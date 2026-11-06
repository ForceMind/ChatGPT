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
        final EnvironmentBrowserActivity.Entry owner;final WebResponse response;final InputStream body;final WebExtension.Port sourcePort;final String source,document,token,kind;final long epoch;
        String streamRequest,readKey;LocalInput stream;
        Activation activation;String fingerprint,capability,name,mime;long expected=-1;boolean asking,claimed,saving,done;volatile boolean cancelled;StudioSheet sheet;
        Export(EnvironmentBrowserActivity.Entry e,WebResponse response,String kind){owner=e;this.response=response;body=response.body;sourcePort=e.port;this.kind=kind;source=e.url;document=e.documentId;token=e.token;epoch=e.epoch;}
        Export(EnvironmentBrowserActivity.Entry e,String kind,LocalInput stream){owner=e;response=null;body=stream;this.stream=stream;sourcePort=e.port;this.kind=kind;source=e.url;document=e.documentId;token=e.token;epoch=e.epoch;}
    }
    /** One packet in flight. UI only offers; the existing file-copy worker consumes. */
    private static final class LocalInput extends InputStream {
        private byte[] packet;private int offset;private long next=1,total;private final long limit;private boolean eof,closed;private Runnable consumed;
        LocalInput(long length){limit=length<0?AssetLedger.MAX_FILE:length;}
        synchronized boolean offer(long sequence,byte[] bytes,Runnable ack){if(closed||eof||packet!=null||sequence!=next||bytes.length==0||bytes.length>16384||total>limit-bytes.length)return false;packet=bytes;offset=0;consumed=ack;total+=bytes.length;next++;notifyAll();return true;}
        synchronized boolean end(long sequence,long length){if(closed||eof||packet!=null||sequence!=next||length!=total)return false;eof=true;notifyAll();return true;}
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] output,int start,int length)throws IOException{
            if(start<0||length<0||start>output.length-length)throw new IndexOutOfBoundsException();if(length==0)return 0;Runnable ack=null;int count;
            synchronized(this){long until=SystemClock.elapsedRealtime()+30000;while(packet==null&&!eof&&!closed){long remaining=until-SystemClock.elapsedRealtime();if(remaining<=0)throw new IOException("Local export stream timed out");try{wait(remaining);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Local export cancelled");}}if(closed)throw new IOException("Local export cancelled");if(packet==null)return -1;count=Math.min(length,packet.length-offset);System.arraycopy(packet,offset,output,start,count);offset+=count;if(offset==packet.length){packet=null;offset=0;ack=consumed;consumed=null;}}
            if(ack!=null)ack.run();return count;
        }
        @Override public synchronized void close(){closed=true;packet=null;consumed=null;notifyAll();}
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
        String messageType=data.optString("type");
        if("local-export-intent".equals(messageType)){try{intent(e,data);}catch(Exception invalid){rejectStream(e,data.optString("request"));notice("此本地文件的信息无效，未保存文件");}return;}
        if(java.util.Arrays.asList("local-export-chunk","local-export-end","local-export-error").contains(messageType)){try{streamMessage(e,data);}catch(Exception invalid){Export value=exports.get(e);if(value!=null&&value.streamRequest!=null&&value.streamRequest.equals(data.optString("request")))cancel(value,"本地文件数据无效，未保存文件");}return;}
        Export value=exports.get(e);if(value==null||value.done||!value.asking||value.claimed||!value.challenge.equals(data.getString("request"))||value.activation==null||!value.activation.id.equals(data.getString("activation")))return;
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("proof valid="+valid(value)+" active="+Boolean.TRUE.equals(data.opt("active"))+" expired="+(SystemClock.elapsedRealtime()-value.activation.at>CLAIM_WINDOW)+" anchored="+Boolean.TRUE.equals(data.opt("hasUriDigest"))+" detached="+Boolean.TRUE.equals(data.opt("detached")));
        if(!valid(value)||!(data.opt("active") instanceof Boolean)||!data.getBoolean("active")||SystemClock.elapsedRealtime()-value.activation.at>CLAIM_WINDOW){stage(value,"proof-rejected",0);cancel(value,"本地文件来源确认已过期");return;}
        Object anchored=data.opt("hasUriDigest");if(!(anchored instanceof Boolean)){cancel(value,"本地文件来源无法确认");return;}
        if((Boolean)anchored){if(!value.kind.equals(data.getString("kind"))||!value.fingerprint.equals(data.getString("uriHash"))){stage(value,"proof-uri-mismatch",0);cancel(value,"本地文件来源不匹配");return;}}
        else if(!Boolean.TRUE.equals(data.opt("detached"))){cancel(value,"本地文件来源无法确认");return;}
        value.claimed=true;stage(value,"claimed",0);
        if(value.response!=null){String type=value.response.headers.get("Content-Type");value.mime=type==null?"application/octet-stream":type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if(!value.mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))value.mime="application/octet-stream";
            String suggested=android.webkit.URLUtil.guessFileName("",value.response.headers.get("Content-Disposition"),value.mime);if(suggested.startsWith("blob:")||suggested.startsWith("data:"))suggested=android.webkit.URLUtil.guessFileName("",null,value.mime);value.name=AssetLedger.filename(suggested);
            try{String length=value.response.headers.get("Content-Length");if(length!=null)value.expected=Long.parseLong(length);}catch(NumberFormatException unavailable){value.expected=-1;}}
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
        routes.importLocal(value.operation,value.capability,value.cancelKey,value.name,value.mime,value.body,value.expected,()->valid(value),(success,message)->finish(value,success,message));
        if(value.stream!=null&&!value.done){value.readKey=UUID.randomUUID().toString();streamPost(value,"local-export-read",0);}
    }
    private void intent(EnvironmentBrowserActivity.Entry e,JSONObject data)throws Exception {
        String request=data.getString("request"),kind=data.getString("kind"),fingerprint=data.getString("uriHash"),activationId=data.getString("activation");UUID.fromString(request);
        Activation activation=activations.get(e);String mime=data.getString("mime"),name=data.getString("name");Object size=data.get("length");long length=data.getLong("length");
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("intent source="+source(e)+" activation="+(activation!=null)+" pending="+exports.containsKey(e)+" local="+java.util.Arrays.asList("blob","data").contains(kind));
        if(!source(e)||exports.containsKey(e)||exports.size()>=32||activation==null||activation.consumed||!activation.id.equals(activationId)||SystemClock.elapsedRealtime()-activation.at>CLAIM_WINDOW||!java.util.Arrays.asList("blob","data").contains(kind)||!fingerprint.matches("[a-f0-9]{64}")||!(size instanceof Number)||data.getDouble("length")!=length||length < -1||length>AssetLedger.MAX_FILE||name.length()>512||!mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")){
            rejectStream(e,request);notice("无法确认此本地文件的来源，未保存文件");return;
        }
        LocalInput stream=new LocalInput(length);Export value=new Export(e,kind,stream);value.streamRequest=request;value.fingerprint=fingerprint;value.activation=activation;value.mime=mime;value.name=AssetLedger.filename(name.isEmpty()?android.webkit.URLUtil.guessFileName("",null,mime):name);value.expected=length;exports.put(e,value);e.activeTransfers++;changed(value);stage(value,"received",0);stage(value,"activation",0);
        ui.postDelayed(()->{if(!value.claimed&&!value.done)cancel(value,"本地文件来源确认已过期，请重新导出");},CLAIM_WINDOW);claim(value);
    }
    private void rejectStream(EnvironmentBrowserActivity.Entry e,String request){try{if(e.port!=null)e.port.postMessage(new JSONObject().put("type","local-export-cancel").put("request",request).put("token",e.token).put("epoch",e.epoch));}catch(Exception unavailable){/* Document teardown also cancels its reader. */}}
    private void streamPost(Export value,String type,long sequence){
        try{value.sourcePort.postMessage(new JSONObject().put("type",type).put("request",value.streamRequest).put("readKey",value.readKey).put("chunkSequence",sequence).put("token",value.token).put("epoch",value.epoch));}
        catch(Exception unavailable){cancel(value,"本地文件来源连接已中断");}
    }
    private void streamMessage(EnvironmentBrowserActivity.Entry e,JSONObject data)throws Exception {
        Export value=exports.get(e);String type=data.getString("type");
        if(value==null){if("local-export-error".equals(type)&&source(e)){if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)diagnostic("stream-prepare failed=true");notice("此本地文件无法读取，未保存文件");}return;}
        if(value.done||value.stream==null||!value.streamRequest.equals(data.getString("request")))return;
        if(!valid(value)){cancel(value,"本地文件来源已失效");return;}
        if("local-export-error".equals(type)){cancel(value,"此本地文件未能完整读取，未保存文件");return;}
        if(!value.saving||value.readKey==null||!value.readKey.equals(data.getString("readKey"))){cancel(value,"本地文件读取授权无效");return;}
        Object raw=data.get("chunkSequence");long sequence=data.getLong("chunkSequence");if(!(raw instanceof Number)||data.getDouble("chunkSequence")!=sequence){cancel(value,"本地文件读取次序无效");return;}
        boolean accepted;
        if("local-export-end".equals(type)){Object count=data.get("length");long length=data.getLong("length");accepted=count instanceof Number&&data.getDouble("length")==length&&length>=0&&length<=AssetLedger.MAX_FILE&&(value.expected<0||length==value.expected)&&value.stream.end(sequence,length);}
        else {String encoded=data.getString("chunk");if(encoded.length()>21848||!encoded.matches("[A-Za-z0-9+/]*={0,2}")){cancel(value,"本地文件数据无效");return;}byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);accepted=value.stream.offer(sequence,bytes,()->ui.post(()->{if(!value.done&&valid(value))streamPost(value,"local-export-credit",sequence);else if(!value.done)cancel(value,"本地文件来源已失效");}));}
        if(!accepted)cancel(value,"本地文件数据次序或长度无效，未保存文件");
    }
    void cancel(EnvironmentBrowserActivity.Entry e){Export value=exports.get(e);if(value!=null)cancel(value,"本地文件保存已取消");}
    void invalidated(EnvironmentBrowserActivity.Entry e){activations.remove(e);cancel(e);}
    void brokerEnded(String operation,String message){for(Export value:new ArrayList<>(exports.values()))if(value.operation.equals(operation))finish(value,false,message);}
    void brokerLost(){for(Export value:new ArrayList<>(exports.values()))cancel(value,"本地文件保存已中断");}
    private void cancel(Export value,String message){if(value.done)return;value.cancelled=true;routes.cancelLocal(value.operation,value.cancelKey,value.capability);finish(value,false,message);}
    private void finish(Export value,boolean success,String message){
        if(value.done)return;value.done=true;if(!success)value.cancelled=true;exports.remove(value.owner);close(value.body);if(value.stream!=null)streamPost(value,"local-export-cancel",0);if(value.sheet!=null)value.sheet.dismiss();value.owner.activeTransfers=Math.max(0,value.owner.activeTransfers-1);changed(value);stage(value,success?"complete":"cancel",0);if(message!=null)notice(message);
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
