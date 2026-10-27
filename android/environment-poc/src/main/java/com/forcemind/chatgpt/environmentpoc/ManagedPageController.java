package com.forcemind.chatgpt.environmentpoc;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

/** App-owned restoration, limited to the independent loopback fixture. */
final class ManagedPageController {
    static final String EXTENSION="studio-recovery-poc@local.invalid",APP="studioRecovery";
    static final class Entry {
        final String account,id;final GeckoSession session;final Object inputLock=new Object();
        JSONObject record,preview,current,desired,approved,conflict;
        WebExtension.Port port;String url,document="",pendingEditor="",state="loading",error="",fingerprint="",checkpoint="",checkpointSource="";
        volatile String token="";volatile long epoch;volatile boolean visible,closed;
        long sequence,revision;int rejected;boolean blocked,writing,awaitingScroll,loading=true;
        Entry(String account,int index){this.account=account;id=Integer.toString(index);session=new GeckoSession(new GeckoSessionSettings.Builder().contextId("managed-recovery-"+account).build());}
    }
    interface Listener {void changed(String account);}
    interface InputViewProvider {View view(Entry entry);}
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final GeckoRuntime runtime;final RecoveryStore store;private final Listener listener;private final InputViewProvider inputViews;
    private final Map<String,Entry> entries=new HashMap<>();private WebExtension extension;String startupError="";
    ManagedPageController(GeckoRuntime runtime,RecoveryStore store,Listener listener,InputViewProvider inputViews){
        this.runtime=runtime;this.store=store;this.listener=listener;this.inputViews=inputViews;
        runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/managed-recovery/",EXTENSION).accept(ext->{extension=ext;for(Entry e:entries.values())if(!e.blocked)attach(e);},error->{startupError=error.toString();for(Entry e:entries.values())fail(e,"bridge-install-failed");});
    }
    static boolean allowed(String url,String account,String id){
        if(url==null)return false;Uri u=Uri.parse(url);
        return "http".equals(u.getScheme())&&"127.0.0.1".equals(u.getHost())&&u.getPort()==8765&&u.getUserInfo()==null&&"/recovery".equals(u.getPath())
            &&account.equals(u.getQueryParameter("account"))&&id.equals(u.getQueryParameter("tab"))&&("start".equals(u.getQueryParameter("page"))||"detail".equals(u.getQueryParameter("page")));
    }
    private static String string(JSONObject value,String key,int limit)throws Exception{
        Object raw=value.get(key);if(!(raw instanceof String)||((String)raw).length()>limit)throw new IllegalArgumentException("Invalid "+key);return (String)raw;
    }
    static String digest(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject drafts(JSONObject value)throws Exception{return new JSONObject().put("url",value.getString("url")).put("standard",value.getString("standard")).put("rich",value.getString("rich"));}
    private JSONObject normalize(Entry e,JSONObject raw)throws Exception{
        String url=string(raw,"url",2048);if(!allowed(url,e.account,e.id))throw new IllegalArgumentException("Foreign page URL");
        double scroll=raw.getDouble("scroll"),height=raw.getDouble("height"),viewport=raw.getDouble("viewport");
        if(!Double.isFinite(scroll)||!Double.isFinite(height)||!Double.isFinite(viewport)||scroll<0||scroll>5000000||height<1||height>10000000||viewport<1||viewport>100000)throw new IllegalArgumentException("Invalid reading position");
        return new JSONObject().put("url",url).put("standard",string(raw,"standard",32768)).put("rich",string(raw,"rich",32768)).put("transcript",string(raw,"transcript",50000))
            .put("scroll",Math.rint(scroll*10)/10).put("height",height).put("viewport",viewport);
    }
    Entry open(String account,int index)throws Exception{
        String key=account+":"+index;if(entries.containsKey(key))return entries.get(key);
        Entry e=new Entry(account,index);entries.put(key,e);
        try{
            e.record=store.read(account,"page-"+e.id);
            if(e.record==null)e.record=new JSONObject().put("kind","managed-page-v1").put("pages",new JSONArray()).put("history",new JSONArray()).put("historyIndex",-1).put("alternatives",new JSONArray()).put("pendingConflicts",new JSONArray());
            if(!"managed-page-v1".equals(e.record.getString("kind")))throw new IllegalArgumentException("Invalid page record");
            if(e.record.optJSONArray("pendingConflicts")==null){JSONArray pending=new JSONArray();JSONObject old=e.record.optJSONObject("pendingConflict");if(old!=null)pending.put(old);e.record.put("pendingConflicts",pending);e.record.remove("pendingConflict");}
            JSONArray pages=e.record.getJSONArray("pages"),history=e.record.getJSONArray("history");if(pages.length()>4||history.length()>16||e.record.getJSONArray("alternatives").length()>4)throw new IllegalArgumentException("Oversized record");
            for(int i=0;i<pages.length();i++)normalize(e,pages.getJSONObject(i));
            for(int i=0;i<history.length();i++)if(!allowed(history.getString(i),account,e.id))throw new IllegalArgumentException("Foreign history");
            e.url=e.record.optString("url","http://127.0.0.1:8765/recovery?account="+account+"&tab="+index+"&page=start");
            if(!allowed(e.url,account,e.id))throw new IllegalArgumentException("Invalid saved URL");e.preview=page(e,e.url);e.revision=e.record.optLong("revision",0);
        }catch(Exception error){e.blocked=true;e.state="blocked";e.error="saved-record-rejected:"+error.getClass().getSimpleName();return e;}
        e.session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,LoadRequest request){
                if(!allowed(request.uri,e.account,e.id)){e.rejected++;changed(e);return GeckoResult.fromValue(AllowOrDeny.DENY);}
                navigation(e,request.uri);return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s,LoadRequest request){return GeckoResult.fromValue(AllowOrDeny.DENY);}
        });
        e.session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onPageStop(GeckoSession s,boolean success){if(!success&&!e.closed&&e.port==null){e.state="offline";changed(e);}}
        });
        e.session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onCrash(GeckoSession s){fail(e,"content-process-crashed");}
            @Override public void onKill(GeckoSession s){fail(e,"content-process-killed");}
        });
        e.session.open(runtime);e.session.setActive(true);e.session.setFocused(false);if(extension!=null)attach(e);changed(e);return e;
    }
    private JSONObject page(Entry e,String url)throws Exception{JSONArray pages=e.record.getJSONArray("pages");for(int i=0;i<pages.length();i++){JSONObject p=pages.getJSONObject(i);if(url.equals(p.getString("url")))return copy(p);}return null;}
    private void attach(Entry e){
        e.session.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate(){
            @Override public void onConnect(WebExtension.Port port){
                if(e.closed||e.blocked||port.sender.session!=e.session||!port.sender.isTopLevel()||port.sender.environmentType!=WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT||!EXTENSION.equals(port.sender.webExtension.id)||!e.url.equals(port.sender.url)){
                    e.rejected++;port.setDelegate(new WebExtension.PortDelegate(){});port.disconnect();changed(e);return;
                }
                if(e.port!=null)e.port.disconnect();e.port=port;e.token=UUID.randomUUID().toString();e.sequence=0;e.document="";
                port.setDelegate(new WebExtension.PortDelegate(){
                    @Override public void onPortMessage(Object raw,WebExtension.Port source){message(e,source,raw);}
                    @Override public void onDisconnect(WebExtension.Port source){if(e.port==source){e.port=null;if(!e.closed&&!e.loading){e.state="offline";changed(e);}}}
                });
            }
        },APP);
        e.session.loadUri(e.url);
    }
    private void navigation(Entry e,String url){
        try{
            synchronized(e.inputLock){e.epoch++;e.token="";}if(e.port!=null){WebExtension.Port old=e.port;e.port=null;old.disconnect();}
            e.url=url;e.preview=page(e,url);e.current=null;e.desired=null;e.approved=null;e.conflict=null;e.pendingEditor="";e.writing=false;e.awaitingScroll=false;e.loading=true;e.state="loading";e.error="";e.checkpoint="";changed(e);
            JSONArray history=e.record.getJSONArray("history");int index=e.record.getInt("historyIndex");
            if(index<0||index>=history.length()||!url.equals(history.getString(index))){JSONArray next=new JSONArray();for(int i=0;i<=index&&i<history.length();i++)next.put(history.getString(i));next.put(url);while(next.length()>16)next.remove(0);e.record.put("history",next).put("historyIndex",next.length()-1);}
            e.record.put("url",url);persist(e);
        }catch(Exception error){fail(e,"navigation-record-invalid");}
    }
    private void message(Entry e,WebExtension.Port port,Object value){
        try{
            if(!(value instanceof JSONObject)||e.port!=port||e.closed)throw new IllegalArgumentException("Stale port");JSONObject msg=(JSONObject)value;
            if(msg.toString().length()>180000||!e.url.equals(string(msg,"url",2048))||!e.url.equals(port.sender.url)||!port.sender.isTopLevel())throw new IllegalArgumentException("Invalid sender");
            Object sequence=msg.get("sequence");if(!(sequence instanceof Number)||((Number)sequence).longValue()<=e.sequence)throw new IllegalArgumentException("Stale sequence");e.sequence=((Number)sequence).longValue();
            String type=string(msg,"type",32),document=string(msg,"document",64);UUID.fromString(document);
            if("hello".equals(type)){
                if(!e.document.isEmpty())throw new IllegalArgumentException("Duplicate handshake");e.document=document;send(e,new JSONObject().put("type","auth"));return;
            }
            if(!e.document.equals(document)||!e.token.equals(msg.getString("token"))||e.epoch!=msg.getLong("epoch"))throw new IllegalArgumentException("Stale document");
            JSONObject current=normalize(e,msg.getJSONObject("snapshot"));if(!e.url.equals(current.getString("url")))throw new IllegalArgumentException("Wrong page snapshot");e.current=current;
            if("ready".equals(type)){e.loading=false;reconcile(e,current);return;}
            if("focused".equals(type)){
                if(!e.pendingEditor.equals(msg.getString("editor"))||!msg.getBoolean("active")||!e.visible)throw new IllegalArgumentException("Unexpected editor focus");commit(e,current,e.pendingEditor);return;
            }
            if("scrolled".equals(type)&&e.awaitingScroll){
                if(e.desired!=null&&(!current.getString("standard").equals(e.desired.getString("standard"))||!current.getString("rich").equals(e.desired.getString("rich")))){showConflict(e,current);return;}
                double expected=Math.max(0,Math.min(e.preview==null?0:e.preview.getDouble("scroll"),current.getDouble("height")-current.getDouble("viewport")));
                if(Math.abs(current.getDouble("scroll")-expected)>80){fail(e,"reading-position-not-confirmed:"+expected+"/"+current.getDouble("scroll"));return;}
                e.awaitingScroll=false;complete(e,current);return;
            }
            if("capture".equals(type)||"checkpoint".equals(type)){
                if("restoring".equals(e.state)){advance(e,current);}
                else if("ready".equals(e.state)){save(e,current);}
                else if("conflict".equals(e.state)&&e.conflict!=null&&!drafts(e.conflict.getJSONObject("current")).toString().equals(drafts(current).toString())){showConflict(e,current);}
                if("checkpoint".equals(type)&&"ready".equals(e.state)){e.checkpoint=msg.getString("request");e.checkpointSource="live-page";changed(e);}
            }
        }catch(java.io.IOException error){fail(e,"save-failed-original-preserved");}
        catch(Exception error){e.rejected++;e.error="bridge-rejected:"+error.getClass().getSimpleName();changed(e);}
    }
    private static boolean conflict(JSONObject saved,JSONObject current)throws Exception{
        for(String key:new String[]{"standard","rich"}){String old=saved.getString(key),live=current.getString(key);if(!old.isEmpty()&&!live.isEmpty()&&!old.equals(live))return true;}return false;
    }
    private void reconcile(Entry e,JSONObject current)throws Exception{
        if(e.preview==null){complete(e,current);return;}
        if(pending(e,e.url)!=null||conflict(e.preview,current)){showConflict(e,current);return;}
        e.desired=copy(e.preview);for(String key:new String[]{"standard","rich"})if(e.desired.getString(key).isEmpty())e.desired.put(key,current.getString(key));e.approved=copy(current);e.state="restoring";advance(e,current);
    }
    private void showConflict(Entry e,JSONObject current)throws Exception{
        e.state="conflict";e.pendingEditor="";e.writing=false;e.awaitingScroll=false;e.desired=null;e.conflict=new JSONObject().put("saved",copy(e.preview)).put("current",copy(current)).put("url",e.url).put("document",e.document);
        JSONObject old=pending(e,e.url);
        if(old!=null&&!drafts(old.getJSONObject("current")).toString().equals(drafts(current).toString())){
            JSONArray alternatives=e.record.getJSONArray("alternatives");alternatives.put(new JSONObject().put("saved",drafts(old.getJSONObject("saved"))).put("current",drafts(old.getJSONObject("current"))).put("choice","unresolved-kept").put("at",System.currentTimeMillis()));while(alternatives.length()>4)alternatives.remove(0);
        }
        JSONArray list=withoutPending(e,e.url);list.put(new JSONObject().put("saved",drafts(e.preview)).put("current",drafts(current)));if(list.length()>4)throw new IllegalStateException("Pending conflict limit reached");e.record.put("pendingConflicts",list);persist(e);changed(e);
    }
    private JSONObject pending(Entry e,String url)throws Exception{JSONArray list=e.record.optJSONArray("pendingConflicts");if(list!=null)for(int i=0;i<list.length();i++){JSONObject pair=list.getJSONObject(i);if(url.equals(pair.getJSONObject("saved").getString("url")))return pair;}return null;}
    private JSONArray withoutPending(Entry e,String url)throws Exception{JSONArray next=new JSONArray(),list=e.record.getJSONArray("pendingConflicts");for(int i=0;i<list.length();i++)if(!url.equals(list.getJSONObject(i).getJSONObject("saved").getString("url")))next.put(list.getJSONObject(i));return next;}
    void choose(Entry e,boolean restoreSaved)throws Exception{
        if(!"conflict".equals(e.state)||e.conflict==null||e.current==null)return;
        JSONArray alternatives=e.record.getJSONArray("alternatives");alternatives.put(new JSONObject().put("saved",drafts(e.conflict.getJSONObject("saved"))).put("current",drafts(e.current)).put("choice",restoreSaved?"saved":"current").put("at",System.currentTimeMillis()));while(alternatives.length()>4)alternatives.remove(0);
        e.record.put("alternatives",alternatives).put("pendingConflicts",withoutPending(e,e.url));persist(e);e.conflict=null;
        if(!restoreSaved){complete(e,e.current);return;}
        e.desired=copy(e.preview);e.approved=copy(e.current);e.state="restoring";advance(e,e.current);changed(e);
    }
    private void advance(Entry e,JSONObject current)throws Exception{
        if(e.desired==null||e.writing||e.awaitingScroll)return;
        if(!e.pendingEditor.isEmpty()){
            if(!current.getString(e.pendingEditor).equals(e.desired.getString(e.pendingEditor)))return;
            e.pendingEditor="";
        }
        for(String key:new String[]{"standard","rich"}){
            String live=current.getString(key),goal=e.desired.getString(key);
            if(!live.equals(goal)){
                if(!live.equals(e.approved.getString(key))){showConflict(e,current);return;}
                if(!e.visible){e.state="restoring";changed(e);return;}
                View target=inputViews.view(e);if(target==null||!target.hasWindowFocus())return;
                if(e.session.getTextInput().getView()!=target)e.session.getTextInput().setView(target);
                target.requestFocus();focus(e);
                if(!e.pendingEditor.isEmpty())return;e.pendingEditor=key;send(e,new JSONObject().put("type","focus").put("editor",key));changed(e);return;
            }
        }
        e.pendingEditor="";e.awaitingScroll=true;send(e,new JSONObject().put("type","scroll").put("top",e.preview==null?0:e.preview.getDouble("scroll")));
    }
    private void commit(Entry e,JSONObject current,String editor)throws Exception{
        if(e.writing||e.desired==null)return;
        if(!current.getString(editor).equals(e.approved.getString(editor))){showConflict(e,current);return;}
        String expected=current.getString(editor),goal=e.desired.getString(editor),token=e.token;long epoch=e.epoch;long started=android.os.SystemClock.elapsedRealtime();e.writing=true;
        ui.post(new Runnable(){boolean restarted;public void run(){
            if(e.closed||e.epoch!=epoch||!token.equals(e.token))return;
            if(!e.visible){e.writing=false;e.pendingEditor="";return;}
            View view=inputViews.view(e);
            if(view!=null&&e.session.getTextInput().getView()!=view)e.session.getTextInput().setView(view);
            if(view!=null&&!view.hasFocus()){view.requestFocus();ui.postDelayed(this,100);return;}
            if(view!=null&&view.hasWindowFocus())focus(e);
            InputConnection input=view==null?null:view.onCreateInputConnection(new EditorInfo());
            if(view==null||!view.hasWindowFocus()||input==null){
                if(view!=null&&view.hasWindowFocus()&&!restarted){
                    ((android.view.inputmethod.InputMethodManager)view.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).restartInput(view);restarted=true;
                    try{send(e,new JSONObject().put("type","focus").put("editor",editor));}catch(Exception error){fail(e,"editor-refocus-failed");return;}
                }
                if(android.os.SystemClock.elapsedRealtime()-started<5000){ui.postDelayed(this,100);return;}
                fail(e,"editor-input-unavailable:view="+(view!=null)+",window="+(view!=null&&view.hasWindowFocus())+",connection="+(input!=null));return;
            }
            Handler target=input.getHandler();if(target==null)target=ui;
            Runnable retry=this;
            target.post(()->{
                boolean accepted=false;String failure="";boolean pending=false;
                try{synchronized(e.inputLock){
                    if(e.closed||e.epoch!=epoch||!token.equals(e.token)||!e.visible)throw new IllegalStateException("Target changed");
                    ExtractedText text=input.getExtractedText(new ExtractedTextRequest(),0);
                    if(text==null||text.text==null||!expected.equals(text.text.toString())){pending=true;throw new IllegalStateException("Editor binding not confirmed");}
                    input.beginBatchEdit();try{if(!input.setSelection(0,expected.length()))throw new IllegalStateException("Selection refused");accepted=input.commitText(goal,1);}finally{input.endBatchEdit();}
                }}catch(Exception error){failure=error.toString();}
                boolean result=accepted;String reason=failure;boolean notReady=pending;
                ui.post(()->{if(e.closed||e.epoch!=epoch||!token.equals(e.token))return;e.writing=false;
                    if(!result){
                        if(notReady&&e.current!=null&&expected.equals(e.current.optString(editor))&&android.os.SystemClock.elapsedRealtime()-started<5000){e.writing=true;ui.postDelayed(retry,100);return;}
                        if(notReady&&e.current!=null&&!expected.equals(e.current.optString(editor)))try{showConflict(e,e.current);return;}catch(Exception ignored){}
                        fail(e,"editor-write-unconfirmed:"+reason);return;
                    }
                    try{if(e.current!=null&&goal.equals(e.current.optString(editor)))advance(e,e.current);}catch(Exception error){fail(e,"editor-ack-invalid");}
                    ui.postDelayed(()->{if(e.epoch==epoch&&token.equals(e.token)&&"restoring".equals(e.state)&&e.current!=null&&!goal.equals(e.current.optString(editor)))fail(e,"editor-value-not-confirmed");},4000);
                });
            });
        }});
    }
    private void complete(Entry e,JSONObject snapshot)throws Exception{save(e,snapshot);e.state="ready";e.desired=null;e.approved=null;e.conflict=null;e.pendingEditor="";e.writing=false;e.awaitingScroll=false;changed(e);}
    private void save(Entry e,JSONObject snapshot)throws Exception{
        if(e.blocked||e.closed||!e.url.equals(snapshot.getString("url")))return;
        String fingerprint=digest(snapshot.toString());if(fingerprint.equals(e.fingerprint))return;
        JSONArray pages=e.record.getJSONArray("pages"),updated=new JSONArray();for(int i=0;i<pages.length();i++)if(!e.url.equals(pages.getJSONObject(i).getString("url")))updated.put(pages.getJSONObject(i));updated.put(copy(snapshot));while(updated.length()>4)updated.remove(0);e.record.put("pages",updated).put("url",e.url);persist(e);e.preview=copy(snapshot);e.fingerprint=fingerprint;changed(e);
    }
    private void persist(Entry e)throws Exception{long next=e.revision+1;e.record.put("revision",next);if(e.record.toString().getBytes(StandardCharsets.UTF_8).length>900000)throw new java.io.IOException("Recovery quota exceeded; original retained");store.write(e.account,"page-"+e.id,e.record);e.revision=next;}
    private void focus(Entry e){for(Entry other:entries.values())if(other!=e&&other.session.isOpen())other.session.setFocused(false);if(e.session.isOpen())e.session.setFocused(true);}
    void visible(Entry e,boolean visible){synchronized(e.inputLock){e.visible=visible;}if(visible)focus(e);else if(e.session.isOpen())e.session.setFocused(false);if(!visible&&!e.writing)e.pendingEditor="";if(visible&&"restoring".equals(e.state)&&e.current!=null)try{advance(e,e.current);}catch(Exception error){fail(e,"resume-restore-failed");}}
    void retry(Entry e){if(!e.blocked&&!e.closed)e.session.loadUri(e.url);}
    void checkpoint(Entry e,String request)throws Exception{
        if(e.error.startsWith("save-failed"))throw new IllegalStateException("Unsaved current data");
        if(e.port==null||!"ready".equals(e.state)){e.checkpoint=request;e.checkpointSource=e.preview!=null?"last-saved-cache":e.blocked?"blocked-original-preserved":"no-editable-page";changed(e);return;}
        e.checkpoint="";e.checkpointSource="pending-live-page";send(e,new JSONObject().put("type","checkpoint").put("request",request));
    }
    void history(Entry e,boolean forward)throws Exception{JSONArray list=e.record.getJSONArray("history");int index=e.record.getInt("historyIndex")+(forward?1:-1);if(index<0||index>=list.length())return;e.record.put("historyIndex",index).put("url",list.getString(index));persist(e);e.session.loadUri(list.getString(index));}
    private void changed(Entry e){listener.changed(e.account);}
    private void fail(Entry e,String error){if(e.closed)return;e.error=error;e.state="failed";e.pendingEditor="";e.writing=false;changed(e);}
    private void send(Entry e,JSONObject message)throws Exception{if(e.port!=null&&!e.closed)e.port.postMessage(message.put("token",e.token).put("epoch",e.epoch));}
    List<Entry> all(){return new ArrayList<>(entries.values());}
    void closeAll(){for(Entry e:all()){synchronized(e.inputLock){e.closed=true;e.visible=false;e.epoch++;}if(e.port!=null){e.port.disconnect();e.port=null;}if(e.session.isOpen())e.session.close();}entries.clear();}
    JSONObject metadata(Entry e)throws Exception{
        JSONArray alternatives=new JSONArray(),source=e.record==null?null:e.record.optJSONArray("alternatives");
        if(source!=null)for(int i=0;i<source.length();i++){JSONObject pair=source.getJSONObject(i);alternatives.put(new JSONObject().put("savedRichSha256",digest(pair.getJSONObject("saved").getString("rich"))).put("currentRichSha256",digest(pair.getJSONObject("current").getString("rich"))).put("choice",pair.getString("choice")));}
        return new JSONObject().put("id",e.id).put("url",e.url==null?"":e.url).put("state",e.state).put("error",e.error).put("blocked",e.blocked).put("revision",e.revision).put("document",e.document).put("epoch",e.epoch).put("rejected",e.rejected).put("visible",e.visible).put("bridgeConnected",e.port!=null).put("hasSnapshot",e.preview!=null).put("standardSha256",digest(e.preview==null?"":e.preview.optString("standard"))).put("richSha256",digest(e.preview==null?"":e.preview.optString("rich"))).put("transcriptLength",e.preview==null?0:e.preview.optString("transcript").length()).put("alternativeCount",source==null?0:source.length()).put("alternativeHashes",alternatives).put("pendingConflict",e.record!=null&&pending(e,e.url)!=null).put("pendingConflictCount",e.record==null||e.record.optJSONArray("pendingConflicts")==null?0:e.record.getJSONArray("pendingConflicts").length()).put("historyCount",e.record==null||e.record.optJSONArray("history")==null?0:e.record.getJSONArray("history").length()).put("checkpoint",e.checkpoint).put("checkpointSource",e.checkpointSource);
    }
}
