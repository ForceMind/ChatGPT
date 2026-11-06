package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.json.*;
import org.mozilla.geckoview.*;

/** Session-scoped UI executor. Central tasks and send fences remain in the main process. */
final class EnvironmentHandoffController {
    static final String CHANNEL="studioEnvironmentHandoff";
    interface Host {
        boolean valid(EnvironmentBrowserActivity.Entry entry);
        GeckoView view(EnvironmentBrowserActivity.Entry entry);
        void command(String action,JSONObject data,BiConsumer<JSONObject,String> callback);
        void changed(JSONObject job);
        void stopped(EnvironmentHandoffController task,String message);
    }
    private static final ExecutorService FILES=Executors.newSingleThreadExecutor();
    private final WeakReference<Host> target;
    private final Context context;
    final EnvironmentBrowserActivity.Entry entry;
    final String id;
    private final int slot;
    // Gecko drops MIME tokens without known filename extensions before appendRawFilter.
    // A legal extension token is preserved verbatim in FilePrompt.mimeTypes.
    private final String capability,prompt,token=WorkspaceState.id(),mimeMarker=".studio-"+WorkspaceState.id();
    private final JSONArray metadata;
    private final List<String> names=new ArrayList<>();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,Consumer<JSONObject>> pending=new HashMap<>();
    private final CancellationSignal copySignal=new CancellationSignal();
    private final List<File> directories=new ArrayList<>();
    private WebExtension.Port port;
    private String document,boundUrl;
    private long epoch,sequence,rpcSequence,deadline;
    private int stable;
    private volatile boolean closed;
    private boolean authenticated,pickerArmed,chooserUsed,filesConfirmed,insertIssued,sendIssued,dispatching,terminalReported;
    private Uri[] granted;
    private GeckoSession.PromptDelegate.FilePrompt activePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> activeResult;
    private volatile InputStream copyInput;
    private String lastAttachmentDiagnostic="";
    private String lastInputDiagnostic="";
    private boolean keyboardHidden;
    private float[] preparedSendPoint;
    private static boolean diagnostics(){return BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST;}
    private static void diagnostic(String stage){if(diagnostics())android.util.Log.i("StudioHandoff",stage);}
    private static String failureClass(Exception error){if(error instanceof InterruptedIOException||error instanceof OperationCanceledException)return "cancelled";if(error instanceof IOException)return "io";if(error instanceof SecurityException)return "security";if(error instanceof IllegalArgumentException)return "argument";if(error instanceof IllegalStateException)return "state";return "other";}

