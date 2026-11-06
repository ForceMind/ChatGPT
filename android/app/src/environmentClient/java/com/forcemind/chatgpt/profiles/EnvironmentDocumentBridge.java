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
        void localActivation(EnvironmentBrowserActivity.Entry entry,JSONObject value)throws Exception;
        void localProof(EnvironmentBrowserActivity.Entry entry,JSONObject value)throws Exception;
        void localSourceInvalidated(EnvironmentBrowserActivity.Entry entry);
    }
    interface CheckpointCallback{void complete(JSONObject snapshot,String error);}
    private static final class Checkpoint{
        final EnvironmentBrowserActivity.Entry entry;final WebExtension.Port port;final String request,url,token,document;final long epoch;final CheckpointCallback callback;Runnable timeout;
        Checkpoint(EnvironmentBrowserActivity.Entry e,String request,CheckpointCallback callback){entry=e;port=e.port;this.request=request;url=e.url;token=e.token;document=e.documentId;epoch=e.epoch;this.callback=callback;}
    }
    private final java.util.Map<String,Checkpoint> checkpoints=new java.util.HashMap<>();
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
                    @Override public void onDisconnect(WebExtension.Port source){if(e.port==source){Host owner=target.get();if(owner!=null)owner.localSourceInvalidated(e);e.port=null;e.token="";cancelCheckpoint(e,null);}}
                });
                authenticate(e,port,e.token,e.epoch,0);
            }
        },"studioEnvironmentRecovery");
    }
    private void authenticate(EnvironmentBrowserActivity.Entry e,WebExtension.Port port,String token,long epoch,int attempt){
        if(e.closed||e.port!=port||!e.token.equals(token)||e.epoch!=epoch||e.documentId!=null)return;
        try{send(e,new JSONObject().put("type","auth").put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId).put("localDiagnostics",BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST));}catch(Exception error){invalidate(e);return;}
        if(attempt<20)ui.postDelayed(()->authenticate(e,port,token,epoch,attempt+1),250);
    }
    void invalidate(EnvironmentBrowserActivity.Entry e){Host owner=target.get();if(owner!=null)owner.localSourceInvalidated(e);cancelCheckpoint(e,null);if(e.restoringDocument!=null)e.pendingRestore=e.restoringDocument;e.restoringDocument=null;e.restoringDraft=null;e.restoreRequest=null;e.epoch++;e.sequence=0;e.token="";e.documentId=null;if(e.port!=null){WebExtension.Port old=e.port;e.port=null;old.disconnect();}e.liveDocument=null;}
    private void message(EnvironmentBrowserActivity.Entry e,WebExtension.Port source,Object raw){
        Host host=target.get();if(host==null)return;
        try{
            if(!(raw instanceof JSONObject)||e.port!=source||e.closed||!host.owns(e)||!source.sender.isTopLevel()||source.sender.session!=e.session||!e.url.equals(source.sender.url))return;
            JSONObject value=(JSONObject)raw;
            if(value.toString().length()>240000||!e.url.equals(value.getString("url"))||!e.accountId.equals(value.getString("accountId"))||!e.storageId.equals(value.getString("storageId"))||!e.tabId.equals(value.getString("tabId"))||!e.token.equals(value.getString("token"))||e.epoch!=value.getLong("epoch"))return;
            Object rawSequence=value.get("sequence");if(!(rawSequence instanceof Number)||((Number)rawSequence).doubleValue()!=((Number)rawSequence).longValue())return;long sequence=((Number)rawSequence).longValue();if(sequence<=e.sequence)return;e.sequence=sequence;
            String document=value.getString("document");UUID.fromString(document);if(e.documentId!=null&&!e.documentId.equals(document))return;e.documentId=document;
            if("local-diagnostic".equals(value.optString("type"))){
                if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST){String stage=value.optString("stage");if(java.util.Arrays.asList("click","proof").contains(stage))EnvironmentLocalExports.diagnostic("document-"+stage+" active="+Boolean.TRUE.equals(value.opt("active"))+" eligible="+Boolean.TRUE.equals(value.opt("eligible"))+" trusted="+Boolean.TRUE.equals(value.opt("trusted"))+" anchored="+Boolean.TRUE.equals(value.opt("anchored")));}
                return;
            }
            String type=value.getString("type");if("local-activation".equals(type)){host.localActivation(e,value);return;}if("local-export-proof".equals(type)){host.localProof(e,value);return;}if("ineligible".equals(type)){host.localSourceInvalidated(e);cancelCheckpoint(e,null);e.sensitive=true;e.pendingRestore=null;e.restoringDraft=null;e.restoringDocument=null;e.restoreRequest=null;return;}
            if(java.util.Arrays.asList("restore-unavailable","conflict","restored").contains(type)&&(e.restoreRequest==null||!e.restoreRequest.equals(value.optString("request"))))return;
            if("restore-unavailable".equals(type)){e.conflictLocal=e.restoringDocument;e.restoringDocument=null;e.restoringDraft=null;e.restoreRequest=null;host.notice("页面暂不能完成恢复，本地草稿已保留，请从恢复草稿重试");return;}
            // The departing document's pagehide checkpoint must not consume the target
            // that was saved for its replacement during a same-URL refresh.
            if(e.resumeAfterDocument!=null){if(e.resumeAfterDocument.equals(document))return;e.resumeAfterDocument=null;}
            JSONObject snapshot=normalize(e,value.getJSONObject("snapshot"));e.liveDocument=snapshot;
            if("checkpoint".equals(type)&&value.has("request")){
                Checkpoint pending=checkpoints.get(e.tabId);
                if(pending==null||!pending.request.equals(value.getString("request"))||pending.entry!=e||pending.port!=source||pending.epoch!=e.epoch||!pending.url.equals(e.url)||!pending.token.equals(e.token)||!pending.document.equals(e.documentId))return;
                Object busy=value.opt("busy");if(!(busy instanceof Boolean)||(Boolean)busy){finishCheckpoint(pending,null,"网页正在生成或处理附件，请完成后再操作");return;}
                if(e.sensitive||e.pendingRestore!=null||e.conflictLocal!=null||e.restoringDraft!=null){finishCheckpoint(pending,null,"请先处理草稿恢复");return;}
                finishCheckpoint(pending,snapshot,null);return;
            }
            if("conflict".equals(type)){if(e.restoringDocument!=null)e.pendingRestore=e.restoringDocument;e.restoringDocument=null;e.restoringDraft=null;e.restoreRequest=null;host.conflict(e,snapshot);return;}
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
            e.restoringDraft=null;e.restoringDocument=null;e.restoreRequest=null;host.document(e,snapshot);
        }catch(Exception rejected){/* Invalid or stale document messages never write a record. */}
    }
    private JSONObject normalize(EnvironmentBrowserActivity.Entry e,JSONObject raw)throws Exception{
        if(!e.url.equals(raw.getString("url"))||EnvironmentUrls.recoverable(e.url)==null)throw new IllegalArgumentException();
        Object draft=raw.get("draft"),preview=raw.get("preview");if(!(draft instanceof String)||!(preview instanceof String)||((String)draft).length()>100000||((String)preview).length()>100000)throw new IllegalArgumentException();
        double scroll=raw.getDouble("scroll"),ratio=raw.getDouble("ratio");if(!Double.isFinite(scroll)||scroll<0||scroll>100000000||!Double.isFinite(ratio)||ratio<0||ratio>1)throw new IllegalArgumentException();
        return new JSONObject().put("url",EnvironmentUrls.recoverable(e.url)).put("draft",draft).put("preview",preview).put("scroll",scroll).put("ratio",ratio).put("capturedAt",System.currentTimeMillis());
    }
    void restore(EnvironmentBrowserActivity.Entry e,JSONObject local,JSONObject live)throws Exception{
        e.restoringDraft=local.getString("draft");e.restoringDocument=new JSONObject(local.toString());e.restoreRequest=UUID.randomUUID().toString();send(e,new JSONObject().put("type","restore").put("request",e.restoreRequest).put("draft",e.restoringDraft).put("expectedDraft",live.getString("draft")).put("scroll",local.optDouble("scroll",0)));
    }
    void cancelRestore(EnvironmentBrowserActivity.Entry e){try{send(e,new JSONObject().put("type","cancel-restore"));}catch(Exception ignored){}e.restoringDraft=null;e.restoringDocument=null;e.restoreRequest=null;}
    void requestCheckpoint(EnvironmentBrowserActivity.Entry e,String request,CheckpointCallback callback){
        Host host=target.get();
        if(host==null||!host.owns(e)||e.closed||e.sensitive||e.port==null||e.documentId==null||e.liveDocument==null||EnvironmentUrls.recoverable(e.url)==null){callback.complete(null,"页面尚未准备好");return;}
        UUID.fromString(request);cancelCheckpoint(e,null);Checkpoint pending=new Checkpoint(e,request,callback);checkpoints.put(e.tabId,pending);
        pending.timeout=()->finishCheckpoint(pending,null,"草稿保存确认超时");ui.postDelayed(pending.timeout,5000);
        try{send(e,new JSONObject().put("type","capture").put("request",request));}catch(Exception error){finishCheckpoint(pending,null,"无法读取当前草稿");}
    }
    void cancelCheckpoint(EnvironmentBrowserActivity.Entry e,String request){Checkpoint pending=checkpoints.get(e.tabId);if(pending!=null&&(request==null||request.equals(pending.request)))finishCheckpoint(pending,null,"页面操作已取消");}
    private void finishCheckpoint(Checkpoint pending,JSONObject snapshot,String error){
        if(checkpoints.get(pending.entry.tabId)!=pending)return;checkpoints.remove(pending.entry.tabId);if(pending.timeout!=null)ui.removeCallbacks(pending.timeout);pending.callback.complete(snapshot,error);
    }
    void request(EnvironmentBrowserActivity.Entry e){try{if(e.port!=null)send(e,new JSONObject().put("type","capture"));}catch(Exception ignored){}}
    private void send(EnvironmentBrowserActivity.Entry e,JSONObject value)throws Exception{if(e.port!=null)value.put("token",e.token).put("epoch",e.epoch);if(e.port!=null)e.port.postMessage(value);}
}
