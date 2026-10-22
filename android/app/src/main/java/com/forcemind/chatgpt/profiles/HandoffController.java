package com.forcemind.chatgpt.profiles;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.*;

/** Drives the normal webpage UI. No private ChatGPT endpoint or JS/native bridge. */
final class HandoffController {
    static final class Page {final String tab;final WebView web;Page(String tab,WebView web){this.tab=tab;this.web=web;}}
    interface Host {Page open(JSONObject job)throws Exception;boolean valid(JSONObject job,Page page);long epoch(Page page);void changed(JSONObject job);void failed(String message);}
    private final HandoffRuntime runtime;private final Host host;private final JSONObject payload;private JSONObject job;private final AtomicBoolean control,grantLive=new AtomicBoolean();
    private final String id,key,token,mimeMarker,prompt;private final List<String> names=new ArrayList<>();
    private Page page;private long epoch=-1,deadline;private boolean closed,chooserUsed,inputDispatch,sendIssued;private int stable,beforeMessages;
    HandoffController(HandoffRuntime runtime,Host host,JSONObject job,JSONObject payload,AtomicBoolean control){
        this.runtime=runtime;this.host=host;this.job=job;this.payload=payload;this.control=control;id=job.optString("id");token=payload.optString("marker");key="__profiles_"+token.replace("-","");mimeMarker="application/x-profiles-"+token.replace("-","");prompt=payload.optString("prompt");JSONArray files=job.optJSONArray("files");for(int i=0;i<files.length();i++)names.add(files.optJSONObject(i).optString("uploadName"));
    }
    String id(){return id;}
    boolean owns(WebView web){return !closed&&page!=null&&page.web==web;}
    void begin(){try{page=host.open(job);page.web.setOnTouchListener((v,event)->!inputDispatch);host.changed(job);runtime.io.execute(()->{try{runtime.store.bindTab(id,page.tab);runtime.handler.post(()->{if(!closed){deadline=SystemClock.elapsedRealtime()+45000;install();}});}catch(Exception e){fail("无法记录目标标签页，已停止发送");}});}catch(Exception e){fail("无法在目标账号新建对话，请检查标签页数量和登录空间");}}
    private boolean valid(){return !closed&&control.get()&&page!=null&&host.valid(job,page)&&(epoch<0||host.epoch(page)==epoch);}
    private boolean origin(){String url=page==null?null:page.web.getUrl();return url!=null&&NavigationPolicy.https(url)&&"chatgpt.com".equalsIgnoreCase(Uri.parse(url).getHost());}
    private void later(Runnable work){if(!closed)runtime.handler.postDelayed(()->{if(!closed)work.run();},300);}
    private JSONObject parsed(String raw)throws Exception{if(raw==null||raw.length()>600000)throw new IOException("网页返回无效");Object value=new JSONTokener(raw).nextValue();return value instanceof JSONObject?(JSONObject)value:new JSONObject(String.valueOf(value));}
    private void install(){
        if(!valid()){fail("目标账号、页签或应用状态已改变");return;}if(SystemClock.elapsedRealtime()>deadline){fail("未找到 ChatGPT 输入框，请先登录或检查网页兼容性");return;}
        if(!origin()||page.web.getProgress()<100){later(this::install);return;}
        try{
            String source;try(InputStream in=runtime.context.getAssets().open("handoff-page.js")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);source=new String(out.toByteArray(),StandardCharsets.UTF_8);}
            JSONObject config=new JSONObject().put("origin","https://chatgpt.com").put("token",token).put("key",key).put("mimeMarker",mimeMarker).put("names",new JSONArray(names));boolean images=!names.isEmpty();JSONArray files=job.getJSONArray("files");for(int i=0;i<files.length();i++)images&=files.getJSONObject(i).getString("uploadMime").startsWith("image/");config.put("allImages",images);long sentEpoch=host.epoch(page);
            page.web.evaluateJavascript(source.replace("__CONFIG__",config.toString()),raw->{if(closed)return;try{if(!valid()||host.epoch(page)!=sentEpoch)throw new IOException();JSONObject result=parsed(raw);if(result.has("error"))throw new IOException();epoch=sentEpoch;ready();}catch(Exception e){fail("目标网页在准备时发生变化，未发送消息");}});
        }catch(Exception e){fail("无法载入本机网页处理组件，未发送消息");}
    }
    private void evaluate(String command,Object argument,Consumer<JSONObject> next){
        if(!valid()||!origin()){fail("目标账号、页签或网页已改变");return;}
        String args=argument==null?"":argument.toString();String script="(function(){try{var a=window["+JSONObject.quote(key)+"];if(window!==top||location.origin!=='https://chatgpt.com'||!a||a.token!=="+JSONObject.quote(token)+")return JSON.stringify({error:'document'});return JSON.stringify(a."+command+"("+args+"));}catch(e){return JSON.stringify({error:'page'});}})()";
        page.web.evaluateJavascript(script,raw->{if(closed)return;try{if(!valid()||!origin())throw new IOException();JSONObject value=parsed(raw);if(value.has("error")){fail("网页控件或内容已改变，已停止自动发送，请检查目标标签页");return;}next.accept(value);}catch(Exception e){fail("无法确认目标网页状态，已停止自动发送");}});
    }
    private void ready(){evaluate("state",false,state->{if(state.optBoolean("login")){fail("目标账号尚未登录，请登录后重新发起任务");return;}if(!state.optBoolean("composer")){if(SystemClock.elapsedRealtime()>deadline)fail("未找到 ChatGPT 输入框，未发送消息");else later(this::ready);return;}if(!state.optBoolean("fresh")||!normal(state.optString("text")).isEmpty()||state.optInt("messages")!=0||state.optBoolean("generating")){fail("目标页面已有内容，为避免覆盖或重复发送已停止");return;}beforeMessages=state.optInt("messages");if(names.isEmpty())draft();else update("uploading","正在上传附件，请等待网页确认",()->{deadline=SystemClock.elapsedRealtime()+9000;upload();});});}
    private void update(String status,String message,Runnable next){runtime.io.execute(()->{try{JSONObject result=runtime.store.transition(id,status,message);runtime.handler.post(()->{if(closed)return;job=result;host.changed(job);if(!valid()){fail("目标账号或页签已改变");return;}next.run();});}catch(Exception e){fail("任务记录写入失败，已停止自动发送");}});}
    private void upload(){
        if(SystemClock.elapsedRealtime()>deadline){fail("网页未提供可用的文件选择控件，未发送消息");return;}
        evaluate("upload",null,result->{if(result.optBoolean("wait")){later(this::upload);return;}try{inputDispatch=true;WebInputs.tap(page.web,result.getDouble("x"),result.getDouble("y"));deadline=SystemClock.elapsedRealtime()+90000;attachments();}catch(Exception e){fail("未能操作网页文件选择器，未发送消息");}finally{inputDispatch=false;}});
    }
    boolean chooser(WebView view,ValueCallback<Uri[]> callback,WebChromeClient.FileChooserParams params){
        if(!owns(view))return false;
        if(!valid()||!origin()||!job.optString("status").equals("uploading")||chooserUsed||!Arrays.asList(params.getAcceptTypes()).contains(mimeMarker)||params.getMode()!=WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE){callback.onReceiveValue(null);return true;}
        try{chooserUsed=true;grantLive.set(true);Uri[] files=HandoffUploads.grant(runtime.context,job,payload,grantLive::get);callback.onReceiveValue(files);}catch(Exception e){callback.onReceiveValue(null);fail("附件授权或暂存文件不可用，已停止发送");}return true;
    }
    private boolean filesReady(JSONObject state){if(state.optBoolean("busy")||state.optBoolean("error")||state.optInt("removers")!=names.size())return false;JSONArray actual=state.optJSONArray("attachments");for(String name:names){boolean found=false;if(actual!=null)for(int i=0;i<actual.length();i++)if(actual.optString(i).contains(name))found=true;if(!found)return false;}return true;}
    private void attachments(){if(SystemClock.elapsedRealtime()>deadline){fail("未能确认全部附件上传完成，请检查目标标签页");return;}evaluate("state",false,state->{if(state.optBoolean("error")){fail("网页提示附件错误，未发送消息");return;}stable=chooserUsed&&filesReady(state)?stable+1:0;if(stable>=3)evaluate("restore",null,ignored->draft());else later(this::attachments);});}
    private void draft(){update("drafting","附件已确认，正在准备处理要求",()->evaluate("focus",null,ignored->{if(!WebInputs.insert(page.web,prompt)){fail("未能写入网页输入框，未发送消息");return;}deadline=SystemClock.elapsedRealtime()+15000;draftReady();}));}
    private void draftReady(){if(SystemClock.elapsedRealtime()>deadline){fail("网页未完整接收任务要求，未发送消息");return;}evaluate("state",false,state->{if(state.optBoolean("error")){fail("网页提示附件或输入错误，未发送消息");return;}if(normal(state.optString("text")).equals(normal(prompt))&&state.optBoolean("send")&&filesReady(state)){send();}else later(this::draftReady);});}
    private void send(){if(sendIssued)return;sendIssued=true;runtime.io.execute(()->{try{HandoffRequest.accounts(runtime.context.getFilesDir(),payload.getJSONObject("request"));AssetLedger source=new AssetLedger(runtime.context.getFilesDir(),job.getString("sourceAccountId"));JSONArray files=job.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i),current=source.get(file.getString("assetId"));if(current.getBoolean("trashed")||!current.getString("status").equals("ready")||!current.getString("sha256").equals(file.getString("sha256")))throw new IOException();}if(!control.get())throw new IOException();runtime.store.beginSend(id);JSONObject result=runtime.store.get(id);
            runtime.handler.post(()->{if(closed)return;job=result;host.changed(job);if(!valid()){fail("目标账号或页签已改变");return;}try{evaluate("send",new JSONObject().put("prompt",prompt),ignored->{deadline=SystemClock.elapsedRealtime()+25000;acknowledge();});}catch(Exception e){fail("发送结果待确认，请检查目标对话");}});
        }catch(Exception e){fail("文件或任务记录已改变，已停止自动发送");}});}
    private void acknowledge(){if(SystemClock.elapsedRealtime()>deadline){fail("未能确认网页收到消息，不会自动重发");return;}evaluate("state",true,state->{String needle=compact(prompt);needle=needle.substring(0,Math.min(160,needle.length()));if(state.optInt("messages")>beforeMessages&&compact(state.optString("last")).contains(needle)&&normal(state.optString("text")).isEmpty()){
            String url=page.web.getUrl();runtime.io.execute(()->{try{runtime.store.submitted(id,url);finish(false,null);}catch(Exception e){fail("发送结果待确认，请检查目标对话");}});
        }else later(this::acknowledge);});}
    void cancel(String reason){stop(reason,true);}
    private void fail(String reason){stop(reason,false);}
    private void stop(String reason,boolean cancelled){runtime.handler.post(()->{if(closed)return;closed=true;control.set(false);dispose();runtime.io.execute(()->{try{runtime.store.failure(id,reason,cancelled);}catch(Exception ignored){}finish(true,reason);});});}
    private void dispose(){grantLive.set(false);HandoffUploads.revoke(id);if(page!=null){page.web.setOnTouchListener(null);try{page.web.evaluateJavascript("(function(){var a=window["+JSONObject.quote(key)+"];if(a&&a.token==="+JSONObject.quote(token)+")a.cleanup();})()",null);}catch(Exception ignored){}}}
    private void finish(boolean failure,String reason){grantLive.set(false);HandoffUploads.revoke(id);runtime.handler.post(()->{if(!closed){closed=true;dispose();}});try{HandoffFiles.cleanup(runtime.context.getFilesDir(),id);}catch(Exception ignored){}try{runtime.hostUpdate(host,runtime.store.get(id));}catch(Exception ignored){if(reason!=null)runtime.handler.post(()->host.failed(reason));}runtime.release(id);}
    private static String normal(String value){return value.replace("\r\n","\n").replace('\u00a0',' ').trim();}
    private static String compact(String value){return normal(value).replaceAll("\\s+"," ");}
}