    EnvironmentHandoffController(Context context,Host host,EnvironmentBrowserActivity.Entry entry,JSONObject launch)throws Exception {
        this.context=context.getApplicationContext();target=new WeakReference<>(host);this.entry=entry;
        id=launch.getString("id");capability=launch.getString("capability");prompt=launch.getString("prompt");metadata=launch.getJSONArray("files");
        WorkspaceState.profile(id);WorkspaceState.profile(capability);
        WorkspaceState.Account account=WorkspaceFiles.read(context.getFilesDir()).find(entry.accountId);slot=account.environmentSlot;EnvironmentConfig.validateSlot(slot);
        if(!entry.accountId.equals(launch.getString("accountId"))||!entry.storageId.equals(launch.getString("storageId"))||!entry.tabId.equals(launch.getString("tabId"))||prompt.length()>70000||metadata.length()>20)throw new IOException("任务目标不匹配");
        for(int i=0;i<metadata.length();i++)names.add(metadata.getJSONObject(i).getString("uploadName"));
        host.changed(launch.getJSONObject("job"));deadline=SystemClock.elapsedRealtime()+45000;
        ui.postDelayed(()->{if(!closed&&document==null)fail("未找到可用的目标聊天页面，任务未发送");},45000);
    }
    boolean dispatching(){return dispatching;}
    boolean active(){return !closed;}
    void pageStarted(){if(!closed&&document!=null)fail("目标文档已重新加载；不会自动重发");}
    void locationChanged(String url){if(closed||document==null||boundUrl.equals(url))return;if(!sendIssued||!sameOrigin(boundUrl,url)||EnvironmentUrls.recoverable(url)==null||!Uri.parse(url).getPath().startsWith("/c/"))fail("目标网页已改变，已停止自动发送");}
    void remoteJob(JSONObject job){if(closed||!id.equals(job.optString("id"))||HandoffStore.active(job.optString("status")))return;terminalReported=true;finish(job.optString("message","网页任务已结束"));}
    /** Call only at a slot's cold start; this queue also owns all task file copies. */
    static void cleanColdCache(Context context,int slot) {
        Context application=context.getApplicationContext();
        FILES.execute(()->{try{EnvironmentConfig.validateSlot(slot);File root=new File(application.getCacheDir().getCanonicalFile(),"environment-handoff");if(root.exists()){HandoffFiles.directory(root,false);File directory=new File(root,Integer.toString(slot));if(directory.exists()){HandoffFiles.directory(directory,false);delete(directory);}}}catch(Exception failed){android.util.Log.w("StudioHandoff","Task cache cleanup unavailable");}});
    }
    void attach(WebExtension extension) {
        entry.session.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate(){
            @Override public void onConnect(WebExtension.Port incoming) {
                WebExtension.MessageSender sender=incoming.sender;
                if(closed||!valid()||sender.session!=entry.session||!sender.isTopLevel()||sender.environmentType!=WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT||!EnvironmentRouteController.EXTENSION.equals(sender.webExtension.id)||!entry.url.equals(sender.url)||EnvironmentUrls.recoverable(entry.url)==null){incoming.setDelegate(new WebExtension.PortDelegate(){});return;}
                if(port!=null){incoming.setDelegate(new WebExtension.PortDelegate(){});fail("目标文档已更换，已停止自动发送");return;}
                port=incoming;boundUrl=entry.url;epoch=entry.epoch;
                incoming.setDelegate(new WebExtension.PortDelegate(){
                    @Override public void onPortMessage(Object raw,WebExtension.Port source){receive(source,raw);}
                    @Override public void onDisconnect(WebExtension.Port source){if(source==port){port=null;fail("目标网页连接已中断；不会自动重发");}}
                });
            }
        },CHANNEL);
    }
    private boolean valid() {
        Host host=target.get();if(closed||host==null||entry.closed||!host.valid(entry))return false;
        if(document==null)return true;
        if(!sendIssued)return entry.epoch==epoch&&boundUrl.equals(entry.url);
        // A same-document conversation URL change after the fenced send is expected.
        return EnvironmentUrls.recoverable(entry.url)!=null;
    }
    private void receive(WebExtension.Port source,Object raw) {
        try {
            if(source!=port||!valid()||!(raw instanceof JSONObject))return;
            JSONObject value=(JSONObject)raw;if(value.toString().length()>450000)throw new IOException();
            String reportedUrl=value.getString("url");
            if(!entry.url.equals(reportedUrl)&&(!sendIssued||EnvironmentUrls.recoverable(reportedUrl)==null||!sameOrigin(boundUrl,reportedUrl)))throw new IOException();
            if(document==null) {
                if(!"hello".equals(value.getString("type")))throw new IOException();
                document=value.getString("document");WorkspaceState.profile(document);
                Uri url=Uri.parse(boundUrl);if(!(url.getPath()==null||url.getPath().isEmpty()||"/".equals(url.getPath())))throw new IOException();
                rpc("bound",new JSONObject(),ignored->{
                    try {boolean images=!names.isEmpty();for(int i=0;i<metadata.length();i++)images&=metadata.getJSONObject(i).getString("uploadMime").startsWith("image/");
                        post(new JSONObject().put("type","auth").put("request",WorkspaceState.id()).put("url",boundUrl).put("names",new JSONArray(names)).put("mimeMarker",mimeMarker).put("allImages",images).put("diagnostics",diagnostics()));
                        ui.postDelayed(()->{if(!closed&&!authenticated)fail("目标网页未确认任务授权，任务未发送");},5000);
                    }catch(Exception failed){fail("无法验证目标网页，任务未发送");}
                });return;
            }
            if(!token.equals(value.getString("token"))||epoch!=value.getLong("epoch")||!document.equals(value.getString("document")))throw new IOException();
            Object rawSequence=value.get("sequence");long next=value.getLong("sequence");if(!(rawSequence instanceof Number)||value.getDouble("sequence")!=next||next!=sequence+1)throw new IOException();sequence=next;
            if("ready".equals(value.getString("type"))){if(authenticated)throw new IOException();authenticated=true;ready(value.getJSONObject("value"));return;}
            String request=value.getString("request");Consumer<JSONObject> callback=pending.remove(request);if(callback==null)return;
            if(!"result".equals(value.getString("type"))){if(diagnostics()){String reason=value.optString("reason");diagnostic("page-error reason="+(Arrays.asList("control-hidden","control-covered","existing-content","changed","document","file-input-changed","text-limit","command","page").contains(reason)?reason:"other"));}fail("网页控件不兼容，已停止自动发送");return;}
            callback.accept(value.getJSONObject("value").put("location",reportedUrl));
        }catch(Exception invalid){fail("无法确认目标网页状态，已停止自动发送");}
    }
    private void post(JSONObject value)throws Exception {
        if(!valid()||port==null||document==null)throw new IOException("网页已失效");
        port.postMessage(value.put("token",token).put("epoch",epoch).put("document",document));
    }
    private void page(String action,Consumer<JSONObject> callback) {
        if(!valid()){fail("目标账号、标签或网页已改变");return;}
        String request=WorkspaceState.id();pending.put(request,callback);
        try{post(new JSONObject().put("type","command").put("action",action).put("request",request));}
        catch(Exception invalid){pending.remove(request);fail("网页连接已失效");return;}
        ui.postDelayed(()->{if(pending.remove(request)!=null)fail("网页确认超时，已停止自动发送");},5000);
    }
    private void rpc(String action,JSONObject data,Consumer<JSONObject> callback) {
        Host host=target.get();if(host==null||!valid()){fail("目标环境已关闭");return;}
        try {
            data.put("id",id).put("capability",capability).put("sequence",++rpcSequence).put("document",document).put("epoch",epoch);
            host.command(action,data,(result,error)->ui.post(()->{
                if(closed)return;
                if(error!=null||result==null){fail(error==null?"任务服务连接已中断":error);return;}
                if(!valid()){fail("目标账号、标签或网页已改变");return;}
                try{if(result.has("job"))host.changed(result.getJSONObject("job"));callback.accept(result);}catch(Exception invalid){fail("任务服务返回无效状态");}
            }));
        }catch(Exception invalid){fail("无法记录网页任务，已停止自动发送");}
    }
    private void later(Runnable action){ui.postDelayed(()->{if(!closed){if(valid())action.run();else fail("目标账号、标签或网页已改变");}},300);}
    private boolean timedOut(){return SystemClock.elapsedRealtime()>deadline;}
    private static String normal(String text){return text.replace("\r\n","\n");}
    private static boolean sameOrigin(String first,String second){try{java.net.URI a=java.net.URI.create(first),b=java.net.URI.create(second);return a.getScheme().equals(b.getScheme())&&a.getHost().equals(b.getHost())&&a.getPort()==b.getPort();}catch(Exception invalid){return false;}}
    private boolean filesReady(JSONObject state) {
        if(state.optBoolean("busy")||state.optBoolean("error")||state.optInt("removers")!=names.size())return false;
        JSONArray actual=state.optJSONArray("attachments");for(String name:names){boolean found=false;if(actual!=null)for(int i=0;i<actual.length();i++)if(actual.optString(i).contains(name))found=true;if(!found)return false;}return true;
    }
    private void ready(JSONObject state) {
        if(!state.optBoolean("composer")){if(timedOut())fail("未找到聊天输入框，请先登录");else later(()->page("state",this::ready));return;}
        if(!state.optBoolean("fresh")||!state.optString("text").isEmpty()||state.optInt("messages")!=0||state.optBoolean("generating")||state.optBoolean("error")){fail("目标页面已有内容，为避免覆盖或重复发送已停止");return;}
        if(names.isEmpty()){draft();return;}
        rpc("files",new JSONObject(),result->{
            try{JSONArray values=result.getJSONArray("uris");if(values.length()!=names.size())throw new IOException();granted=new Uri[values.length()];for(int i=0;i<granted.length;i++){Uri uri=Uri.parse(values.getString(i));List<String> parts=uri.getPathSegments();if(!"content".equals(uri.getScheme())||!(context.getPackageName()+".handoff").equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null||parts.size()!=3||!id.equals(parts.get(0))||!metadata.getJSONObject(i).getString("stageId").equals(parts.get(2)))throw new IOException();granted[i]=uri;}deadline=SystemClock.elapsedRealtime()+9000;upload();}
            catch(Exception invalid){fail("原生附件授权无效，任务未发送");}
        });
    }
    private void upload() {
        if(timedOut()){fail("网页未提供可用的文件选择控件，任务未发送");return;}
        page("upload",value->{try{
            if(value.optBoolean("wait")){JSONObject menu=value.optJSONObject("menu");if(menu!=null)tap(menu);later(this::upload);return;}
            pickerArmed=true;tap(value);deadline=SystemClock.elapsedRealtime()+90000;attachments();
        }catch(Exception invalid){fail("无法操作网页文件选择器，任务未发送");}});
    }
    GeckoResult<GeckoSession.PromptDelegate.PromptResponse> chooser(GeckoSession session,GeckoSession.PromptDelegate.FilePrompt selected) {
        if(session!=entry.session)return null;
        boolean validNow=valid(),marker=selected.mimeTypes!=null&&Arrays.asList(selected.mimeTypes).contains(mimeMarker);
        if(diagnostics())diagnostic("chooser valid="+validNow+" armed="+pickerArmed+" used="+chooserUsed+" pending="+(activeResult!=null)+" mode="+selected.type+" marker="+marker);
        if(!validNow||!pickerArmed||chooserUsed||activeResult!=null)return GeckoResult.fromValue(selected.dismiss());
        if(selected.type!=GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE||!marker){GeckoSession.PromptDelegate.PromptResponse response=selected.dismiss();fail("网页文件选择器未能匹配本次附件授权，任务未发送");return GeckoResult.fromValue(response);}
        chooserUsed=true;pickerArmed=false;activePrompt=selected;activeResult=new GeckoResult<>();GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result=activeResult;
        FILES.execute(()->{
            try {
                if(diagnostics())diagnostic("copy-start files="+granted.length);
                Uri[] files=copyFiles();ui.post(()->{
                    boolean current=valid();if(diagnostics())diagnostic("confirm-ready valid="+current+" pending="+(activeResult==result)+" complete="+selected.isComplete()+" files="+files.length);
                    if(activeResult!=result||!current||selected.isComplete()){dismissPrompt();fail("目标网页或附件授权已失效，任务未发送");return;}
                    try{GeckoSession.PromptDelegate.PromptResponse response=selected.confirm(context,files);activeResult=null;activePrompt=null;filesConfirmed=true;result.complete(response);if(diagnostics())diagnostic("confirm success=true");}catch(Exception invalid){if(diagnostics())diagnostic("confirm success=false class="+failureClass(invalid));dismissPrompt();fail("无法交付原生附件，任务未发送");}
                });
            }catch(Exception failed){if(diagnostics())diagnostic("copy-failed class="+failureClass(failed));ui.post(()->{dismissPrompt();fail("附件读取或校验失败，任务未发送");});}
        });
        return result;
    }
    private Uri[] copyFiles()throws Exception {
        checkCopy();File base=new File(context.getCacheDir().getCanonicalFile(),"environment-handoff");if(!base.exists()&&!base.mkdir())throw new IOException();HandoffFiles.directory(base,false);
        File root=new File(base,Integer.toString(slot));if(!root.exists()&&!root.mkdir())throw new IOException();HandoffFiles.directory(root,false);
        File folder=new File(root,WorkspaceState.id());if(!folder.mkdir())throw new IOException();HandoffFiles.directory(folder,false);synchronized(directories){directories.add(folder);}
        Uri[] result=new Uri[granted.length];long total=0;
        for(int i=0;i<granted.length;i++) {
            checkCopy();JSONObject expected=metadata.getJSONObject(i);String name=expected.getString("uploadName");if(!AssetLedger.filename(name).equals(name)||name.equals(".")||name.equals(".."))throw new IOException();
            File file=new File(folder,name);MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(AssetFileDescriptor descriptor=context.getContentResolver().openAssetFileDescriptor(granted[i],"r",copySignal)) {
                if(descriptor==null)throw new IOException();
                try(InputStream input=descriptor.createInputStream();FileChannel output=FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    copyInput=input;byte[] buffer=new byte[65536];int size;
                    while((size=input.read(buffer))!=-1){checkCopy();count+=size;total+=size;if(count>expected.getLong("size")||count>HandoffRequest.MAX_FILE||total>HandoffRequest.MAX_TOTAL)throw new IOException();digest.update(buffer,0,size);ByteBuffer chunk=ByteBuffer.wrap(buffer,0,size);while(chunk.hasRemaining())output.write(chunk);}output.force(true);
                }finally{copyInput=null;}
            }
            StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));
            if(diagnostics())diagnostic("copy-file index="+i+" lengthMatch="+(count==expected.getLong("size"))+" hashMatch="+hash.toString().equals(expected.getString("sha256")));
            if(count!=expected.getLong("size")||!hash.toString().equals(expected.getString("sha256")))throw new IOException();checkCopy();result[i]=Uri.fromFile(file);
        }
        checkCopy();if(diagnostics())diagnostic("copy-complete files="+result.length);return result;
    }
    private void checkCopy()throws Exception {
        if(closed||copySignal.isCanceled()||entry.closed||entry.epoch!=epoch)throw new InterruptedIOException("附件任务已取消");
        WorkspaceState state=WorkspaceFiles.read(context.getFilesDir());WorkspaceState.Account account=state.find(entry.accountId);
        if(!account.isEnvironment()||!account.storageId.equals(entry.storageId)||!account.activeTab.equals(entry.tabId)||!state.activeAccount.equals(entry.accountId))throw new InterruptedIOException("附件目标已改变");
    }
    private void attachments() {
        if(timedOut()){fail("未能确认全部附件上传完成，任务未发送");return;}
        page("state",state->{
            if(diagnostics()){
                JSONObject page=state.optJSONObject("diagnostics");JSONArray attachments=state.optJSONArray("attachments");
                String value="attachments chosen="+chooserUsed+" confirmed="+filesConfirmed+" prompt="+(activeResult!=null)+" removers="+state.optInt("removers")+" labels="+(attachments==null?0:attachments.length())+" namesMatch="+filesReady(state)+" busy="+state.optBoolean("busy")+" error="+state.optBoolean("error")+" selectedFiles="+(page==null?-1:page.optInt("selectedFiles",-1))+" pageErrors="+(page==null?-1:page.optInt("pageErrors",-1))+" rejections="+(page==null?-1:page.optInt("rejections",-1));
                if(!value.equals(lastAttachmentDiagnostic)){lastAttachmentDiagnostic=value;diagnostic(value);}
            }
            if(state.optBoolean("error")){fail("网页提示附件上传失败，任务未发送");return;}stable=chooserUsed&&activeResult==null&&filesReady(state)?stable+1:0;if(stable>=3)page("restore",ignored->draft());else later(this::attachments);
        });
    }
    private void draft(){rpc("drafting",new JSONObject(),ignored->page("focus",value->{deadline=SystemClock.elapsedRealtime()+5000;later(this::insert);}));}
    private void insert() {
        if(timedOut()){fail("输入框尚未就绪，任务未发送");return;}
        page("state",state->{
            if(!state.optBoolean("fresh")||!state.optBoolean("focused")||!state.optString("text").isEmpty()||state.optInt("messages")!=0||state.optBoolean("generating")||!filesReady(state)){fail("目标输入框已改变，任务未发送");return;}
            Host host=target.get();GeckoView view=host==null?null:host.view(entry);if(view==null||!view.isShown()||!view.hasWindowFocus()){fail("目标网页当前不可操作，任务未发送");return;}
            view.requestFocus();EditorInfo info=new EditorInfo();InputConnection connection=view.onCreateInputConnection(info);
            if(connection==null||(info.inputType&InputType.TYPE_MASK_CLASS)!=InputType.TYPE_CLASS_TEXT){later(this::insert);return;}
            if(insertIssued){fail("完整处理要求已尝试输入，不会重复输入");return;}insertIssued=true;
            Handler supplied=connection.getHandler(),input=supplied==null?ui:supplied;long inputDeadline=deadline;
            java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
            if(diagnostics())diagnostic("input-dispatch handlerPresent="+(supplied!=null)+" handlerUi="+(input.getLooper()==Looper.getMainLooper()));
            // Gecko 158 ignores editable operations on the wrong IC thread, while Android
            // BaseInputConnection may still return true. Always enqueue on its own Handler:
            // getHandler also queues Gecko's thread-switch barrier before this operation.
            ui.postDelayed(()->{if(finished.compareAndSet(false,true)&&!closed)fail("输入框尚未就绪，任务未发送");},Math.max(1,inputDeadline-SystemClock.elapsedRealtime()));
            boolean posted=input.post(()->{
                boolean accepted=false;String failure="none";
                try{if(closed||SystemClock.elapsedRealtime()>=inputDeadline)throw new InterruptedIOException();checkCopy();if(SystemClock.elapsedRealtime()>=inputDeadline)throw new InterruptedIOException();accepted=connection.commitText(prompt,1);}
                catch(Exception invalid){failure=failureClass(invalid);}
                boolean committed=accepted;String category=failure;
                ui.post(()->{
                    if(!finished.compareAndSet(false,true)||closed)return;
                    if(diagnostics())diagnostic("input-commit accepted="+committed+" category="+category);
                    if(!valid()){fail("目标账号、标签或网页已改变");return;}
                    if(!committed){fail("无法确认完整处理要求已输入，任务未发送");return;}
                    deadline=SystemClock.elapsedRealtime()+15000;verifyDraft();
                });
            });
            if(!posted&&finished.compareAndSet(false,true))fail("无法确认完整处理要求已输入，任务未发送");
        });
    }
    private void verifyDraft() {
        if(timedOut()){fail("网页未完整接收处理要求，任务未发送");return;}
        page("state",state->{
            if(diagnostics()){
                JSONObject page=state.optJSONObject("diagnostics");String value="input-readback match="+normal(prompt).equals(normal(state.optString("text")))+" empty="+state.optString("text").isEmpty()+" focused="+state.optBoolean("focused")+" send="+state.optBoolean("send")+" events="+(page==null?-1:page.optInt("inputEvents",-1))+" trusted="+(page==null?-1:page.optInt("trustedInputEvents",-1));
                if(!value.equals(lastInputDiagnostic)){lastInputDiagnostic=value;diagnostic(value);}
            }
            if(state.optBoolean("error")){fail("网页提示附件或输入错误，任务未发送");return;}
            if(normal(prompt).equals(normal(state.optString("text")))&&state.optBoolean("send")&&filesReady(state))prepareSend();else later(this::verifyDraft);
        });
    }
    private void prepareSend() {
        // Use the original draft-verification deadline. Waiting for layout never retries input/send.
        if(timedOut()){fail("发送按钮尚不可操作，任务未发送");return;}
        Host host=target.get();GeckoView view=host==null?null:host.view(entry);
        if(view==null){fail("目标网页当前不可操作，任务未发送");return;}
        if(!keyboardHidden){keyboardHidden=true;InputMethodManager keyboard=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(view.getWindowToken(),0);}
        page("send-point",value->{
            JSONObject state=value.optJSONObject("state");if(state==null||!normal(prompt).equals(normal(state.optString("text")))||!filesReady(state)||state.optInt("messages")!=0){fail("发送前网页内容已改变；不会自动重发");return;}
            float[] point=value.optBoolean("wait")?null:sendCoordinates(value);
            if(diagnostics())diagnostic("send-prepare wait="+value.optBoolean("wait")+" inView="+(point!=null)+" stable="+samePoint(preparedSendPoint,point));
            if(point==null){preparedSendPoint=null;later(this::prepareSend);return;}
            if(!samePoint(preparedSendPoint,point)){preparedSendPoint=point;later(this::prepareSend);return;}
            beforeSend();
        });
    }
    private static boolean samePoint(float[] first,float[] second){if(first==null||second==null)return false;for(int i=0;i<first.length;i++)if(Math.abs(first[i]-second[i])>1)return false;return true;}
    private float[] sendCoordinates(JSONObject geometry){
        try{
            if(!valid())return null;Host host=target.get();GeckoView view=host==null?null:host.view(entry);if(view==null||!view.isShown()||view.getWindowToken()==null)return null;
            double x=geometry.getDouble("x"),y=geometry.getDouble("y");if(!Double.isFinite(x)||!Double.isFinite(y))return null;
            RectF client=new RectF();entry.session.getClientBounds(client);if(!Float.isFinite(client.width())||!Float.isFinite(client.height())||client.width()<=0||client.height()<=0||x<client.left||y<client.top||x>=client.right||y>=client.bottom)return null;
            Matrix transform=new Matrix();entry.session.getClientToSurfaceMatrix(transform);float[] point={(float)x,(float)y};transform.mapPoints(point);
            if(!Float.isFinite(point[0])||!Float.isFinite(point[1])||point[0]<0||point[1]<0||point[0]>=view.getWidth()||point[1]>=view.getHeight())return null;
            return new float[]{point[0],point[1],view.getWidth(),view.getHeight()};
        }catch(Exception invalid){return null;}
    }
    private void beforeSend() {
        try {rpc("before-send",new JSONObject().put("promptSha256",HandoffRequest.hash(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8))),ignored->page("send-point",value->{
            JSONObject state=value.optJSONObject("state");float[] point=value.optBoolean("wait")?null:sendCoordinates(value);if(diagnostics())diagnostic("send-final inView="+(point!=null)+" stable="+samePoint(preparedSendPoint,point));
            if(state==null||!normal(prompt).equals(normal(state.optString("text")))||!filesReady(state)||state.optInt("messages")!=0||!samePoint(preparedSendPoint,point)){fail("发送前网页内容已改变；不会自动重发");return;}
            if(sendIssued){fail("本任务已尝试发送，不会重复发送");return;}sendIssued=true;
            try{tapSend(value);deadline=SystemClock.elapsedRealtime()+25000;acknowledge();}catch(Exception invalid){fail("发送结果待确认，请检查目标对话；不会自动重发");}
        }));}catch(Exception invalid){fail("发送许可无法记录，任务未发送");}
    }
    private void acknowledge() {
        if(timedOut()){fail("无法确认网页收到完整消息；不会自动重发");return;}
        page("ack-state",state->{
            if(state.optInt("messages")==1&&normal(prompt).equals(normal(state.optString("last")))&&state.optString("text").isEmpty()&&entry.url.equals(state.optString("location"))) {
                try{rpc("submitted",new JSONObject().put("url",entry.url),ignored->finish("已发送；模型结果请在目标对话中查看"));}catch(Exception invalid){fail("发送结果待确认；不会自动重发");}
            }else later(this::acknowledge);
        });
    }
    private void tap(JSONObject geometry)throws Exception {
        if(!valid())throw new IOException();Host host=target.get();GeckoView view=host==null?null:host.view(entry);
        if(view==null||!view.isShown()||view.getWindowToken()==null)throw new IOException();double width=geometry.getDouble("viewportWidth"),height=geometry.getDouble("viewportHeight");
        if(!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0)throw new IOException();
        float x=(float)(geometry.getDouble("x")*view.getWidth()/width),y=(float)(geometry.getDouble("y")*view.getHeight()/height);
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=view.getWidth()||y>=view.getHeight())throw new IOException();
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,x,y,0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);dispatching=true;
        try{view.requestFocus();if(!view.dispatchTouchEvent(down)||!view.dispatchTouchEvent(up))throw new IOException();}finally{dispatching=false;down.recycle();up.recycle();}
    }
    private void tapSend(JSONObject geometry)throws Exception {
        float[] point=sendCoordinates(geometry);if(!samePoint(preparedSendPoint,point))throw new IOException();Host host=target.get();GeckoView view=host==null?null:host.view(entry);if(view==null)throw new IOException();
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0),up=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,point[0],point[1],0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);dispatching=true;
        try{view.requestFocus();boolean accepted=view.dispatchTouchEvent(down)&&view.dispatchTouchEvent(up);if(diagnostics())diagnostic("send-tap accepted="+accepted);if(!accepted)throw new IOException();}finally{dispatching=false;down.recycle();up.recycle();}
    }
    void invalidated(String reason){if(!closed)fail(reason);}
    void cancel(){stop("cancelled","已停止自动发送；请检查目标网页中已有的附件和草稿");}
    private void fail(String message){stop("failed",message);}
    private void stop(String action,String message) {
        if(closed)return;
        Host host=target.get();if(!terminalReported&&host!=null) {
            terminalReported=true;try{host.command(action,new JSONObject().put("id",id).put("capability",capability).put("sequence",++rpcSequence).put("document",document).put("epoch",epoch).put("reason",message),(result,error)->{if(result!=null&&result.optJSONObject("job")!=null)ui.post(()->host.changed(result.optJSONObject("job")));});}catch(Exception ignored){/* Broker disconnect owns durable termination. */}
        }
        finish(message);
    }
    private void dismissPrompt(){GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result=activeResult;GeckoSession.PromptDelegate.FilePrompt selected=activePrompt;activeResult=null;activePrompt=null;if(result!=null&&selected!=null&&!selected.isComplete())result.complete(selected.dismiss());}
    private void finish(String message) {
        if(closed)return;closed=true;pending.clear();copySignal.cancel();InputStream input=copyInput;if(input!=null)try{input.close();}catch(IOException ignored){}dismissPrompt();
        if(port!=null&&document!=null)try{port.postMessage(new JSONObject().put("type","command").put("action","cleanup").put("request",WorkspaceState.id()).put("token",token).put("epoch",epoch).put("document",document));}catch(Exception ignored){}
        port=null;
        // Gecko may still be reading confirmed files. Keep successful cache files with this
        // Entry until its real close; cancellation before confirm can delete immediately.
        synchronized(directories){if(filesConfirmed){entry.pickerDirectories.addAll(directories);directories.clear();}}
        FILES.execute(()->{synchronized(directories){for(File directory:directories)delete(directory);directories.clear();}});
        Host host=target.get();if(host!=null)host.stopped(this,message);
    }
    private static void delete(File directory){if(Files.isSymbolicLink(directory.toPath()))return;File[] files=directory.listFiles();if(files!=null)for(File file:files){if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath()))delete(file);else try{Files.deleteIfExists(file.toPath());}catch(IOException ignored){}}try{Files.deleteIfExists(directory.toPath());}catch(IOException ignored){}}
}
