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

/** Same-UID route/file ownership broker. JSON control messages carry no Cookie headers or saved credentials. */
public final class EnvironmentBridgeService extends Service implements Application.ActivityLifecycleCallbacks {
    private static WeakReference<EnvironmentBridgeService> current = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<IBinder, Client> clients = new LinkedHashMap<>();
    private final String instance = UUID.randomUUID().toString();
    private final Runnable changed = this::broadcast;
    private NetworkSession network;
    private final java.util.concurrent.ExecutorService imports = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final Map<String, Import> incomingFiles = new LinkedHashMap<>();
    private final Map<String,Lease> leases=new LinkedHashMap<>();
    private final Map<String,LocalExport> localExports=new LinkedHashMap<>();
    private BrowserMemory memory;
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), this::message));
    private final class Client implements IBinder.DeathRecipient {
        final Messenger destination; final IBinder token;final int slot;volatile boolean alive=true;boolean foreground;
        Client(Messenger destination,int slot) { this.destination = destination; token = destination.getBinder();this.slot=slot; }
        @Override public void binderDied() { main.post(() -> remove(token)); }
    }
    private final class Lease {
        final String id,purpose,account,storage,tab,url;String uri;final Client client;final NetworkRoute.Snapshot route;
        volatile boolean failed,networkSuccess;boolean claimed,terminal,downloadCandidate;
        final java.util.concurrent.CountDownLatch networkFinished=new java.util.concurrent.CountDownLatch(1);
        Lease(JSONObject data,Client client)throws Exception{
            id=data.getString("operation");purpose=data.getString("purpose");account=data.optString("accountId");storage=data.optString("storageId");tab=data.optString("tabId");url=data.getString("url");uri=data.getString("uri");this.client=client;
            WorkspaceState.profile(id);if(!"runtime".equals(purpose)){WorkspaceState.profile(account);WorkspaceState.profile(storage);WorkspaceState.profile(tab);}else if(!account.isEmpty()||!storage.isEmpty()||!tab.isEmpty())throw new IllegalArgumentException();
            if(!java.util.Arrays.asList("request","picker","runtime").contains(purpose)||leases.containsKey(id)||!instance.equals(data.getString("instance"))||network.routes.snapshot().generation!=data.getLong("generation")||!validHttpUri(uri)||url.length()>8192)throw new IllegalArgumentException();
            if(!ownerValid())throw new IllegalArgumentException();
            route="picker".equals(purpose)?network.beginEnvironmentFile(id):network.beginEnvironmentRequest(id);
        }
        boolean ownerValid(){if("runtime".equals(purpose))return client.alive;try{WorkspaceState.Account a=new StateStore(getFilesDir()).load().find(account);return client.alive&&a.isEnvironment()&&a.environmentSlot==client.slot&&a.storageId.equals(storage)&&a.tabs.stream().anyMatch(t->t.id.equals(tab));}catch(Exception unavailable){return false;}}
        boolean valid(){return !failed&&!network.closed()&&network.ready()&&network.routes.snapshot()==route&&ownerValid();}
    }
    private static boolean validHttpUri(String value){try{java.net.URI uri=java.net.URI.create(value);return value.length()<=8192&&uri.getHost()!=null&&uri.getRawUserInfo()==null&&("https".equals(uri.getScheme())||"http".equals(uri.getScheme()));}catch(Exception invalid){return false;}}
    private void leaseReply(Client client,String call,boolean ok,Lease lease){
        if(client==null)return;try{JSONObject value=new JSONObject().put("call",call).put("ok",ok);if(lease!=null)value.put("lease",lease.id).put("instance",instance).put("generation",lease.route.generation);Message reply=Message.obtain(null,12);Bundle data=new Bundle();data.putString("payload",value.toString());reply.setData(data);client.destination.send(reply);}catch(Exception disconnected){remove(client.token);}
    }
    private void finishLease(Lease lease,boolean failed){
        if(leases.get(lease.id)!=lease)return;leases.remove(lease.id);lease.failed=failed;if(failed)lease.networkFinished.countDown();
        if(failed){for(Import task:new ArrayList<>(incomingFiles.values()))if(task.lease==lease)task.cancel();try{Message notice=Message.obtain(null,13);Bundle data=new Bundle();data.putString("lease",lease.id);notice.setData(data);lease.client.destination.send(notice);}catch(RemoteException disconnected){/* Binder death removes the remaining client leases. */}}
        // Queue the busy notification after removing the capability; panel observers may broadcast recursively.
        main.post(()->network.finishEnvironmentFile(lease.id));
    }
    private void leaseMessage(int what,Client client,Bundle bundle){
        if(client==null)return;String call="";
        try{
            String raw=bundle.getString("payload","");if(raw.length()>32768)throw new IllegalArgumentException();JSONObject data=new JSONObject(raw);call=data.optString("call");
            if(what==9){for(Lease lease:new ArrayList<>(leases.values()))if(lease.client==client)finishLease(lease,true);return;}
            if(what==6){Lease lease=new Lease(data,client);leases.put(lease.id,lease);main.postDelayed(()->finishLease(lease,true),600000);leaseReply(client,call,true,lease);return;}
            Lease lease=leases.get(data.getString("lease"));if(lease==null||lease.client!=client)throw new IllegalArgumentException();
            if(what==7){String status=data.optString("status");if("completed".equals(status)&&!"picker".equals(lease.purpose)){
                lease.terminal=true;lease.networkSuccess=true;lease.networkFinished.countDown();if(lease.claimed)return;if(lease.downloadCandidate){main.postDelayed(()->{if(!lease.claimed&&lease.terminal)finishLease(lease,true);},30000);return;}
                finishLease(lease,false);
            }else finishLease(lease,true);return;}
            if(what==8){if(!lease.valid()||!instance.equals(data.getString("instance"))||lease.route.generation!=data.getLong("generation"))throw new IllegalArgumentException();
                String action=data.optString("action");if("response".equals(action)){if(!lease.uri.equals(data.getString("uri")))throw new IllegalArgumentException();lease.downloadCandidate="request".equals(lease.purpose)&&data.optBoolean("download");}
                else if("redirect".equals(action)){String uri=data.getString("uri");if(lease.claimed||!validHttpUri(uri))throw new IllegalArgumentException();lease.uri=uri;}
                else if(!"validate".equals(action))throw new IllegalArgumentException();
                leaseReply(client,call,true,lease);return;
            }
            throw new IllegalArgumentException();
        }catch(Exception rejected){leaseReply(client,call,false,null);}
    }
    /** Local bytes have source ownership, not a network route or HTTP completion signal. */
    private final class LocalExport {
        final String id,account,storage,tab,source,document,kind,fingerprint,cancelKey,capability=UUID.randomUUID().toString();final long epoch;final Client client;
        final java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean(),held=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
        volatile boolean childSuccess,eof,published;volatile long bytes;boolean started;java.io.InputStream pipe;
        LocalExport(JSONObject data,Client client)throws Exception{
            id=data.getString("operation");account=data.getString("accountId");storage=data.getString("storageId");tab=data.getString("tabId");source=data.getString("source");document=data.getString("document");epoch=data.getLong("epoch");kind=data.getString("kind");fingerprint=data.getString("fingerprint");cancelKey=data.getString("cancelKey");WorkspaceState.profile(cancelKey);if(cancelKey.equals(id))throw new IllegalArgumentException();this.client=client;
            for(String value:new String[]{id,account,storage,tab,document})WorkspaceState.profile(value);
            if(epoch<0||!java.util.Arrays.asList("blob","data").contains(kind)||!fingerprint.matches("[a-f0-9]{64}")||source.length()>8192||localSource(source)==null||!valid())throw new IllegalArgumentException();
        }
        boolean valid(){if(failed.get()||!client.alive)return false;try{WorkspaceState.Account a=new StateStore(getFilesDir()).load().find(account);String canonical=localSource(source);return canonical!=null&&a.isEnvironment()&&a.environmentSlot==client.slot&&a.storageId.equals(storage)&&a.tabs.stream().anyMatch(t->t.id.equals(tab)&&t.url.equals(canonical));}catch(Exception unavailable){return false;}}
        boolean publishAllowed(){
            if(!valid())return false;
            if(eof&&held.compareAndSet(false,true))try{EnvironmentLocalExportChecks.beforePublish(id,account,tab,bytes,this::valid);}catch(java.io.IOException rejected){failed.set(true);return false;}
            return valid();
        }
        void cancel(){failed.set(true);finished.countDown();try{if(pipe!=null)pipe.close();}catch(java.io.IOException ignored){}}
    }
    private static String localSource(String value){String safe=PageRecoveryRecord.url(value);return safe!=null?safe:(EnvironmentPolicy.testUrl(value)?value:null);}
    private void localReply(Client client,String call,boolean ok,String operation){
        try{JSONObject result=new JSONObject().put("call",call).put("ok",ok).put("localExport",true);if(ok){result.put("operation",operation).put("capability",localExports.get(operation).capability);}Message message=Message.obtain(null,12);Bundle data=new Bundle();data.putString("payload",result.toString());message.setData(data);client.destination.send(message);}catch(Exception disconnected){remove(client.token);}
    }
    private void finishLocal(LocalExport task,boolean success,String message){
        if(localExports.remove(task.id)!=task)return;success=success||task.published;if(!success)task.cancel();
        EnvironmentLocalExportChecks.stage(success?"publish":"cancel",task.id,task.account,task.tab,task.bytes);
        importReply(task.client,task.id,success,message);
    }
    private void localControl(int what,Client client,Bundle bundle){
        if(client==null)return;String call="";
        try{
            String raw=bundle.getString("payload","");if(raw.length()>32768)throw new IllegalArgumentException();JSONObject data=new JSONObject(raw);call=data.optString("call");
            if(what==14){if(localExports.size()>=32||localExports.containsKey(data.getString("operation")))throw new IllegalArgumentException();LocalExport task=new LocalExport(data,client);localExports.put(task.id,task);main.postDelayed(()->finishLocal(task,false,"本地文件保存已取消"),600000);EnvironmentLocalExportChecks.stage("claimed",task.id,task.account,task.tab,0);localReply(client,call,true,task.id);return;}
            LocalExport task=localExports.get(data.getString("operation"));if(task==null||task.client!=client)return;
            boolean permitted=task.capability.equals(data.optString("capability"));if(!permitted&&!("cancel".equals(data.optString("action"))&&task.cancelKey.equals(data.optString("cancelKey"))))return;
            if("complete".equals(data.optString("action"))&&task.started&&task.finished.getCount()>0){task.childSuccess=data.optBoolean("success");if(!task.childSuccess)task.failed.set(true);task.finished.countDown();}
            else finishLocal(task,false,"本地文件保存已取消");
        }catch(Exception rejected){localReply(client,call,false,null);}
    }
    private void beginLocal(Client client,Bundle data){
        ParcelFileDescriptor descriptor=data.getParcelable("pipe");LocalExport task=localExports.get(data.getString("operation"));
        boolean authorized=client!=null&&task!=null&&task.client==client&&task.capability.equals(data.getString("capability"));
        if(!authorized||task.started||!task.valid()){
            if(descriptor!=null)try{descriptor.close();}catch(java.io.IOException ignored){}if(authorized&&!task.started)finishLocal(task,false,"本地文件来源已失效");return;
        }
        try{
            long expected=data.getLong("expectedLength",-1);if(descriptor==null||expected < -1||expected>AssetLedger.MAX_FILE)throw new IllegalArgumentException();
            String name=AssetLedger.filename(data.getString("name","网页文件")),mime=data.getString("mime","application/octet-stream");
            if(mime.length()>150||!mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))throw new IllegalArgumentException();
            task.started=true;task.pipe=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);EnvironmentLocalExportChecks.stage("confirm",task.id,task.account,task.tab,0);
            imports.execute(()->{
                boolean success=false;
                try(java.io.InputStream input=new java.io.FilterInputStream(task.pipe){
                    boolean ended;
                    private int check(int count)throws java.io.IOException{
                        if(!task.valid())throw new java.io.IOException("本地导出已失效");
                        if(count>0){task.bytes+=count;if(task.bytes>AssetLedger.MAX_FILE||expected>=0&&task.bytes>expected)throw new java.io.IOException("本地文件长度无效");}
                        if(count<0&&!ended){ended=true;try{if(!task.finished.await(30,java.util.concurrent.TimeUnit.SECONDS)||!task.childSuccess||expected>=0&&task.bytes!=expected||!task.valid())throw new java.io.IOException("本地文件未完整读取");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}task.eof=true;EnvironmentLocalExportChecks.stage("copy-eof",task.id,task.account,task.tab,task.bytes);}
                        return count;
                    }
                    @Override public int read()throws java.io.IOException{int count=in.read();check(count<0?-1:1);return count;}
                    @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{return check(in.read(bytes,offset,length));}
                }){new AssetLedger(getFilesDir(),task.account).importCopy(input,name,mime,"import",null,task::publishAllowed);task.published=true;success=true;}
                catch(Exception failed){task.cancel();EnvironmentLocalExportChecks.stage("error",task.id,task.account,task.tab,task.bytes);}
                boolean done=success;main.post(()->finishLocal(task,done,done?"已保存到原账号资产库":"本地文件未保存，可从原页面重试"));
            });
        }catch(Exception rejected){if(descriptor!=null)try{descriptor.close();}catch(java.io.IOException ignored){}finishLocal(task,false,"本地文件暂不可保存");}
    }
    private final class Import {
        final String id,account,storage,tab; final Client client; final NetworkRoute.Snapshot route;final Lease lease;
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        final java.io.InputStream pipe; final long expected;
        Import(Bundle data, Client client, ParcelFileDescriptor descriptor, Lease lease) throws Exception {
            id=data.getString("operation");account=data.getString("accountId");storage=data.getString("storageId");tab=data.getString("tabId");
            WorkspaceState.profile(id);WorkspaceState.profile(account);WorkspaceState.profile(storage);WorkspaceState.profile(tab);
            this.client=client;this.lease=lease;this.route=lease.route;expected=data.getLong("expectedLength",-1);
            if(expected < -1 || expected > 512L*1024*1024)throw new IllegalArgumentException();
            pipe=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        }
        boolean valid() {
            if(failed.get()||!lease.valid())return false;
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
        EnvironmentLocalExportChecks.initialize(getFilesDir());
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
                int slot=message.getData().getInt("slot",-1);if(slot<0||slot>=30)return true;Client client = new Client(message.replyTo,slot);
                try { token.linkToDeath(client, 0); clients.put(token, client); }
                catch (RemoteException dead) { return true; }
            }
        } else if (message.what == 2) {
            Client client = clients.get(token); if (client == null) return true;
            client.foreground = message.arg1 == 1;
        } else if (message.what == 3) { remove(token); return true; }
        else if(message.what==4){beginImport(clients.get(token),message.getData());return true;}
        else if(message.what==5){Import task=incomingFiles.get(message.getData().getString("operation"));if(task!=null&&task.client.token.equals(token)&&task.completed.getCount()>0){task.success.set(message.getData().getBoolean("success"));if(!task.success.get())task.failed.set(true);task.completed.countDown();}return true;}
        else if(message.what==14||message.what==16){localControl(message.what,clients.get(token),message.getData());return true;}
        else if(message.what==15){beginLocal(clients.get(token),message.getData());return true;}
        else if(message.what>=6&&message.what<=9){leaseMessage(message.what,clients.get(token),message.getData());return true;}
        else return true;
        broadcast(); return true;
    }
    private void remove(IBinder token) {
        Client client = clients.remove(token);
        if (client != null) {client.alive=false;token.unlinkToDeath(client, 0);}
        for(Import task:new ArrayList<>(incomingFiles.values()))if(task.client.token.equals(token))task.cancel();
        for(Lease lease:new ArrayList<>(leases.values()))if(lease.client.token.equals(token))finishLease(lease,true);
        for(LocalExport task:new ArrayList<>(localExports.values()))if(task.client.token.equals(token))finishLocal(task,false,"本地文件保存已中断");
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
            Lease lease=leases.get(data.getString("ticket"));
            if(lease==null||lease.client!=client||lease.claimed||!"request".equals(lease.purpose)||!lease.valid()||!lease.account.equals(data.getString("accountId"))||!lease.storage.equals(data.getString("storageId"))||!lease.tab.equals(data.getString("tabId"))||!lease.uri.equals(data.getString("uri")))throw new IllegalArgumentException();
            Import task=new Import(data,client,descriptor,lease);if(!task.valid())throw new IllegalArgumentException();lease.claimed=true;
            incomingFiles.put(id,task);
            accepted=task;
            String name=AssetLedger.filename(data.getString("name","download")),mime=data.getString("mime","application/octet-stream");
            if(mime.length()>160||mime.indexOf('\n')>=0||mime.indexOf('\r')>=0){task.cancel();incomingFiles.remove(id);finishLease(task.lease,true);throw new IllegalArgumentException();}
            imports.execute(()->{
                boolean ok=false;
                try{
                    java.io.InputStream checked=new java.io.FilterInputStream(task.pipe){
                        long count;boolean ended;
                        private int check(int size)throws java.io.IOException{
                            if(!task.valid())throw new java.io.IOException("文件任务已失效");
                            if(size>0){count+=size;if(count>512L*1024*1024||task.expected>=0&&count>task.expected)throw new java.io.IOException("文件长度无效");}
                            if(size<0&&!ended){ended=true;try{if(!task.completed.await(30,java.util.concurrent.TimeUnit.SECONDS)||!task.success.get()||!task.lease.networkFinished.await(30,java.util.concurrent.TimeUnit.SECONDS)||!task.lease.networkSuccess||task.expected>=0&&count!=task.expected||!task.valid())throw new java.io.IOException("下载未完整完成");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}}
                            return size;
                        }
                        @Override public int read()throws java.io.IOException{int value=in.read();check(value<0?-1:1);return value;}
                        @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{return check(in.read(bytes,offset,length));}
                    };
                    try(java.io.InputStream body=checked){new AssetLedger(getFilesDir(),task.account).importCopy(body,name,mime,"download",null,task::valid);}
                    ok=true;
                }catch(Exception failure){task.cancel();}
                boolean result=ok;
                main.post(()->{incomingFiles.remove(task.id);finishLease(task.lease,!result);importReply(task.client,task.id,result,result?"已保存到当前账号资产库":"文件保存未完成，原文件与已有资产保留");});
            });
        }catch(Exception rejected){if(accepted!=null&&incomingFiles.remove(id)==accepted){accepted.cancel();finishLease(accepted.lease,true);}if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}importReply(client,id,false,"文件暂不可保存，请确认线路与账号后重试");}
    }
    private void broadcast() {
        if (memory == null || network == null) return;
        boolean foreground = false;
        for (Client client : clients.values()) foreground |= client.foreground;
        memory.setEnvironmentForeground(foreground);
        for(Import task:new ArrayList<>(incomingFiles.values()))if(!task.valid())task.cancel();
        for(Lease lease:new ArrayList<>(leases.values()))if(!lease.valid())finishLease(lease,true);
        for(LocalExport task:new ArrayList<>(localExports.values()))if(!task.valid())finishLocal(task,false,"本地文件来源已失效");
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
                catch (RemoteException dead) { remove(client.token); }
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException("Invalid internal route state", impossible); }
    }
    @Override public void onDestroy() {
        if (network != null) network.removePanelObserver(changed);
        getApplication().unregisterActivityLifecycleCallbacks(this);
        for (Client client : new ArrayList<>(clients.values())) {client.alive=false;client.token.unlinkToDeath(client, 0);}
        clients.clear();
        for(Lease lease:new ArrayList<>(leases.values()))finishLease(lease,true);
        for(LocalExport task:new ArrayList<>(localExports.values()))finishLocal(task,false,"本地文件保存已中断");
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
