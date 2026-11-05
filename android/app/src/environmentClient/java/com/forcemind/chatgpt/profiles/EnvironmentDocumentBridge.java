package com.forcemind.chatgpt.profiles;

import java.util.UUID;
import org.json.JSONObject;
import org.mozilla.geckoview.*;

/** A document port is bound to a saved owner, one session, URL, epoch and random token. */
final class EnvironmentDocumentBridge {
    interface Host{
        boolean owns(EnvironmentBrowserActivity.Entry entry);
        void document(EnvironmentBrowserActivity.Entry entry,JSONObject value);
        void conflict(EnvironmentBrowserActivity.Entry entry,JSONObject live);
        void notice(String message);
    }
    private final java.lang.ref.WeakReference<Host> target;
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    EnvironmentDocumentBridge(Host host){target=new java.lang.ref.WeakReference<>(host);}
    void attach(EnvironmentBrowserActivity.Entry e,WebExtension extension){
        if(e.port!=null)invalidate(e);
        e.session.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate(){
            @Override public void onConnect(WebExtension.Port port){
                Host host=target.get();WebExtension.MessageSender sender=port.sender;
                if(host==null||e.closed||!host.owns(e)||sender.session!=e.session||!sender.isTopLevel()||sender.environmentType!=WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT||!EnvironmentRouteController.EXTENSION.equals(sender.webExtension.id)||!e.url.equals(sender.url)||EnvironmentUrls.recoverable(e.url)==null){port.setDelegate(new WebExtension.PortDelegate(){});port.disconnect();return;}
                invalidate(e);e.port=port;e.token=UUID.randomUUID().toString();e.sequence=0;
                port.setDelegate(new WebExtension.PortDelegate(){
                    @Override public void onPortMessage(Object raw,WebExtension.Port source){message(e,source,raw);}
                    @Override public void onDisconnect(WebExtension.Port source){if(e.port==source){e.port=null;e.token="";}}
                });
                authenticate(e,port,e.token,e.epoch,0);
            }
        },"studioEnvironmentRecovery");
    }
    private void authenticate(EnvironmentBrowserActivity.Entry e,WebExtension.Port port,String token,long epoch,int attempt){
        if(e.closed||e.port!=port||!e.token.equals(token)||e.epoch!=epoch||e.documentId!=null)return;
        try{send(e,new JSONObject().put("type","auth").put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId));}catch(Exception error){invalidate(e);return;}
        if(attempt<20)ui.postDelayed(()->authenticate(e,port,token,epoch,attempt+1),250);
    }
    void invalidate(EnvironmentBrowserActivity.Entry e){e.epoch++;e.sequence=0;e.token="";e.documentId=null;if(e.port!=null){WebExtension.Port old=e.port;e.port=null;old.disconnect();}e.liveDocument=null;}
    private void message(EnvironmentBrowserActivity.Entry e,WebExtension.Port source,Object raw){
        Host host=target.get();if(host==null)return;
        try{
            if(!(raw instanceof JSONObject)||e.port!=source||e.closed||!host.owns(e)||!source.sender.isTopLevel()||source.sender.session!=e.session||!e.url.equals(source.sender.url))return;
            JSONObject value=(JSONObject)raw;
            if(value.toString().length()>240000||!e.url.equals(value.getString("url"))||!e.accountId.equals(value.getString("accountId"))||!e.storageId.equals(value.getString("storageId"))||!e.tabId.equals(value.getString("tabId"))||!e.token.equals(value.getString("token"))||e.epoch!=value.getLong("epoch"))return;
            Object rawSequence=value.get("sequence");if(!(rawSequence instanceof Number)||((Number)rawSequence).doubleValue()!=((Number)rawSequence).longValue())return;long sequence=((Number)rawSequence).longValue();if(sequence<=e.sequence)return;e.sequence=sequence;
            String document=value.getString("document");UUID.fromString(document);if(e.documentId!=null&&!e.documentId.equals(document))return;e.documentId=document;
            String type=value.getString("type");if("ineligible".equals(type)){e.sensitive=true;e.pendingRestore=null;e.restoringDraft=null;return;}
            if("restore-unavailable".equals(type)){host.notice("页面编辑器暂不能恢复，请保留本地草稿后重试");return;}
            JSONObject snapshot=normalize(e,value.getJSONObject("snapshot"));e.liveDocument=snapshot;
            if("conflict".equals(type)){host.conflict(e,snapshot);return;}
            if(!java.util.Arrays.asList("ready","capture","checkpoint","restored").contains(type))return;
            if(e.restoringDraft!=null&&!"restored".equals(type))return;
            if(e.pendingRestore!=null){
                JSONObject local=e.pendingRestore;
                String saved=local.getString("draft"),live=snapshot.getString("draft");
                if(!saved.isEmpty()&&!live.isEmpty()&&!saved.equals(live)){host.conflict(e,snapshot);return;}
                // Native history restores first; an empty live composer can then recover safely.
                e.pendingRestore=null;
                if(!saved.isEmpty()&&!saved.equals(live)||local.optDouble("scroll")>0){restore(e,local,snapshot);return;}
            }
            if(e.conflictLocal!=null&& !"restored".equals(type)){host.conflict(e,snapshot);return;}
            if("restored".equals(type)&&e.restoringDraft!=null&&!e.restoringDraft.equals(snapshot.getString("draft"))){host.notice("页面未接受本地草稿，恢复副本已保留");return;}
            e.restoringDraft=null;host.document(e,snapshot);
        }catch(Exception rejected){/* Invalid or stale document messages never write a record. */}
    }
    private JSONObject normalize(EnvironmentBrowserActivity.Entry e,JSONObject raw)throws Exception{
        if(!e.url.equals(raw.getString("url"))||EnvironmentUrls.recoverable(e.url)==null)throw new IllegalArgumentException();
        Object draft=raw.get("draft"),preview=raw.get("preview");if(!(draft instanceof String)||!(preview instanceof String)||((String)draft).length()>100000||((String)preview).length()>100000)throw new IllegalArgumentException();
        double scroll=raw.getDouble("scroll"),ratio=raw.getDouble("ratio");if(!Double.isFinite(scroll)||scroll<0||scroll>100000000||!Double.isFinite(ratio)||ratio<0||ratio>1)throw new IllegalArgumentException();
        return new JSONObject().put("url",EnvironmentUrls.recoverable(e.url)).put("draft",draft).put("preview",preview).put("scroll",scroll).put("ratio",ratio).put("capturedAt",System.currentTimeMillis());
    }
    void restore(EnvironmentBrowserActivity.Entry e,JSONObject local,JSONObject live)throws Exception{
        e.restoringDraft=local.getString("draft");send(e,new JSONObject().put("type","restore").put("draft",e.restoringDraft).put("expectedDraft",live.getString("draft")).put("scroll",local.optDouble("scroll",0)));
    }
    void request(EnvironmentBrowserActivity.Entry e){try{if(e.port!=null)send(e,new JSONObject().put("type","capture"));}catch(Exception ignored){}}
    private void send(EnvironmentBrowserActivity.Entry e,JSONObject value)throws Exception{if(e.port!=null)value.put("token",e.token).put("epoch",e.epoch);if(e.port!=null)e.port.postMessage(value);}
}
