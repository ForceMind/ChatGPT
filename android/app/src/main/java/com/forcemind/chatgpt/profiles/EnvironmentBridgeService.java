package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Same-UID route/foreground broker. No browser credentials or account data cross this channel. */
public final class EnvironmentBridgeService extends Service implements Application.ActivityLifecycleCallbacks {
    private static WeakReference<EnvironmentBridgeService> current = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<IBinder, Client> clients = new LinkedHashMap<>();
    private final String instance = UUID.randomUUID().toString();
    private final Runnable changed = this::broadcast;
    private NetworkSession network;
    private final java.util.concurrent.ExecutorService imports = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final Map<String, Import> incomingFiles = new LinkedHashMap<>();
    private BrowserMemory memory;
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), this::message));
    private final class Client implements IBinder.DeathRecipient {
        final Messenger destination; final IBinder token; boolean foreground;
        Client(Messenger destination) { this.destination = destination; token = destination.getBinder(); }
        @Override public void binderDied() { main.post(() -> remove(token)); }
    }
    private final class Import {
        final String id,account,storage,tab; final Client client; final NetworkRoute.Snapshot route;
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        final java.io.InputStream pipe; final long expected;
        Import(Bundle data, Client client, ParcelFileDescriptor descriptor, NetworkRoute.Snapshot route) throws Exception {
            id=data.getString("operation");account=data.getString("accountId");storage=data.getString("storageId");tab=data.getString("tabId");
            WorkspaceState.profile(id);WorkspaceState.profile(account);WorkspaceState.profile(storage);WorkspaceState.profile(tab);
            this.client=client;this.route=route;expected=data.getLong("expectedLength",-1);
            if(expected < -1 || expected > 512L*1024*1024)throw new IllegalArgumentException();
            pipe=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        }
        boolean valid() {
            if(failed.get()||network.closed()||!network.ready()||network.routes.snapshot()!=route)return false;
            try{WorkspaceState.Account a=new StateStore(getFilesDir()).load().find(account);return a.isEnvironment()&&a.storageId.equals(storage)&&a.tabs.stream().anyMatch(t->t.id.equals(tab));}
            catch(Exception error){return false;}
        }
        void cancel(){failed.set(true);completed.countDown();try{pipe.close();}catch(Exception ignored){}}
    }
    static void refreshIfRunning() {
        EnvironmentBridgeService service = current.get();
        if (service != null) service.main.post(service::broadcast);
    }
    @Override public void onCreate() {
        super.onCreate();
        if (!BuildConfig.ENVIRONMENT_CLIENT) { stopSelf(); return; }
        current = new WeakReference<>(this);
        memory = BrowserMemory.get(this); network = memory.network();
        getApplication().registerActivityLifecycleCallbacks(this);
        network.observePanel(changed);
    }
    @Override public IBinder onBind(Intent intent) { return BuildConfig.ENVIRONMENT_CLIENT ? incoming.getBinder() : null; }
    private boolean message(Message message) {
        if (!BuildConfig.ENVIRONMENT_CLIENT || message.sendingUid != android.os.Process.myUid() || message.replyTo == null) return true;
        IBinder token = message.replyTo.getBinder();
        if (message.what == 1) {
            if (!clients.containsKey(token)) {
                Client client = new Client(message.replyTo);
                try { token.linkToDeath(client, 0); clients.put(token, client); }
                catch (RemoteException dead) { return true; }
            }
        } else if (message.what == 2) {
            Client client = clients.get(token); if (client == null) return true;
            client.foreground = message.arg1 == 1;
        } else if (message.what == 3) { remove(token); return true; }
        else if(message.what==4){beginImport(clients.get(token),message.getData());return true;}
        else if(message.what==5){Import task=incomingFiles.get(message.getData().getString("operation"));if(task!=null&&task.client.token.equals(token)&&task.completed.getCount()>0){task.success.set(message.getData().getBoolean("success"));if(!task.success.get())task.failed.set(true);task.completed.countDown();}return true;}
        else return true;
        broadcast(); return true;
    }
    private void remove(IBinder token) {
        Client client = clients.remove(token);
        if (client != null) token.unlinkToDeath(client, 0);
        for(Import task:new ArrayList<>(incomingFiles.values()))if(task.client.token.equals(token))task.cancel();
        broadcast();
    }
    private void importReply(Client client,String id,boolean success,String message){
        if(client==null)return;
        Message reply=Message.obtain(null,11);Bundle data=new Bundle();data.putString("operation",id);data.putBoolean("success",success);data.putString("message",message);reply.setData(data);
        try{client.destination.send(reply);}catch(RemoteException dead){remove(client.token);}
    }
    private void beginImport(Client client,Bundle data){
        ParcelFileDescriptor descriptor=data.getParcelable("pipe");String id=data.getString("operation");
        Import accepted=null;
        if(client==null){if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}return;}
        try{
            WorkspaceState.profile(id);
            if(descriptor==null||incomingFiles.containsKey(id)||incomingFiles.size()>=32||!instance.equals(data.getString("instance"))||network.routes.snapshot().generation!=data.getLong("generation",-1))throw new IllegalArgumentException();
            NetworkRoute.Snapshot route=network.beginEnvironmentFile(id);
            Import task;
            try{task=new Import(data,client,descriptor,route);if(!task.valid())throw new IllegalArgumentException();}
            catch(Exception invalid){network.finishEnvironmentFile(id);throw invalid;}
            incomingFiles.put(id,task);
            accepted=task;
            String name=AssetLedger.filename(data.getString("name","download")),mime=data.getString("mime","application/octet-stream");
            if(mime.length()>160||mime.indexOf('\n')>=0||mime.indexOf('\r')>=0){task.cancel();incomingFiles.remove(id);network.finishEnvironmentFile(id);throw new IllegalArgumentException();}
            imports.execute(()->{
                boolean ok=false;
                try{
                    java.io.InputStream checked=new java.io.FilterInputStream(task.pipe){
                        long count;boolean ended;
                        private int check(int size)throws java.io.IOException{
                            if(!task.valid())throw new java.io.IOException("文件任务已失效");
                            if(size>0){count+=size;if(count>512L*1024*1024||task.expected>=0&&count>task.expected)throw new java.io.IOException("文件长度无效");}
                            if(size<0&&!ended){ended=true;try{if(!task.completed.await(30,java.util.concurrent.TimeUnit.SECONDS)||!task.success.get()||task.expected>=0&&count!=task.expected||!task.valid())throw new java.io.IOException("下载未完整完成");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}}
                            return size;
                        }
                        @Override public int read()throws java.io.IOException{int value=in.read();check(value<0?-1:1);return value;}
                        @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{return check(in.read(bytes,offset,length));}
                    };
                    try(java.io.InputStream body=checked){new AssetLedger(getFilesDir(),task.account).importCopy(body,name,mime,"download",null,task::valid);}
                    ok=true;
                }catch(Exception failure){task.cancel();}
                boolean result=ok;
                main.post(()->{incomingFiles.remove(task.id);network.finishEnvironmentFile(task.id);importReply(task.client,task.id,result,result?"已保存到当前账号资产库":"文件保存未完成，原文件与已有资产保留");});
            });
        }catch(Exception rejected){if(accepted!=null&&incomingFiles.remove(id)==accepted){accepted.cancel();network.finishEnvironmentFile(id);}if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}importReply(client,id,false,"文件暂不可保存，请确认线路与账号后重试");}
    }
    private void broadcast() {
        if (memory == null || network == null) return;
        boolean foreground = false;
        for (Client client : clients.values()) foreground |= client.foreground;
        memory.setEnvironmentForeground(foreground);
        for(Import task:new ArrayList<>(incomingFiles.values()))if(!task.valid())task.cancel();
        NetworkRoute.Snapshot snapshot = network.routes.snapshot();
        String kind = !network.ready() ? "blocked" : snapshot.state == NetworkRoute.State.CONNECTED ? "proxy"
                : snapshot.state == NetworkRoute.State.DIRECT ? "direct" : "system";
        try {
            JSONObject state = new JSONObject().put("instance", instance).put("kind", kind)
                .put("port", "proxy".equals(kind) ? snapshot.port : 0).put("generation", snapshot.generation)
                .put("foreground", memory.retainsExecution()).put("status", network.status());
            for (Client client : new ArrayList<>(clients.values())) {
                Message reply = Message.obtain(null, 10); Bundle data = new Bundle(); data.putString("state", state.toString()); reply.setData(data);
                try { client.destination.send(reply); }
                catch (RemoteException dead) { clients.remove(client.token); client.token.unlinkToDeath(client, 0); }
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException("Invalid internal route state", impossible); }
    }
    @Override public void onDestroy() {
        if (network != null) network.removePanelObserver(changed);
        getApplication().unregisterActivityLifecycleCallbacks(this);
        for (Client client : new ArrayList<>(clients.values())) client.token.unlinkToDeath(client, 0);
        clients.clear();
        for(Import task:new ArrayList<>(incomingFiles.values()))task.cancel();incomingFiles.clear();imports.shutdown();
        if (memory != null) memory.setEnvironmentForeground(false);
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
    @Override public void onActivityStarted(Activity activity) { broadcast(); }
    @Override public void onActivityStopped(Activity activity) { broadcast(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
