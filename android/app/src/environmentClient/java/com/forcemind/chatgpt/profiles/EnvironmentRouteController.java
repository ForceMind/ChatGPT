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
    private final Context context;private Listener listener;private String lastNotice="正在连接账号环境";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),this::receive));
    private Messenger service;private WebExtension.Port port;private JSONObject desired;
    interface ImportCompletion{void complete(boolean success,String message);}
    private static final ExecutorService FILE_IO=Executors.newSingleThreadExecutor();
    private final Map<String,ImportCompletion> imports=new HashMap<>();
    private String routeToken="";private boolean bound,foreground,ready,closed;private long sent=-1,acknowledged=-1;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=new Messenger(binder);send(1,0);send(2,foreground?1:0);}
        public void onServiceDisconnected(ComponentName name){service=null;block("网络连接正在恢复");}
        public void onBindingDied(ComponentName name){service=null;block("网络连接正在恢复");if(bound){context.unbindService(this);bound=false;}ui.postDelayed(()->{if(!closed)bind();},500);}
        public void onNullBinding(ComponentName name){block("网络服务不可用");}
    };
    EnvironmentRouteController(Context context,GeckoRuntime runtime,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
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
                    if(source!=port||!(raw instanceof JSONObject)||desired==null||routeToken.isEmpty())return;JSONObject value=(JSONObject)raw;
                    if(!"route-applied".equals(value.optString("type"))||value.getLong("generation")!=sent||sent!=desired.getLong("generation")||!routeToken.equals(value.optString("routeToken"))||!desired.optString("instance").equals(value.optString("instance")))return;
                    if(!value.optBoolean("ok")||!desired.getString("kind").equals(value.getString("kind"))||desired.optInt("port")!=value.optInt("port")){block("网络设置未能生效，请重试");return;}
                    acknowledged=sent;ready=!"blocked".equals(desired.getString("kind"));lastNotice=ready?"":"网络暂不可用";listener.route(ready,desired.optBoolean("foreground"),lastNotice);
                }catch(Exception error){block("网络设置确认失败");}
            }
            public void onDisconnect(WebExtension.Port source){if(source==port){port=null;block("网络连接正在恢复");}}
        });apply();
    }
    private boolean receive(Message message){
        if(message.what==11){String operation=message.getData().getString("operation","");ImportCompletion callback=imports.remove(operation);if(callback!=null)callback.complete(message.getData().getBoolean("success"),message.getData().getString("message","文件保存未完成"));return true;}
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
    void importAsset(String accountId,String storageId,String tabId,String url,String name,String mime,InputStream body,long expectedLength,ImportCompletion completion){
        if(body==null){completion.complete(false,"下载内容不可用");return;}
        if(!ready||service==null||desired==null||closed||expectedLength>256L*1024*1024){try{body.close();}catch(IOException ignored){}completion.complete(false,"当前网络无法保存文件，请重试");return;}
        final String operation=UUID.randomUUID().toString();final Messenger destination=service;ParcelFileDescriptor[] pipe=null;
        try{
            pipe=ParcelFileDescriptor.createPipe();Bundle data=new Bundle();data.putString("operation",operation);data.putString("accountId",accountId);data.putString("storageId",storageId);data.putString("tabId",tabId);data.putString("url",url);data.putString("name",name);data.putString("mime",mime);data.putString("instance",desired.getString("instance"));data.putLong("generation",desired.getLong("generation"));data.putLong("expectedLength",expectedLength);data.putParcelable("pipe",pipe[0]);
            Message begin=Message.obtain(null,4);begin.replyTo=inbox;begin.setData(data);imports.put(operation,completion);destination.send(begin);pipe[0].close();final ParcelFileDescriptor writer=pipe[1];
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
            imports.remove(operation);if(pipe!=null)for(ParcelFileDescriptor descriptor:pipe)try{descriptor.close();}catch(IOException ignored){}try{body.close();}catch(IOException ignored){}completion.complete(false,"文件保存未能开始，请重试");
        }
    }
    private void apply(){
        if(port==null||desired==null||closed)return;
        try{routeToken=java.util.UUID.randomUUID().toString();JSONObject value=new JSONObject(desired.toString()).put("type","route").put("routeToken",routeToken);sent=value.getLong("generation");port.postMessage(value);long wanted=sent;String wantedToken=routeToken;ui.postDelayed(()->{if(!closed&&sent==wanted&&wantedToken.equals(routeToken)&&acknowledged!=wanted)block("网络设置尚未确认，请稍后重试");},12000);}catch(Exception error){block("网络设置发送失败");}
    }
    private void block(String notice){lastNotice=notice;ready=false;acknowledged=-1;routeToken="";if(port!=null)try{port.postMessage(new JSONObject().put("type","suspend"));}catch(Exception ignored){}listener.route(false,desired!=null&&desired.optBoolean("foreground"),notice);}
    private void send(int what,int value){if(service==null)return;try{Message message=Message.obtain(null,what,value,0);message.replyTo=inbox;service.send(message);}catch(RemoteException e){block("网络服务连接中断");}}
    void foreground(boolean value){foreground=value;send(2,value?1:0);}
    void close(){closed=true;foreground(false);if(port!=null){port.disconnect();port=null;}if(bound){context.unbindService(connection);bound=false;}}
}
