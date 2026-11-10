package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import android.content.*;
import android.os.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;

/** Same-UID main-process route subscription. Navigation waits for privileged-extension ACK. */
final class EnvironmentRouteController {
    static final String EXTENSION="studio-environment@local.invalid";
    interface Listener{void route(boolean ready,boolean foreground,String notice);}
    final int slot;final EnvironmentTransferController transfers;final EnvironmentLocalExports localExports;
    private final Context context;private Listener listener;private String lastNotice="正在连接账号环境";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),this::receive));
    private Messenger service;private WebExtension.Port port;private JSONObject desired;
    interface ImportCompletion{void complete(boolean success,String message);}
    private static final ExecutorService FILE_IO=Executors.newSingleThreadExecutor();
    private static final ExecutorService LOCAL_FILE_IO=Executors.newSingleThreadExecutor();
    private final Map<String,ImportCompletion> imports=new HashMap<>();
    private final Map<String,java.util.function.Consumer<JSONObject>> calls=new HashMap<>();
    private final Set<String> localCalls=new HashSet<>();private final Map<String,LocalPipe> localPipes=new HashMap<>();
    interface HandoffListener{void changed(JSONObject job);void lost(String message);}
    private HandoffListener handoffListener;
    private static final class HandoffCall {
        final String action;final java.util.function.BiConsumer<JSONObject,String> callback;
        HandoffCall(String action,java.util.function.BiConsumer<JSONObject,String> callback){this.action=action;this.callback=callback;}
    }
    private final Map<String,HandoffCall> handoffCalls=new HashMap<>();
    private String handoffClaimCall="",handoffCapability="",handoffId="";
    private String routeToken="";private boolean bound,foreground,ready,closed;private long sent=-1,acknowledged=-1;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=new Messenger(binder);send(1,0);send(2,foreground?1:0);}
        public void onServiceDisconnected(ComponentName name){service=null;localBrokerLost();block("网络连接正在恢复");}
        public void onBindingDied(ComponentName name){service=null;localBrokerLost();block("网络连接正在恢复");if(bound){context.unbindService(this);bound=false;}ui.postDelayed(()->{if(!closed)bind();},500);}
        public void onNullBinding(ComponentName name){localBrokerLost();block("网络服务不可用");}
    };
    EnvironmentRouteController(Context context,GeckoRuntime runtime,int slot,Listener listener){
        this.context=context.getApplicationContext();this.slot=slot;this.listener=listener;transfers=new EnvironmentTransferController(context,this);localExports=new EnvironmentLocalExports(context,this);
        ui.post(()->{
            if(closed)return;
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/studio-environment/",EXTENSION).accept(extension->ui.post(()->{
                if(closed)return;
                runtime.getWebExtensionController().setAllowedInPrivateBrowsing(extension,true).accept(allowed->ui.post(()->{
                    if(closed)return;
                    allowed.setMessageDelegate(new WebExtension.MessageDelegate(){@Override public void onConnect(WebExtension.Port incoming){connect(incoming);}},"studioEnvironmentRoute");
                    // Recovery delegate installation and readiness share the route/lifecycle UI queue.
                    extensionReady=allowed;if(readyListener!=null)readyListener.run();
                }),error->ui.post(()->{if(!closed)block("网络保护初始化失败，请重新打开账号");}));
            }),error->ui.post(()->{if(!closed)block("页面组件初始化失败，请重新打开账号");}));
            bind();
        });
    }
    private void bind(){
        Intent intent=new Intent().setClassName(context,"com.forcemind.chatgpt.profiles.EnvironmentBridgeService");
        try{bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);if(!bound)block("网络服务不可用");}catch(RuntimeException error){block("网络服务不可用");}
    }
    WebExtension extensionReady;Runnable readyListener;
    void whenExtensionReady(Runnable callback){readyListener=callback;if(extensionReady!=null&&callback!=null)callback.run();}
    void listener(Listener next){listener=next;listener.route(ready,desired!=null&&desired.optBoolean("foreground"),lastNotice);}
    private void connect(WebExtension.Port incoming){
        WebExtension.MessageSender sender=incoming.sender;
        if(closed||sender.session!=null||sender.environmentType!=WebExtension.MessageSender.ENV_TYPE_EXTENSION||!EXTENSION.equals(sender.webExtension.id)){
            incoming.setDelegate(new WebExtension.PortDelegate(){});incoming.disconnect();return;
        }
        // Private-browsing authorization restarts the extension and may queue its old
        // disconnect before both pending Connect callbacks reach us. Gecko 158's remote
        // disconnect path is not idempotent: let that owner finish the old port teardown.
        // The source!=port checks below already revoke all superseded route messages.
        port=incoming;sent=-1;acknowledged=-1;block("正在应用网络设置");
        incoming.setDelegate(new WebExtension.PortDelegate(){
            public void onPortMessage(Object raw,WebExtension.Port source){
                try{
                    if(source!=port||!(raw instanceof JSONObject)||desired==null||routeToken.isEmpty())return;JSONObject value=(JSONObject)raw;if(transfers.message(value,source))return;
                    if(!"route-applied".equals(value.optString("type"))||value.getLong("generation")!=sent||sent!=desired.getLong("generation")||!routeToken.equals(value.optString("routeToken"))||!desired.optString("instance").equals(value.optString("instance")))return;
                    if(BuildConfig.ENVIRONMENT_TEST)android.util.Log.i("StudioEnvironmentRoute","ack kind="+value.optString("kind")+" port="+value.optInt("port")+" generation="+sent+" implementation="+value.optString("implementation")+" handler="+value.optBoolean("handlerInstalled")+" guard="+value.optBoolean("guardInstalled")+" ok="+value.optBoolean("ok")+" error="+value.optString("error"));
                    boolean needsHandler="direct".equals(desired.getString("kind"))||"proxy".equals(desired.getString("kind"));
                    if(!value.optBoolean("ok")||!"proxy.onRequest".equals(value.optString("implementation"))||!value.optBoolean("guardInstalled")||value.optBoolean("handlerInstalled")!=needsHandler||!desired.getString("kind").equals(value.getString("kind"))||desired.optInt("port")!=value.optInt("port")){block("网络设置未能生效，请重试");return;}
                    acknowledged=sent;ready=!"blocked".equals(desired.getString("kind"));lastNotice=ready?"":"网络暂不可用";listener.route(ready,desired.optBoolean("foreground"),lastNotice);
                }catch(Exception error){block("网络设置确认失败");}
            }
            public void onDisconnect(WebExtension.Port source){if(source==port){port=null;block("网络连接正在恢复");}}
        });apply();
    }
    java.util.function.BiFunction<String, Long, Boolean> updatePrompt;
    private boolean receive(Message message){
        if(message.what==20&&BuildConfig.APP_UPDATE_AUTO){
            Bundle data=message.getData();
            if(!closed&&foreground&&updatePrompt!=null&&updatePrompt.apply(data.getString("name",""),data.getLong("size"))&&service!=null){
                try{
                    Message ack=Message.obtain(null,21);ack.replyTo=inbox;
                    Bundle proof=new Bundle();proof.putLong("code",data.getLong("code"));ack.setData(proof);service.send(ack);
                }catch(RemoteException disconnected){/* Next foreground can prompt again. */}
            }
            return true;
        }
        if(message.what==18){receiveHandoffReply(message.getData());return true;}
        if(message.what==19){receiveHandoffEvent(message.getData());return true;}
        if(message.what==12){try{JSONObject result=new JSONObject(message.getData().getString("payload",""));java.util.function.Consumer<JSONObject> callback=calls.remove(result.optString("call"));if(callback!=null)callback.accept(result);}catch(Exception invalid){block("文件线路确认失败");}return true;}
        if(message.what==13){transfers.expired(message.getData().getString("lease",""));return true;}
        if(message.what==11){String operation=message.getData().getString("operation","");ImportCompletion callback=imports.remove(operation);if(callback!=null)callback.complete(message.getData().getBoolean("success"),message.getData().getString("message","文件保存未完成"));else localExports.brokerEnded(operation,message.getData().getString("message","本地保存已取消"));return true;}
        if(message.what!=10||closed)return false;
        try{
            JSONObject next=new JSONObject(message.getData().getString("state",""));String kind=next.getString("kind");long generation=next.getLong("generation");
            if(!java.util.Arrays.asList("system","direct","proxy","blocked").contains(kind)||generation<0||(kind.equals("proxy")&&(next.getInt("port")<1||next.getInt("port")>65535)))throw new IllegalArgumentException();
            boolean sameInstance=desired!=null&&desired.optString("instance").equals(next.optString("instance"));
            if(sameInstance&&generation<desired.getLong("generation"))return true;
            boolean changed=!sameInstance||desired==null||generation!=desired.getLong("generation")||!kind.equals(desired.getString("kind"))||next.optInt("port")!=desired.optInt("port");
            desired=next;
            if(changed){acknowledged=-1;block("正在应用网络设置");apply();}
            else if(!ready&&acknowledged<0)apply();else {lastNotice=ready?"":"网络暂不可用";listener.route(ready,next.optBoolean("foreground"),lastNotice);}
        }catch(Exception error){block("网络配置无效，已停止加载");}
        return true;
    }
    void importAsset(EnvironmentTransferController.Lease ticket,String name,String mime,InputStream body,long expectedLength,ImportCompletion completion){
        String accountId=ticket.owner.accountId,storageId=ticket.owner.storageId,tabId=ticket.owner.tabId,url=ticket.owner.url;
        if(body==null){completion.complete(false,"下载内容不可用");return;}
        if(!ready||service==null||desired==null||closed||ticket.closed||!matches(ticket.instance,ticket.generation)||expectedLength>256L*1024*1024){try{body.close();}catch(IOException ignored){}transfers.release(ticket,"cancel");completion.complete(false,"当前网络无法保存文件，请重试");return;}
        ImportCompletion finished=(success,message)->{transfers.imported(ticket);completion.complete(success,message);};
        final String operation=UUID.randomUUID().toString();final Messenger destination=service;ParcelFileDescriptor[] pipe=null;
        try{
            pipe=ParcelFileDescriptor.createPipe();Bundle data=new Bundle();data.putString("operation",operation);data.putString("accountId",accountId);data.putString("storageId",storageId);data.putString("tabId",tabId);data.putString("url",url);data.putString("name",name);data.putString("mime",mime);data.putString("instance",ticket.instance);data.putLong("generation",ticket.generation);data.putString("ticket",ticket.id);data.putString("uri",ticket.uri);data.putLong("expectedLength",expectedLength);data.putParcelable("pipe",pipe[0]);
            Message begin=Message.obtain(null,4);begin.replyTo=inbox;begin.setData(data);imports.put(operation,finished);destination.send(begin);pipe[0].close();final ParcelFileDescriptor writer=pipe[1];
            ui.postDelayed(()->{ImportCompletion callback=imports.remove(operation);if(callback!=null){try{writer.close();}catch(IOException ignored){}try{body.close();}catch(IOException ignored){}callback.complete(false,"文件保存尚未确认，请到资产库核对");}},600000);
            FILE_IO.execute(()->{
                boolean success=false;
                try(InputStream source=body;OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(writer)){
                    byte[] buffer=new byte[32768];long total=0;int count;while((count=source.read(buffer))!=-1){if(count==0)continue;total+=count;if(total>256L*1024*1024||(expectedLength>=0&&total>expectedLength))throw new IOException("文件大小无效");output.write(buffer,0,count);}output.flush();if(expectedLength>=0&&total!=expectedLength)throw new IOException("下载内容不完整");success=true;
                }catch(Exception rejected){try{writer.close();}catch(IOException ignored){}}
                final boolean copied=success;ui.post(()->{
                    try{Message finish=Message.obtain(null,5);finish.replyTo=inbox;Bundle result=new Bundle();result.putString("operation",operation);result.putBoolean("success",copied);finish.setData(result);destination.send(finish);}catch(RemoteException disconnected){ImportCompletion callback=imports.remove(operation);if(callback!=null)callback.complete(false,"文件服务连接中断，请重试");}
                });
            });
        }catch(Exception failed){
            imports.remove(operation);if(pipe!=null)for(ParcelFileDescriptor descriptor:pipe)try{descriptor.close();}catch(IOException ignored){}try{body.close();}catch(IOException ignored){}transfers.release(ticket,"cancel");finished.complete(false,"文件保存未能开始，请重试");
        }
    }
    private static final class LocalPipe {
        final InputStream body;volatile boolean cancelled;volatile ParcelFileDescriptor writer;
        LocalPipe(InputStream body){this.body=body;}
        void cancel(){cancelled=true;try{body.close();}catch(IOException ignored){}try{if(writer!=null)writer.close();}catch(IOException ignored){}}
    }
    void localBroker(int what,JSONObject value,java.util.function.Consumer<JSONObject> callback)throws Exception{
        String call=UUID.randomUUID().toString();value.put("call",call);localCalls.add(call);calls.put(call,result->{localCalls.remove(call);callback.accept(result);});
        try{sendPayload(what,value);}catch(Exception disconnected){java.util.function.Consumer<JSONObject> failed=calls.remove(call);if(failed!=null)failed.accept(new JSONObject());return;}
        ui.postDelayed(()->{java.util.function.Consumer<JSONObject> pending=calls.remove(call);if(pending!=null){if(what==14)cancelLocal(value.optString("operation"),value.optString("cancelKey"),null);pending.accept(new JSONObject());}},5000);
    }
    void cancelLocal(String operation,String cancelKey,String capability){
        LocalPipe pipe=localPipes.remove(operation);if(pipe!=null)pipe.cancel();
        try{JSONObject value=new JSONObject().put("operation",operation).put("action","cancel");if(capability!=null)value.put("capability",capability);else value.put("cancelKey",cancelKey);sendPayload(16,value);}catch(Exception disconnected){/* The old broker revokes all local exports when the client dies. */}
        ImportCompletion callback=imports.remove(operation);if(callback!=null)callback.complete(false,"本地文件保存已取消");
    }
    void importLocal(String operation,String capability,String cancelKey,String name,String mime,InputStream body,long expected,java.util.function.BooleanSupplier valid,ImportCompletion completion){
        LocalPipe copy=new LocalPipe(body);localPipes.put(operation,copy);ParcelFileDescriptor[] pipe=null;
        try{
            if(service==null||closed||expected < -1||expected>256L*1024*1024||!valid.getAsBoolean())throw new IOException("Local export unavailable");
            pipe=ParcelFileDescriptor.createPipe();copy.writer=pipe[1];Bundle data=new Bundle();data.putString("operation",operation);data.putString("capability",capability);data.putString("name",name);data.putString("mime",mime);data.putLong("expectedLength",expected);data.putParcelable("pipe",pipe[0]);
            imports.put(operation,(success,message)->{localPipes.remove(operation);copy.cancel();completion.complete(success,message);});Message begin=Message.obtain(null,15);begin.replyTo=inbox;begin.setData(data);service.send(begin);pipe[0].close();
            LOCAL_FILE_IO.execute(()->{
                boolean copied=false;
                try(InputStream input=body;OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(copy.writer)){
                    byte[] buffer=new byte[32768];long count=0;int read;while((read=input.read(buffer))!=-1){if(copy.cancelled||!valid.getAsBoolean())throw new IOException("Local source revoked");if(read>0){count+=read;if(count>256L*1024*1024||expected>=0&&count>expected)throw new IOException("Local length invalid");output.write(buffer,0,read);}}output.flush();if(expected>=0&&count!=expected)throw new IOException("Local stream incomplete");copied=true;
                }catch(Exception interrupted){copy.cancel();}
                boolean sourceRead=copied;ui.post(()->{
                    try{boolean success=sourceRead&&!copy.cancelled&&valid.getAsBoolean();sendPayload(16,new JSONObject().put("operation",operation).put("capability",capability).put("action","complete").put("success",success));}
                    catch(Exception unavailable){cancelLocal(operation,cancelKey,capability);}
                });
            });
        }catch(Exception unavailable){if(pipe!=null)for(ParcelFileDescriptor descriptor:pipe)try{descriptor.close();}catch(IOException ignored){}copy.cancel();boolean registered=imports.containsKey(operation);cancelLocal(operation,cancelKey,capability);if(!registered)completion.complete(false,"本地文件暂不可保存");}
    }
    private void localBrokerLost(){
        localExports.brokerLost();for(String call:new ArrayList<>(localCalls)){java.util.function.Consumer<JSONObject> callback=calls.remove(call);if(callback!=null)callback.accept(new JSONObject());}localCalls.clear();
    }
    void handoffListener(HandoffListener next){handoffListener=next;}
    void cancelHandoff(){handoffLost("已停止自动发送；请查看网页任务记录");}
    /** Independent task RPCs never enter the HTTP/local-file callback tables. */
    void handoff(String action,JSONObject data,java.util.function.BiConsumer<JSONObject,String> callback) {
        if(closed||service==null||handoffCalls.size()>=16){callback.accept(null,"任务服务暂不可用");return;}
        boolean mutates=!java.util.Arrays.asList("list","get","cancel","failed","cancelled").contains(action);
        if(mutates&&!ready()){callback.accept(null,"网络尚未就绪，任务未发送");return;}
        String call=UUID.randomUUID().toString();
        if("claim".equals(action)){
            if(!handoffClaimCall.isEmpty()||!handoffCapability.isEmpty()){callback.accept(null,"已有网页任务正在运行");return;}
            handoffClaimCall=call;
        }
        handoffCalls.put(call,new HandoffCall(action,callback));
        try{sendPayload(17,new JSONObject().put("call",call).put("action",action).put("data",data));}
        catch(Exception unavailable){handoffCalls.remove(call);if(call.equals(handoffClaimCall))handoffClaimCall="";callback.accept(null,"任务服务连接已中断");return;}
        ui.postDelayed(()->{
            HandoffCall pending=handoffCalls.remove(call);if(pending==null)return;
            if(!java.util.Arrays.asList("list","get","cancel").contains(action))abandonHandoff(call);
            pending.callback.accept(null,"任务服务确认超时；不会自动重发");
        },5000);
    }
    private void abandonHandoff(String claimCall) {
        try{sendPayload(17,new JSONObject().put("call",UUID.randomUUID().toString()).put("action","abandon").put("data",new JSONObject().put("claimCall",claimCall.isEmpty()?handoffClaimCall:claimCall).put("capability",handoffCapability)));}
        catch(Exception disconnected){/* Broker/client death owns central task termination. */}
        handoffClaimCall="";handoffCapability="";handoffId="";
    }
    private void handoffLost(String message) {
        boolean active=!handoffClaimCall.isEmpty()||!handoffCapability.isEmpty();
        if(active)abandonHandoff("");
        ArrayList<HandoffCall> cancelled=new ArrayList<>(handoffCalls.values());handoffCalls.clear();
        for(HandoffCall call:cancelled)call.callback.accept(null,message);
        if(active&&handoffListener!=null)handoffListener.lost(message);
    }
    private void receiveHandoffReply(Bundle bundle) {
        HandoffCall pending=null;boolean delivered=false;
        try {
            String raw=bundle.getString("payload","");if(raw.length()>600000)throw new IllegalArgumentException();JSONObject value=new JSONObject(raw);String call=value.getString("call");pending=handoffCalls.remove(call);
            JSONObject result=value.optJSONObject("result");
            if(pending==null){if(result!=null&&result.has("capability"))try{sendPayload(17,new JSONObject().put("call",UUID.randomUUID().toString()).put("action","abandon").put("data",new JSONObject().put("claimCall",call).put("capability",result.getString("capability"))));}catch(Exception disconnected){/* Late claims cannot reopen a local task. */}return;}
            if(!value.optBoolean("ok")||result==null){if(call.equals(handoffClaimCall))handoffClaimCall="";delivered=true;pending.callback.accept(null,value.optString("message","任务未能完成；不会自动重发"));return;}
            if("claim".equals(pending.action)) {
                if(closed||!ready()||!call.equals(handoffClaimCall)){abandonHandoff(call);delivered=true;pending.callback.accept(null,"目标环境已失效，任务未发送");return;}
                String capability=result.getString("capability"),id=result.getString("id");WorkspaceState.profile(capability);WorkspaceState.profile(id);handoffCapability=capability;handoffId=id;
            }
            delivered=true;pending.callback.accept(result,null);
        }catch(Exception invalid){if(pending!=null&&!delivered)pending.callback.accept(null,"任务服务返回无效状态；已停止自动发送");handoffLost("任务服务返回无效状态；已停止自动发送");}
    }
    private void receiveHandoffEvent(Bundle bundle) {
        try {
            String raw=bundle.getString("payload","");if(raw.length()>65536)throw new IllegalArgumentException();JSONObject value=new JSONObject(raw);
            if(handoffId.isEmpty()||!handoffId.equals(value.getString("id")))return;
            JSONObject job=value.optJSONObject("job");
            if(job!=null){if(!handoffId.equals(job.getString("id")))throw new IllegalArgumentException();if(!HandoffStore.active(job.getString("status"))){handoffCapability="";handoffId="";handoffClaimCall="";}if(handoffListener!=null)handoffListener.changed(job);}
            else if(value.has("message"))handoffLost(value.getString("message"));
        }catch(Exception invalid){handoffLost("任务状态返回无效；已停止自动发送");}
    }
    boolean ready(){return ready&&!closed;}
    JSONObject tuple()throws Exception{if(!ready())throw new IllegalStateException("Route is blocked");return new JSONObject(desired.toString()).put("routeToken",routeToken);}
    boolean matches(String instance,long generation){return ready()&&desired!=null&&instance.equals(desired.optString("instance"))&&generation==desired.optLong("generation",-1);}
    boolean matches(JSONObject value){return matches(value.optString("instance"),value.optLong("generation",-1))&&routeToken.equals(value.optString("routeToken"));}
    void post(WebExtension.Port source,JSONObject value){if(source==port)source.postMessage(value);}
    void sendPayload(int what,JSONObject value)throws Exception{if(service==null)throw new RemoteException();Message message=Message.obtain(null,what);message.replyTo=inbox;Bundle data=new Bundle();data.putString("payload",value.toString());message.setData(data);service.send(message);}
    void broker(int what,JSONObject value,java.util.function.Consumer<JSONObject> callback)throws Exception{
        String call=UUID.randomUUID().toString();value.put("call",call);calls.put(call,callback);
        try{sendPayload(what,value);}catch(Exception disconnected){calls.remove(call);callback.accept(new JSONObject());return;}
        ui.postDelayed(()->{
            java.util.function.Consumer<JSONObject> pending=calls.remove(call);
            if(pending!=null){
                if(what==6)try{sendPayload(7,new JSONObject().put("lease",value.getString("operation")).put("status","cancel"));}catch(Exception disconnected){/* A dead broker owns no surviving lease; binder death revokes the client. */}
                pending.accept(new JSONObject());
            }
        },5000);
    }
    private void apply(){
        if(port==null||desired==null||closed)return;
        try{routeToken=java.util.UUID.randomUUID().toString();JSONObject value=new JSONObject(desired.toString()).put("type","route").put("routeToken",routeToken);sent=value.getLong("generation");port.postMessage(value);long wanted=sent;String wantedToken=routeToken;ui.postDelayed(()->{if(!closed&&sent==wanted&&wantedToken.equals(routeToken)&&acknowledged!=wanted)block("网络设置尚未确认，请稍后重试");},12000);}catch(Exception error){block("网络设置发送失败");}
    }
    private void block(String notice){handoffLost("网络连接已失效，已停止自动发送；不会自动重发");transfers.revoke();for(String call:new ArrayList<>(calls.keySet()))if(!localCalls.contains(call)){java.util.function.Consumer<JSONObject> callback=calls.remove(call);if(callback!=null)callback.accept(new JSONObject());}lastNotice=notice;ready=false;acknowledged=-1;routeToken="";if(port!=null)try{port.postMessage(new JSONObject().put("type","suspend"));}catch(Exception ignored){}listener.route(false,desired!=null&&desired.optBoolean("foreground"),notice);}
    private void send(int what,int value){if(service==null)return;try{Message message=Message.obtain(null,what,value,0);message.replyTo=inbox;if(what==1){Bundle data=new Bundle();data.putInt("slot",slot);message.setData(data);}service.send(message);}catch(RemoteException e){block("网络服务连接中断");}}
    void foreground(boolean value){foreground=value;send(2,value?1:0);}
    void close(){closed=true;handoffLost("目标环境已关闭；不会自动重发");localBrokerLost();foreground(false);if(port!=null){port.disconnect();port=null;}if(bound){context.unbindService(connection);bound=false;}}
}
