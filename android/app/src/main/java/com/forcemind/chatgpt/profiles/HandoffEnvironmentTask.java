package com.forcemind.chatgpt.profiles;

import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Main-process authority for one Gecko task. The slot never opens the central task store. */
final class HandoffEnvironmentTask {
    /** Native-owned user explanations only; remote exceptions and page text never enter the journal. */
    private static final java.util.Set<String> FAILURE_REASONS=java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
        "未能确认全部附件上传完成，任务未发送","网页提示附件上传失败，任务未发送","附件读取或校验失败，任务未发送",
        "原生附件授权无效，任务未发送","网页未提供可用的文件选择控件，任务未发送","无法操作网页文件选择器，任务未发送",
        "网页文件选择器未能匹配本次附件授权，任务未发送","无法交付原生附件，任务未发送","目标网页或附件授权已失效，任务未发送",
        "未找到聊天输入框，请先登录","目标页面已有内容，为避免覆盖或重复发送已停止","输入框尚未就绪，任务未发送",
        "目标输入框已改变，任务未发送","目标网页当前不可操作，任务未发送","完整处理要求已尝试输入，不会重复输入",
        "无法确认完整处理要求已输入，任务未发送","网页未完整接收处理要求，任务未发送","网页提示附件或输入错误，任务未发送",
        "发送前网页内容已改变；不会自动重发","本任务已尝试发送，不会重复发送","发送结果待确认，请检查目标对话；不会自动重发",
        "发送许可无法记录，任务未发送","无法确认网页收到完整消息；不会自动重发","发送结果待确认；不会自动重发",
        "网页控件不兼容，已停止自动发送","无法确认目标网页状态，已停止自动发送","目标网页未确认任务授权，任务未发送",
        "未找到可用的目标聊天页面，任务未发送","目标文档已重新加载；不会自动重发","目标网页已改变，已停止自动发送",
        "目标网页连接已中断；不会自动重发","网页确认超时，已停止自动发送","目标账号、标签或网页已改变"
    )));
    interface Listener {
        void changed(JSONObject job);
        void stopped(String reason);
    }
    final String id,capability=WorkspaceState.id(),account,storage,tab;
    final int slot;
    private final HandoffRuntime runtime;
    private final JSONObject payload;
    private final Listener listener;
    private final AtomicBoolean control;
    private final long deadline=SystemClock.elapsedRealtime()+600000;
    private JSONObject job;
    private String document;
    private long epoch,sequence;
    private boolean filesGranted;
    private volatile boolean terminal;

    HandoffEnvironmentTask(HandoffRuntime runtime,String id,String launch,int slot,Listener listener,AtomicBoolean control)throws Exception {
        this.runtime=runtime;this.id=id;this.slot=slot;this.listener=listener;this.control=control;
        payload=runtime.store.payload(id);JSONObject request=payload.getJSONObject("request");
        account=request.getString("targetAccountId");storage=request.getString("targetStorageId");
        // Only this authenticated, one-shot claim may terminate and clean this launch.
        // A bad/replayed launch token never reaches the cleanup below.
        job=runtime.store.claim(id,launch);
        final String[] created={null};
        try {
            if(!BrowserMemory.get(runtime.context).network().ready())throw new IOException("网络尚未就绪，任务未发送");
            HandoffRequest.accounts(runtime.context.getFilesDir(),request);
            WorkspaceState.Account destination=WorkspaceFiles.read(runtime.context.getFilesDir()).find(account);
            if(!BuildConfig.ENVIRONMENT_CLIENT||!destination.isEnvironment()||destination.environmentSlot!=slot)throw new IOException("目标环境不匹配");
            if(payload.getString("prompt").length()>70000)throw new IOException("完整处理要求超过网页确认上限，任务未发送");
            new StateStore(runtime.context.getFilesDir()).update(state->{
                WorkspaceState.Account target=state.find(account);
                if(!target.isEnvironment()||target.environmentSlot!=slot||!target.storageId.equals(storage))throw new IllegalStateException("目标登录空间已改变");
                WorkspaceState.Tab fresh=state.addTab(target);
                // CI uses the same owned origin, with a truly fresh conversation path.
                if(BuildConfig.ENVIRONMENT_CLIENT&&BuildConfig.ENVIRONMENT_TEST&&EnvironmentPolicy.testUrl(BuildConfig.ENVIRONMENT_HOME_URL)) {
                    Uri home=Uri.parse(BuildConfig.ENVIRONMENT_HOME_URL);
                    fresh.url=home.buildUpon().encodedPath("/").clearQuery().fragment(null).build().toString();
                }
                state.activeAccount=account;created[0]=fresh.id;
            });
            tab=created[0];runtime.store.bindTab(id,tab);job=runtime.store.get(id);
        } catch(Exception failed) {
            HandoffUploads.revoke(id);
            try{runtime.store.failure(id,"无法绑定目标新标签页，任务未发送",false);}catch(Exception journal){failed.addSuppressed(journal);}
            try{HandoffFiles.cleanup(runtime.context.getFilesDir(),id);}catch(Exception cleanup){failed.addSuppressed(cleanup);}
            throw failed;
        }
    }
    JSONObject launch()throws Exception {
        return new JSONObject().put("id",id).put("capability",capability).put("accountId",account).put("storageId",storage).put("tabId",tab)
                .put("prompt",payload.getString("prompt")).put("files",new JSONArray(job.getJSONArray("files").toString())).put("job",new JSONObject(job.toString()));
    }
    private boolean owns() {
        try {
            HandoffRequest.accounts(runtime.context.getFilesDir(),payload.getJSONObject("request"));
            WorkspaceState state=WorkspaceFiles.read(runtime.context.getFilesDir());WorkspaceState.Account target=state.find(account);
            if(!target.isEnvironment()||target.environmentSlot!=slot||!target.storageId.equals(storage)||!target.activeTab.equals(tab)||!state.activeAccount.equals(account))return false;
            for(WorkspaceState.Tab candidate:target.tabs)if(candidate.id.equals(tab))return true;
        }catch(Exception invalid){return false;}
        return false;
    }
    boolean live() { return !terminal&&control.get()&&SystemClock.elapsedRealtime()<deadline&&owns(); }
    private void require(JSONObject data)throws Exception {
        if(!live()||!capability.equals(data.getString("capability"))||!id.equals(data.getString("id")))throw new IOException("任务授权已失效");
        Object raw=data.get("sequence");long next=data.getLong("sequence");
        if(!(raw instanceof Number)||data.getDouble("sequence")!=next||next!=sequence+1)throw new IOException("任务回执次序无效");
        if(document!=null&&(!document.equals(data.getString("document"))||epoch!=data.getLong("epoch")))throw new IOException("目标网页已改变");
        sequence=next;
    }
    /** Called only by Runtime's serialized IO queue, after broker slot/client validation. */
    JSONObject command(String action,JSONObject data)throws Exception {
        require(data);
        switch(action) {
            case "bound":
                if(document!=null||!job.getString("status").equals("opening"))throw new IOException("网页已绑定");
                document=data.getString("document");WorkspaceState.profile(document);epoch=data.getLong("epoch");
                if(epoch<0)throw new IOException("网页代次无效");
                break;
            case "files":
                if(document==null||filesGranted||job.getJSONArray("files").length()==0||!job.getString("status").equals("opening"))throw new IOException("附件授权不能重复领取");
                verifySource();job=runtime.store.transition(id,"uploading","正在上传附件，请等待网页确认");
                Uri[] uris=HandoffUploads.grant(runtime.context,job,payload,this::live);filesGranted=true;JSONArray values=new JSONArray();for(Uri uri:uris)values.put(uri.toString());
                changed();return result().put("uris",values);
            case "drafting":
                if(document==null||job.getJSONArray("files").length()>0&&!filesGranted)throw new IOException("附件尚未确认");
                job=runtime.store.transition(id,"drafting","附件已确认，正在准备完整处理要求");changed();break;
            case "before-send":
                if(document==null||!payloadHash().equals(data.getString("promptSha256")))throw new IOException("完整处理要求不匹配");
                verifySource();if(!live())throw new IOException("任务已取消");
                runtime.store.beginSend(id);job=runtime.store.get(id);changed();break;
            case "submitted":
                if(!job.getString("status").equals("sending"))throw new IOException("任务尚未发送");
                runtime.store.submittedEnvironment(id,data.getString("url"));job=runtime.store.get(id);finish(null,false,false);break;
            case "failed":case "cancelled":
                String reason=data.optString("reason");
                finish("cancelled".equals(action)?"已停止自动发送；请检查目标网页中已有的附件和草稿":FAILURE_REASONS.contains(reason)?reason:"网页任务未能完成；请检查任务记录和目标网页",true,"cancelled".equals(action));break;
            default:throw new IOException("任务命令无效");
        }
        return result();
    }
    private String payloadHash()throws Exception {return HandoffRequest.hash(payload.getString("prompt").getBytes(StandardCharsets.UTF_8));}
    private void verifySource()throws Exception {
        HandoffRequest.accounts(runtime.context.getFilesDir(),payload.getJSONObject("request"));AssetLedger source=new AssetLedger(runtime.context.getFilesDir(),job.getString("sourceAccountId"));
        JSONArray files=job.getJSONArray("files");for(int i=0;i<files.length();i++) {
            JSONObject expected=files.getJSONObject(i),current=source.get(expected.getString("assetId"));
            if(current.getBoolean("trashed")||!"ready".equals(current.getString("status"))||current.getLong("size")!=expected.getLong("size")||!current.getString("sha256").equals(expected.getString("sha256")))throw new IOException("源附件已改变，未发送消息");
        }
    }
    private JSONObject result()throws JSONException {return new JSONObject().put("job",new JSONObject(job.toString()));}
    private void changed()throws JSONException {JSONObject snapshot=new JSONObject(job.toString());runtime.handler.post(()->listener.changed(snapshot));}
    void cancel(String reason)throws Exception {if(!terminal)finish(reason,true,true);}
    private void finish(String reason,boolean failed,boolean cancelled)throws Exception {
        if(terminal)return;
        // Revoke capability and provider reads before deleting any staging bytes.
        terminal=true;control.set(false);HandoffUploads.revoke(id);
        try {
            if(failed)runtime.store.failure(id,reason,cancelled);
            job=runtime.store.get(id);changed();
        } finally {
            try{HandoffFiles.cleanup(runtime.context.getFilesDir(),id);}finally{runtime.release(id);runtime.handler.post(()->listener.stopped(reason));}
        }
    }
}
