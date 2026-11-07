package com.forcemind.chatgpt.profiles;

import android.os.*;
import android.graphics.Bitmap;
import android.webkit.WebView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Native-to-page snapshots only. No JavaScript-to-native bridge or background polling. */
final class PageRecoveryManager {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final int HISTORY_PER_TAB=6,HISTORY_PER_PROCESS=48,HISTORY_TEXT_BUDGET=256000;
    private static final class HistoryDraft {
        final JSONObject record;final int size;long usedAt=SystemClock.uptimeMillis();
        HistoryDraft(JSONObject record){this.record=record;size=record.toString().length();}
    }
    private MainActivity activity;
    final PageRecoveryStore store;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<WebView,Session> sessions=new IdentityHashMap<>();
    private final Set<String> notices=new HashSet<>();
    private final Set<Bitmap> pendingPictures=Collections.newSetFromMap(new IdentityHashMap<>());
    private final String script;
    private boolean foreground;private volatile boolean closed;
    private static final class Session {
        final WebView web;final String account,storage,tab,key,token;final long generation;
        final LinkedHashMap<String,HistoryDraft> history=new LinkedHashMap<>(8,.75f,true);
        volatile long serial,captureTicket;long deadline,prepareTicket,lastPictureAttempt;String url,alternative="",previewURL,failureMessage="";boolean installed,loading,restoring,restoreCall,inflight,blocked,liveReady,failed,waitingOffline,authenticationRequired;JSONObject saved,preview,currentPage,currentRecord;
        Session(WebView web,String account,String storage,String tab,long generation){this.web=web;this.account=account;this.storage=storage;this.tab=tab;this.generation=generation;token=WorkspaceState.id();key="__profiles_recovery_"+token.replace("-","");}
    }
    PageRecoveryManager(MainActivity activity)throws Exception{
        this.activity=activity;store=new PageRecoveryStore(activity.getFilesDir(),DevicePayloadKeys.recoveryCipher(activity.getApplicationContext()));
        try(InputStream in=activity.getAssets().open("page-recovery.js")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);script=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
    }
    void attach(MainActivity activity){this.activity=activity;}
    void bind(WebView web,String account,String storage,String tab){sessions.put(web,new Session(web,account,storage,tab,store.generation(account,storage)));}
    private boolean valid(Session s){return !closed&&activity!=null&&sessions.get(s.web)==s&&activity.recoveryValid(s.account,s.storage,s.tab,s.web)&&store.generation(s.account,s.storage)==s.generation;}
    private boolean visible(Session s){return foreground&&activity!=null&&activity.recoveryCurrentWeb()==s.web&&s.web.isShown();}
    /** URL-specific drafts live only in their original owner/generation; disk format stays unchanged. */
    private void rememberHistory(Session s,JSONObject source)throws JSONException{
        if(!valid(s)||source==null||!s.account.equals(source.optString("accountId"))||!s.storage.equals(source.optString("storageId"))||!s.tab.equals(source.optString("tabId")))return;
        String url=PageRecoveryRecord.url(source.optString("url"));if(url==null||!url.equals(source.optString("url")))return;
        JSONObject draft=new JSONObject().put("schema",1).put("accountId",s.account).put("storageId",s.storage).put("tabId",s.tab).put("url",url)
            .put("draft",source.getString("draft")).put("scroll",source.getDouble("scroll")).put("ratio",source.getDouble("ratio")).put("capturedAt",source.getLong("capturedAt"));
        if(source.has("anchor"))draft.put("anchor",new JSONObject(source.getJSONObject("anchor").toString()));
        try{PageRecoveryRecord.normalize(draft);}catch(Exception invalid){throw new JSONException("Invalid history draft");}
        s.history.remove(url);HistoryDraft remembered=new HistoryDraft(draft);if(remembered.size>HISTORY_TEXT_BUDGET)return;
        s.history.put(url,remembered);while(s.history.size()>HISTORY_PER_TAB)s.history.remove(s.history.keySet().iterator().next());trimHistory();
    }
    private void trimHistory(){
        while(true){int count=0;long size=0,oldest=Long.MAX_VALUE;Session owner=null;String url=null;
            for(Session s:sessions.values())for(Map.Entry<String,HistoryDraft> entry:s.history.entrySet()){HistoryDraft record=entry.getValue();count++;size+=record.size;if(record.usedAt<oldest){oldest=record.usedAt;owner=s;url=entry.getKey();}}
            if(count<=HISTORY_PER_PROCESS&&size<=HISTORY_TEXT_BUDGET||owner==null)return;owner.history.remove(url);
        }
    }
    private JSONObject preferHistory(Session s,String url,JSONObject persisted)throws JSONException{
        if(!valid(s))return null;HistoryDraft remembered=s.history.get(url);
        if(remembered!=null){remembered.usedAt=SystemClock.uptimeMillis();return new JSONObject(remembered.record.toString());}
        if(persisted!=null)rememberHistory(s,persisted);return persisted;
    }
    void prepare(WebView web,String address,Runnable open){
        Session s=sessions.get(web);String url=PageRecoveryRecord.url(address);if(s==null||url==null){open.run();return;}long ticket=++s.prepareTicket;
        IO.execute(()->{JSONObject record=null;Exception failure=null;try{record=store.load(s.account,s.storage,s.tab,url);}catch(Exception e){failure=e;}JSONObject persisted=record;Exception error=failure;
            handler.post(()->{if(!valid(s)||s.prepareTicket!=ticket)return;JSONObject loaded=persisted;try{if(error==null)loaded=preferHistory(s,url,persisted);}catch(JSONException invalid){s.blocked=true;notice("运行期草稿暂不可读，原记录已保留。");}if(error!=null){s.blocked=true;notice("本地恢复记录暂不可读，原文件已保留；网页可继续使用。");}
                if(loaded!=null&&loaded.has("page")){s.preview=loaded;s.currentRecord=loaded;s.currentPage=loaded.optJSONObject("page");s.previewURL=url;s.liveReady=false;if(activity!=null)activity.recoveryShowSnapshot(web,s.preview,"");}
                open.run();});});
    }
    void navigating(WebView web){navigating(web,null);}
    void navigating(WebView web,String address){Session s=sessions.get(web);if(s!=null){String next=address==null?null:PageRecoveryRecord.url(address);
        if(address!=null&&s.previewURL!=null&&!Objects.equals(next,s.previewURL)){s.preview=null;s.previewURL=null;if(activity!=null)activity.recoveryHideSnapshot(web);}
        if(address!=null&&s.url!=null&&!Objects.equals(next,s.url)){s.currentPage=null;s.currentRecord=null;}
        s.serial++;s.installed=false;s.loading=false;s.restoring=false;s.restoreCall=false;s.inflight=false;s.saved=null;s.liveReady=false;s.failed=false;s.failureMessage="";s.waitingOffline=false;s.authenticationRequired=false;}}
    void ready(WebView web){
        Session s=sessions.get(web);if(s==null||!valid(s)||web.getProgress()<100||s.authenticationRequired||s.failed&&s.preview!=null)return;String url=PageRecoveryRecord.url(web.getUrl());
        if(url==null){navigating(web,web.getUrl());return;}if(s.installed&&url.equals(s.url)){visible();return;}
        if(s.url!=null&&!url.equals(s.url)){s.currentPage=null;s.currentRecord=null;s.preview=null;s.previewURL=null;if(activity!=null)activity.recoveryHideSnapshot(web);}
        s.serial++;s.url=url;s.installed=false;s.loading=true;s.restoring=false;s.restoreCall=false;s.inflight=false;s.saved=null;long serial=s.serial;
        try{
            JSONObject config=new JSONObject().put("key",s.key).put("token",s.token);
            web.evaluateJavascript(script.replace("__CONFIG__",config.toString()),raw->{if(!valid(s)||s.serial!=serial)return;try{
                JSONObject result=parsed(raw);if(!result.optString("status").equals("installed"))throw new IOException();s.installed=true;
                IO.execute(()->{try{JSONObject record=store.load(s.account,s.storage,s.tab,url);handler.post(()->{if(!valid(s)||s.serial!=serial)return;s.loading=false;JSONObject restored;try{restored=preferHistory(s,url,record);}catch(JSONException invalid){s.blocked=true;notice("运行期草稿暂不可读，原记录已保留。");return;}s.saved=restored;s.currentRecord=restored;if(restored!=null){s.currentPage=restored.optJSONObject("page");s.alternative=restored.optString("alternativeDraft",s.alternative);s.restoring=true;s.deadline=0;restore(s);}else finish(s);});}
                    catch(Exception e){handler.post(()->{if(!valid(s)||s.serial!=serial)return;s.loading=false;s.blocked=true;notice("恢复记录暂不可读，已保留原文件；网页可继续使用。可从账号菜单查看本地草稿。");});}});
            }catch(Exception e){s.loading=false;notice("此页面暂不支持草稿恢复，网页可继续使用。");}});
        }catch(Exception e){s.loading=false;notice("无法准备页面恢复组件，网页可继续使用。");}
    }
    private JSONObject parsed(String raw)throws Exception{if(raw==null||raw.length()>1400000)throw new IOException();Object value=new JSONTokener(raw).nextValue();return value instanceof JSONObject?(JSONObject)value:new JSONObject(String.valueOf(value));}
    private void evaluate(Session s,String method,String argument,Consumer<JSONObject> callback){
        if(!valid(s)||!s.installed||!Objects.equals(s.url,PageRecoveryRecord.url(s.web.getUrl())))return;long serial=s.serial;
        String js="(function(){try{var a=window["+JSONObject.quote(s.key)+"];if(window!==top||!a||a.token!=="+JSONObject.quote(s.token)+")return JSON.stringify({status:'skip'});return JSON.stringify(a."+method+"("+argument+"));}catch(e){return JSON.stringify({status:'skip'});}})()";
        s.web.evaluateJavascript(js,raw->{if(!valid(s)||s.serial!=serial||!Objects.equals(s.url,PageRecoveryRecord.url(s.web.getUrl())))return;try{callback.accept(parsed(raw));}catch(Exception e){notice("页面恢复检查未完成，已保留本地记录。");}});
    }
    private void restore(Session s){
        if(!valid(s)||!s.restoring||s.restoreCall||!visible(s))return;s.restoreCall=true;if(s.deadline==0)s.deadline=SystemClock.elapsedRealtime()+15000;
        boolean force=SystemClock.elapsedRealtime()>=s.deadline;String args=s.saved.toString()+","+force;
        evaluate(s,"restore",args,result->{String status=result.optString("status");
            if(status.equals("waiting")&&!force){s.restoreCall=false;handler.postDelayed(()->restore(s),300);return;}
            if(status.equals("restored")||status.equals("already-sent")){finish(s);return;}
            if(status.equals("unavailable")&&s.preview!=null){s.restoring=false;s.restoreCall=false;s.failed=true;s.failureMessage="暂时无法连接";s.waitingOffline=true;if(activity!=null)activity.recoveryShowSnapshot(s.web,s.preview,s.failureMessage);return;}preserve(s);
        });
    }
    private void preserve(Session s){if(s.saved!=null&&!s.saved.optString("draft").isEmpty()){s.alternative=s.saved.optString("draft");notice("本地草稿已保留，可从账号菜单的“本地恢复草稿”查看；不会覆盖网页现有内容。");}finish(s);}
    private void finish(Session s){s.restoring=false;s.restoreCall=false;s.deadline=0;s.liveReady=true;s.failed=false;s.failureMessage="";s.preview=null;s.previewURL=null;if(activity!=null)activity.recoveryHideSnapshot(s.web);evaluate(s,"finish","",ignored->{});}
    void visible(){if(activity==null)return;WebView web=activity.recoveryCurrentWeb();Session s=sessions.get(web);if(s==null)return;if(!s.liveReady&&s.preview!=null)activity.recoveryShowSnapshot(web,s.preview,s.failureMessage);if(s.restoring)restore(s);}
    boolean failed(WebView web,String address,boolean certificate){Session s=sessions.get(web);if(s==null||!valid(s))return false;String url=PageRecoveryRecord.url(address);
        if(s.preview==null&&s.currentRecord!=null&&s.currentRecord.has("page")&&Objects.equals(s.url,url)){s.preview=s.currentRecord;s.previewURL=url;}
        if(s.preview==null||!Objects.equals(s.previewURL,url))return false;s.failed=true;s.failureMessage=certificate?"证书校验失败":"暂时无法连接";s.liveReady=false;if(activity!=null)activity.recoveryShowSnapshot(web,s.preview,s.failureMessage);return true;}
    void revealForAuthentication(WebView web){Session s=sessions.get(web);if(s==null)return;s.serial++;s.preview=null;s.previewURL=null;s.liveReady=true;s.waitingOffline=false;s.authenticationRequired=true;s.restoring=false;s.restoreCall=false;s.installed=false;if(activity!=null)activity.recoveryHideSnapshot(web);}
    void checkpoint(){if(activity==null)return;WebView web=activity.recoveryCurrentWeb();Session s=sessions.get(web);if(s!=null)capture(s,true);}
    void checkpointVisible(){for(Session s:new ArrayList<>(sessions.values()))if(s.web.getParent()!=null&&s.web.isShown())capture(s,true);}
    void beforeNavigation(WebView web,Runnable approved,Runnable cancelled){
        Session s=sessions.get(web);String original=web.getUrl();long serial=s==null?-1:s.serial;
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Boolean> done=success->{if(!completed.compareAndSet(false,true))return;boolean current=activity!=null&&activity.recoveryCurrentWeb()==web&&Objects.equals(original,web.getUrl())&&(s==null||valid(s)&&s.serial==serial);if(success&&current)approved.run();else{if(current)notice("页面尚未完成草稿保护，请稍候重试；当前页面已保留。");cancelled.run();}};
        handler.postDelayed(()->done.accept(false),4000);
        if(s!=null&&(s.restoring||s.waitingOffline||s.preview!=null&&!s.liveReady)){done.accept(false);return;}
        if(s==null||!s.installed||s.blocked){unprotectedDraftEmpty(web,done);return;}
        if(s.loading||s.inflight){done.accept(false);return;}
        capture(s,true,done);
    }
    JSONObject environmentNavigationState(WebView web)throws JSONException{
        if(!BuildConfig.ENVIRONMENT_TEST)throw new IllegalStateException("Environment test required");
        Session s=sessions.get(web);JSONObject result=new JSONObject().put("bound",s!=null);if(s==null)return result;
        return result.put("serial",s.serial).put("captureTicket",s.captureTicket).put("valid",valid(s)).put("installed",s.installed)
            .put("loading",s.loading).put("restoring",s.restoring).put("restoreCall",s.restoreCall).put("inflight",s.inflight).put("blocked",s.blocked)
            .put("liveReady",s.liveReady).put("waitingOffline",s.waitingOffline).put("preview",s.preview!=null).put("failed",s.failed)
            .put("authenticationRequired",s.authenticationRequired).put("currentUrlMatches",Objects.equals(s.url,PageRecoveryRecord.url(web.getUrl())));
    }
    private void unprotectedDraftEmpty(WebView web,Consumer<Boolean> done){
        try{web.evaluateJavascript("(function(){try{return !Array.from(document.querySelectorAll('#prompt-textarea,textarea,[contenteditable=\"true\"]')).some(function(e){return e.getClientRects().length&&!!(e.value||e.innerText||'');});}catch(e){return false;}})()",raw->done.accept("true".equals(raw)));}catch(RuntimeException error){done.accept(false);}
    }
    private void capture(Session s,boolean force){capture(s,force,null);}
    private void capture(Session s,boolean force,Consumer<Boolean> completion){
        if(!valid(s)||!s.installed||s.loading||s.restoring||s.inflight||s.blocked||s.waitingOffline||s.preview!=null&&!s.liveReady){if(completion!=null)completion.accept(false);return;}s.inflight=true;long ticket=++s.captureTicket,serial=s.serial;
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        // Keep this capture exclusive through encrypted IO and its main-thread acknowledgement.
        // Releasing after JavaScript alone lets the next poll invalidate a pending navigation save.
        Consumer<Boolean> done=success->{if(!completed.compareAndSet(false,true))return;boolean current=valid(s)&&s.serial==serial&&s.captureTicket==ticket;if(current)s.inflight=false;if(completion!=null)completion.accept(success&&current);};
        handler.postDelayed(()->{if(valid(s)&&s.serial==serial&&s.inflight&&s.captureTicket==ticket){s.inflight=false;s.captureTicket++;notice("网页繁忙，最近的修改尚未保存，请稍候或手动备份。");done.accept(false);}},2000);
        evaluate(s,"capture",String.valueOf(force),value->{if(completed.get()||s.serial!=serial||s.captureTicket!=ticket){done.accept(false);return;}String status=value.optString("status");if(status.equals("oversize")){notice("草稿超过 10 万字符，本地未保存，请手动备份。");done.accept(false);return;}
            if(status.equals("same")){if(completion!=null)IO.execute(()->handler.post(()->done.accept(true)));else{done.accept(true);if(s.currentPage!=null&&!s.currentPage.has("image"))capturePicture(s,s.currentPage,s.url,serial,ticket,false);}return;}
            if(!status.equals("captured")){if(completion!=null)unprotectedDraftEmpty(s.web,done);else done.accept(false);return;}
            try{if(!Objects.equals(s.url,PageRecoveryRecord.url(value.getString("url")))){done.accept(false);return;}JSONObject record=new JSONObject().put("schema",1).put("accountId",s.account).put("storageId",s.storage).put("tabId",s.tab).put("url",s.url).put("draft",value.getString("draft")).put("scroll",value.getDouble("scroll")).put("ratio",value.getDouble("ratio")).put("capturedAt",System.currentTimeMillis());
                if(value.has("anchor"))record.put("anchor",value.getJSONObject("anchor"));if(!s.alternative.isEmpty()&&!s.alternative.equals(record.getString("draft")))record.put("alternativeDraft",s.alternative);
                if(value.has("page")){JSONObject rawPage=new JSONObject(value.getJSONObject("page").toString());rawPage.remove("image");rawPage.remove("imageCapturedAt");JSONObject page=PageSnapshot.normalize(rawPage,s.url);
                    if(s.currentPage!=null&&s.currentPage.has("image")){page.put("image",s.currentPage.getString("image"));page.put("imageCapturedAt",s.currentPage.getLong("imageCapturedAt"));}record.put("page",page);
                }else if(s.currentPage!=null)record.put("page",s.currentPage);PageRecoveryRecord.normalize(record);rememberHistory(s,record);
                if(record.toString().getBytes(StandardCharsets.UTF_8).length>1024*1024&&record.optJSONObject("page")!=null){record.getJSONObject("page").remove("image");record.getJSONObject("page").remove("imageCapturedAt");}
                JSONObject savedPage=record.optJSONObject("page");String pageURL=s.url;IO.execute(()->{try{boolean changed=store.save(record,s.generation);handler.post(()->{boolean current=valid(s)&&s.serial==serial&&s.captureTicket==ticket&&!completed.get();if(current){if(changed){s.currentRecord=record;s.currentPage=savedPage;}JSONObject page=s.currentPage;if(completion==null&&page!=null)capturePicture(s,page,pageURL,serial,ticket,force);}done.accept(current);});}catch(Exception e){handler.post(()->{if(valid(s))notice("本地草稿保存未完成，请手动备份；不会写入未加密内容。");done.accept(false);});}});
            }catch(Exception e){notice("页面草稿或阅读位置无效，本次未保存。");done.accept(false);}
        });
    }
    private void capturePicture(Session s,JSONObject page,String url,long serial,long ticket,boolean force){
        if(activity==null||!activity.recoveryCanCapturePicture(s.web)||!force&&s.lastPictureAttempt>0&&SystemClock.elapsedRealtime()-s.lastPictureAttempt<30000)return;
        evaluate(s,"pictureSafe","",safe->{if(!"safe".equals(safe.optString("status"))||!valid(s)||s.serial!=serial||s.captureTicket!=ticket||activity==null||!activity.recoveryCanCapturePicture(s.web))return;
            s.lastPictureAttempt=SystemClock.elapsedRealtime();activity.recoveryCapturePicture(s.web,bitmap->{if(bitmap==null)return;
                if(!valid(s)||s.serial!=serial||s.captureTicket!=ticket||activity==null||!activity.recoveryCanCapturePicture(s.web)){bitmap.recycle();return;}
                pendingPictures.add(bitmap);Runnable expiry=()->releasePicture(bitmap);handler.postDelayed(expiry,2000);
                try{evaluate(s,"pictureSafe","",after->{if(!"safe".equals(after.optString("status"))||!valid(s)||s.serial!=serial||s.captureTicket!=ticket||activity==null||!activity.recoveryCanCapturePicture(s.web)){handler.removeCallbacks(expiry);releasePicture(bitmap);return;}
                    if(!pendingPictures.remove(bitmap))return;handler.removeCallbacks(expiry);String fingerprint;try{fingerprint=PageSnapshot.contentFingerprint(page);}catch(Exception error){bitmap.recycle();return;}
                    IO.execute(()->{Bitmap smaller=null;try{
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int quality:new int[]{55,35}){bytes.reset();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,bytes))return;if(bytes.size()<=PageSnapshot.MAX_IMAGE_BYTES)break;}
                        if(bytes.size()>PageSnapshot.MAX_IMAGE_BYTES){smaller=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()/2),Math.max(1,bitmap.getHeight()/2),true);for(int quality:new int[]{50,30}){bytes.reset();if(!smaller.compress(Bitmap.CompressFormat.JPEG,quality,bytes))return;if(bytes.size()<=PageSnapshot.MAX_IMAGE_BYTES)break;}}
                        if(bytes.size()>PageSnapshot.MAX_IMAGE_BYTES||closed||s.serial!=serial||s.captureTicket!=ticket)return;
                        JSONObject latest=store.load(s.account,s.storage,s.tab,url),current=latest==null?null:latest.optJSONObject("page");if(current==null||!PageSnapshot.contentFingerprint(current).equals(fingerprint))return;
                        current.put("image",Base64.getEncoder().encodeToString(bytes.toByteArray())).put("imageCapturedAt",System.currentTimeMillis());PageSnapshot.normalize(current,url);
                        if(latest.toString().getBytes(StandardCharsets.UTF_8).length>1024*1024)return;boolean changed=store.save(latest,s.generation);
                        handler.post(()->{if(changed&&valid(s)&&s.serial==serial&&s.captureTicket==ticket){s.currentRecord=latest;s.currentPage=current;}});
                    }catch(Exception|OutOfMemoryError ignored){/* Saved text remains usable when picture capture fails. */}finally{if(smaller!=null&&smaller!=bitmap)smaller.recycle();bitmap.recycle();}});
                });}catch(RuntimeException error){handler.removeCallbacks(expiry);releasePicture(bitmap);}
            });
        });
    }
    private final Runnable poll=new Runnable(){public void run(){if(closed||!foreground)return;Session s=sessions.get(activity.recoveryCurrentWeb());if(s!=null&&visible(s))capture(s,false);handler.postDelayed(this,4000);}};
    private void releasePicture(Bitmap bitmap){if(pendingPictures.remove(bitmap)&&!bitmap.isRecycled())bitmap.recycle();}
    void resume(){foreground=true;handler.removeCallbacks(poll);handler.post(poll);if(activity!=null){Session s=sessions.get(activity.recoveryCurrentWeb());if(s!=null&&s.waitingOffline&&!s.failed&&s.installed&&s.saved!=null){s.waitingOffline=false;s.restoring=true;s.deadline=0;}}visible();}
    void pause(){checkpoint();foreground=false;handler.removeCallbacks(poll);for(Session s:sessions.values())if(s.restoring){if(s.preview!=null){s.restoring=false;s.restoreCall=false;s.waitingOffline=true;}else preserve(s);}}
    void unbind(WebView web){Session s=sessions.remove(web);if(s!=null){s.serial++;s.history.clear();}}
    void clear(String account,String storage)throws Exception{store.clear(account,storage);for(Session s:sessions.values())if(s.account.equals(account)&&s.storage.equals(storage)){s.serial++;s.blocked=true;s.history.clear();}}
    void forget(String account,String storage,String tab){for(Session s:sessions.values())if(s.account.equals(account)&&s.storage.equals(storage)&&s.tab.equals(tab)){s.serial++;s.history.clear();}IO.execute(()->{try{store.forget(account,storage,tab);}catch(Exception e){handler.post(()->notice("旧标签恢复记录未能清理，文件已保留。"));}});}
    void localDraft(Consumer<JSONObject> done){
        if(activity==null){done.accept(null);return;}Session s=sessions.get(activity.recoveryCurrentWeb());if(s==null){done.accept(null);return;}String url=PageRecoveryRecord.url(s.web.getUrl());IO.execute(()->{try{JSONObject record=store.load(s.account,s.storage,s.tab,url);handler.post(()->{if(valid(s)&&visible(s)&&Objects.equals(s.url,PageRecoveryRecord.url(s.web.getUrl())))done.accept(record);});}catch(Exception e){handler.post(()->notice("本地恢复记录暂不可读，请保留应用数据并反馈。"));}});
    }
    void close(){closed=true;foreground=false;handler.removeCallbacksAndMessages(null);for(Bitmap bitmap:new ArrayList<>(pendingPictures))releasePicture(bitmap);for(Session s:sessions.values())s.history.clear();sessions.clear();}
    private void notice(String message){if(!closed&&activity!=null&&notices.add(message))activity.recoveryNotice(message);}
}
