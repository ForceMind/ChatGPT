package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.mozilla.geckoview.*;

/** Local engine streams are authorized by their original document, never by the current network route. */
final class EnvironmentLocalExports {
    private static final long CLAIM_WINDOW=5000;
    private static final ExecutorService DIGESTS=Executors.newSingleThreadExecutor();
    private final Context context;private final EnvironmentRouteController routes;private final Handler ui=new Handler(Looper.getMainLooper());
    private WeakReference<EnvironmentBrowserActivity> host=new WeakReference<>(null);
    private final Map<EnvironmentBrowserActivity.Entry,Activation> activations=new HashMap<>();
    private final Map<EnvironmentBrowserActivity.Entry,Export> exports=new HashMap<>();
    private static final class Activation {
        final String id,document,token,source;final long epoch,at=SystemClock.elapsedRealtime();boolean consumed;
        Activation(EnvironmentBrowserActivity.Entry e,String id){this.id=id;document=e.documentId;token=e.token;source=e.url;epoch=e.epoch;}
    }
    private static final class Export {
        final String operation=UUID.randomUUID().toString(),cancelKey=UUID.randomUUID().toString(),challenge=UUID.randomUUID().toString();
        final EnvironmentBrowserActivity.Entry owner;final WebResponse response;final String source,document,token,kind;final long epoch;
        Activation activation;String fingerprint,capability,name,mime;long expected=-1;boolean asking,claimed,saving,done;volatile boolean cancelled;StudioSheet sheet;
        Export(EnvironmentBrowserActivity.Entry e,WebResponse response,String kind){owner=e;this.response=response;this.kind=kind;source=e.url;document=e.documentId;token=e.token;epoch=e.epoch;}
    }
    EnvironmentLocalExports(Context context,EnvironmentRouteController routes){this.context=context.getApplicationContext();this.routes=routes;}
    static void diagnostic(String detail){if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.i("StudioLocalExport",detail);}
    void host(EnvironmentBrowserActivity activity){host=new WeakReference<>(activity);}
    void detach(EnvironmentBrowserActivity activity){if(host.get()==activity){for(Export value:new ArrayList<>(exports.values()))cancel(value,"本地文件保存已取消");host.clear();}}
    static String kind(String uri){if(uri==null)return null;if(uri.startsWith("blob:"))return "blob";if(uri.startsWith("data:"))return "data";return null;}
    private boolean owns(EnvironmentBrowserActivity.Entry e){
        if(e.closed||EnvironmentBrowserActivity.SESSIONS.get(e.tabId)!=e)return false;
        try{WorkspaceState.Account a=new StateStore(context.getFilesDir()).load().find(e.accountId);return a.isEnvironment()&&a.environmentSlot==routes.slot&&a.storageId.equals(e.storageId)&&a.tabs.stream().anyMatch(t->t.id.equals(e.tabId));}catch(Exception unavailable){return false;}
    }
    private boolean source(EnvironmentBrowserActivity.Entry e){return owns(e)&&!e.sensitive&&e.port!=null&&e.documentId!=null&&!e.token.isEmpty()&&e.url.equals(e.committedUri)&&EnvironmentUrls.recoverable(e.url)!=null;}
    private boolean valid(Export value){EnvironmentBrowserActivity.Entry e=value.owner;return !value.cancelled&&source(e)&&value.epoch==e.epoch&&value.source.equals(e.url)&&value.document.equals(e.documentId)&&value.token.equals(e.token);}
    private void stage(Export value,String stage,long bytes){EnvironmentLocalExportChecks.stage(stage,value.operation,value.owner.accountId,value.owner.tabId,bytes);}
    private void changed(Export value){EnvironmentBrowserActivity activity=host.get();if(activity!=null)activity.localExportChanged(value.owner);}
    private void notice(String text){EnvironmentBrowserActivity activity=host.get();if(activity!=null)activity.notice(text);}
    void activation(EnvironmentBrowserActivity.Entry e,JSONObject data)throws Exception{
        String id=data.getString("activation"),method=data.getString("method");UUID.fromString(id);
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("activation source="+source(e)+" active="+Boolean.TRUE.equals(data.opt("active"))+" supported="+java.util.Arrays.asList("click","keyboard").contains(method));
        if(!source(e)||!Boolean.TRUE.equals(data.opt("active"))||!java.util.Arrays.asList("click","keyboard").contains(method))return;
        Activation value=new Activation(e,id);activations.put(e,value);ui.postDelayed(()->{if(activations.get(e)==value)activations.remove(e);},CLAIM_WINDOW);
        Export pending=exports.get(e);if(pending!=null&&!pending.claimed&&pending.activation==null){pending.activation=value;stage(pending,"activation",0);claim(pending);}
    }
    void accept(EnvironmentBrowserActivity.Entry e,WebResponse response){
        String localKind=kind(response.uri);
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("response local="+(localKind!=null)+" body="+(response.body!=null)+" source="+source(e)+" pending="+exports.containsKey(e)+" limit="+(exports.size()>=32)+" opaque="+response.uri.startsWith("blob:null/"));
        if(localKind==null||response.body==null||!source(e)||exports.containsKey(e)||exports.size()>=32||response.uri.startsWith("blob:null/")){
            close(response.body);notice("无法确认此本地文件的来源，未保存文件");return;
        }
        Export value=new Export(e,response,localKind);exports.put(e,value);e.activeTransfers++;changed(value);stage(value,"received",0);
        Activation activation=activations.get(e);if(activation!=null&&!activation.consumed){value.activation=activation;stage(value,"activation",0);}
        ui.postDelayed(()->{if(!value.claimed&&!value.done)cancel(value,"本地文件来源确认已过期，请重新导出");},CLAIM_WINDOW);
        DIGESTS.execute(()->{
            String hash=null;try{if(value.cancelled)return;hash=digest(response.uri,()->!value.cancelled);}catch(Exception unavailable){/* A failed digest never grants a local export. */}
            String ready=hash;ui.post(()->{if(value.done)return;if(ready==null||!valid(value)){cancel(value,"本地文件来源已失效");return;}value.fingerprint=ready;claim(value);});
        });
    }
    private void claim(Export value){
        Activation activation=value.activation;
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("claim digest="+(value.fingerprint!=null)+" activation="+(activation!=null)+" asking="+value.asking+" valid="+valid(value));
        if(value.done||value.asking||value.fingerprint==null||activation==null)return;
        if(!valid(value)||activation.consumed||SystemClock.elapsedRealtime()-activation.at>CLAIM_WINDOW||activation.epoch!=value.epoch||!activation.document.equals(value.document)||!activation.token.equals(value.token)||!activation.source.equals(value.source)){cancel(value,"本地文件来源已失效");return;}
        activation.consumed=true;value.asking=true;
        try{value.owner.port.postMessage(new JSONObject().put("type","local-export-challenge").put("request",value.challenge).put("activation",activation.id).put("token",value.token).put("epoch",value.epoch));}
        catch(Exception unavailable){cancel(value,"本地文件来源无法确认");}
    }
    void proof(EnvironmentBrowserActivity.Entry e,JSONObject data)throws Exception{
        Export value=exports.get(e);if(value==null||value.done||!value.asking||value.claimed||!value.challenge.equals(data.getString("request"))||value.activation==null||!value.activation.id.equals(data.getString("activation")))return;
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("proof valid="+valid(value)+" active="+Boolean.TRUE.equals(data.opt("active"))+" expired="+(SystemClock.elapsedRealtime()-value.activation.at>CLAIM_WINDOW)+" anchored="+Boolean.TRUE.equals(data.opt("hasUriDigest"))+" detached="+Boolean.TRUE.equals(data.opt("detached")));
        if(!valid(value)||!(data.opt("active") instanceof Boolean)||!data.getBoolean("active")||SystemClock.elapsedRealtime()-value.activation.at>CLAIM_WINDOW){stage(value,"proof-rejected",0);cancel(value,"本地文件来源确认已过期");return;}
        Object anchored=data.opt("hasUriDigest");if(!(anchored instanceof Boolean)){cancel(value,"本地文件来源无法确认");return;}
        if((Boolean)anchored){if(!value.kind.equals(data.getString("kind"))||!value.fingerprint.equals(data.getString("uriHash"))){stage(value,"proof-uri-mismatch",0);cancel(value,"本地文件来源不匹配");return;}}
        else if(!Boolean.TRUE.equals(data.opt("detached"))){cancel(value,"本地文件来源无法确认");return;}
        value.claimed=true;stage(value,"claimed",0);
        String type=value.response.headers.get("Content-Type");value.mime=type==null?"application/octet-stream":type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(!value.mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))value.mime="application/octet-stream";
        String suggested=android.webkit.URLUtil.guessFileName("",value.response.headers.get("Content-Disposition"),value.mime);if(suggested.startsWith("blob:")||suggested.startsWith("data:"))suggested=android.webkit.URLUtil.guessFileName("",null,value.mime);value.name=AssetLedger.filename(suggested);
        try{String length=value.response.headers.get("Content-Length");if(length!=null)value.expected=Long.parseLong(length);}catch(NumberFormatException unavailable){value.expected=-1;}
        if(value.expected < -1||value.expected>AssetLedger.MAX_FILE){cancel(value,"本地文件过大或长度无效");return;}
        JSONObject request=new JSONObject().put("operation",value.operation).put("cancelKey",value.cancelKey).put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId).put("source",value.source).put("document",value.document).put("epoch",value.epoch).put("kind",value.kind).put("fingerprint",value.fingerprint);
        routes.localBroker(14,request,result->{
            if(value.done)return;
            if(!result.optBoolean("ok")||!result.optBoolean("localExport")||!value.operation.equals(result.optString("operation"))||!valid(value)){cancel(value,"本地文件来源已失效");return;}
            value.capability=result.optString("capability");if(!value.capability.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")){cancel(value,"本地文件暂不可保存");return;}
            EnvironmentBrowserActivity activity=host.get();if(activity==null){cancel(value,"本地文件保存已取消");return;}
            try{value.sheet=activity.confirmLocalExport(e,value.name,()->save(value),()->cancel(value,"本地文件保存已取消"));if(value.sheet==null&&!value.done)cancel(value,"本地文件保存已取消");}catch(RuntimeException unavailable){cancel(value,"本地文件暂不可保存");}
        });
    }
    private void save(Export value){
        if(value.done||value.saving)return;if(!valid(value)){cancel(value,"本地文件来源已失效");return;}
        value.saving=true;if(value.sheet!=null)value.sheet.dismiss();stage(value,"confirm",0);
        routes.importLocal(value.operation,value.capability,value.cancelKey,value.name,value.mime,value.response.body,value.expected,()->valid(value),(success,message)->finish(value,success,message));
    }
    void cancel(EnvironmentBrowserActivity.Entry e){Export value=exports.get(e);if(value!=null)cancel(value,"本地文件保存已取消");}
    void invalidated(EnvironmentBrowserActivity.Entry e){activations.remove(e);cancel(e);}
    void brokerEnded(String operation,String message){for(Export value:new ArrayList<>(exports.values()))if(value.operation.equals(operation))finish(value,false,message);}
    void brokerLost(){for(Export value:new ArrayList<>(exports.values()))cancel(value,"本地文件保存已中断");}
    private void cancel(Export value,String message){if(value.done)return;value.cancelled=true;routes.cancelLocal(value.operation,value.cancelKey,value.capability);finish(value,false,message);}
    private void finish(Export value,boolean success,String message){
        if(value.done)return;value.done=true;if(!success)value.cancelled=true;exports.remove(value.owner);close(value.response.body);if(value.sheet!=null)value.sheet.dismiss();value.owner.activeTransfers=Math.max(0,value.owner.activeTransfers-1);changed(value);stage(value,success?"complete":"cancel",0);if(message!=null)notice(message);
    }
    private static void close(InputStream input){try{if(input!=null)input.close();}catch(IOException ignored){}}
    private static String digest(String value,java.util.function.BooleanSupplier available)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(Writer writer=new OutputStreamWriter(new DigestOutputStream(new OutputStream(){public void write(int ignored){}@Override public void write(byte[] data,int offset,int length){}},digest),StandardCharsets.UTF_8)){
            for(int offset=0;offset<value.length();offset+=4096){if(!available.getAsBoolean())throw new IOException("Local digest cancelled");writer.write(value,offset,Math.min(4096,value.length()-offset));}
        }
        StringBuilder result=new StringBuilder();for(byte item:digest.digest())result.append(String.format(Locale.ROOT,"%02x",item&255));return result.toString();
    }
}
