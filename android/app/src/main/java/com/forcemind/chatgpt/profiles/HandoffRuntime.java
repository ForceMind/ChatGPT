package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.*;

final class HandoffRuntime {
    private static HandoffRuntime instance;
    static synchronized HandoffRuntime get(Context context)throws Exception{if(instance==null)instance=new HandoffRuntime(context.getApplicationContext());return instance;}
    final Context context;
    final HandoffStore store;
    final ExecutorService io=Executors.newSingleThreadExecutor();
    final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,AtomicBoolean> controls=new HashMap<>();
    private String activeId;
    private volatile HandoffController controller;
    private HandoffRuntime(Context context)throws Exception{
        this.context=context;store=new HandoffStore(context.getFilesDir(),DevicePayloadKeys.cipher(context));
        for(String id:store.recover())try{HandoffFiles.cleanup(context.getFilesDir(),id);}catch(Exception ignored){}
    }
    JSONObject prepare(JSONObject raw,SkillCatalog catalog,BooleanSupplier screenAlive)throws Exception{
        JSONObject request=HandoffRequest.normalize(raw);String id=request.getString("requestId");AtomicBoolean control=new AtomicBoolean(true);
        synchronized(this){if(activeId!=null||!controls.isEmpty())throw new IOException("已有附件任务正在处理，请稍后再试");controls.put(id,control);}
        try{
            HandoffRequest.accounts(context.getFilesDir(),request);WorkspaceState state=WorkspaceFiles.read(context.getFilesDir());WorkspaceState.Account source=state.find(request.getString("sourceAccountId")),target=state.find(request.getString("targetAccountId"));
            AssetLedger ledger=new AssetLedger(context.getFilesDir(),source.id);JSONArray files=HandoffFiles.snapshot(ledger,request.getJSONArray("assetIds"));JSONObject task=catalog.task(request.getString("skillId"),request.optString("agentId",""));String label=task.getString("label"),skillPrompt=task.getString("prompt");
            StringBuilder prompt=new StringBuilder("请在本次新对话中处理以下任务。输出语言：").append(request.getString("language")).append("。\n\n");
            if(!skillPrompt.isEmpty())prompt.append(skillPrompt).append("\n\n");if(!request.getString("instructions").trim().isEmpty())prompt.append("补充要求：\n").append(request.getString("instructions")).append("\n\n");
            if(files.length()>0){prompt.append("附件清单：\n");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);prompt.append("- ").append(file.getString("uploadName")).append("（").append(file.getString("uploadMime")).append("，").append(file.getLong("size")).append(" 字节）\n");if(!file.getString("name").equals(file.getString("uploadName")))prompt.append("  原名：").append(file.getString("name")).append("。内容保持不变，HTML/SVG/代码按文本附件处理。\n");}}
            JSONObject job=store.create(request,files,label(source),label(target),label,prompt.toString());String status=job.getString("status");
            if(status.equals("preparing")){
                HandoffFiles.stage(context.getFilesDir(),id,ledger,job.getJSONArray("files"),()->control.get()&&screenAlive.getAsBoolean());HandoffRequest.accounts(context.getFilesDir(),request);if(!control.get()||!screenAlive.getAsBoolean())throw new InterruptedIOException("任务已取消");store.transition(id,"ready","文件已校验，等待打开目标账号");
            }else if(!status.equals("ready"))throw new IOException("该任务已经执行或结束，请查看任务记录");
            return new JSONObject().put("id",id).put("token",store.payload(id).getString("launchToken"));
        }catch(Exception e){try{store.failure(id,e.getMessage()==null?"任务准备失败，原件保留":e.getMessage(),!control.get()||!screenAlive.getAsBoolean());}catch(Exception ignored){}try{HandoffFiles.cleanup(context.getFilesDir(),id);}catch(Exception ignored){}throw e;}
        finally{synchronized(this){controls.remove(id);}}
    }
    private static String label(WorkspaceState.Account account){return account.name+(account.email.isEmpty()?"":" · "+account.email);}
    void start(HandoffController.Host host,String id,String token)throws Exception{
        WorkspaceState.profile(id);WorkspaceState.profile(token);AtomicBoolean control=new AtomicBoolean(true);
        synchronized(this){if(activeId!=null)throw new IOException("已有网页任务正在运行，请先查看任务记录");activeId=id;controls.put(id,control);}
        io.execute(()->{
            boolean claimed=false;
            try{JSONObject payload=store.payload(id);JSONObject job=store.claim(id,token);claimed=true;HandoffRequest.accounts(context.getFilesDir(),payload.getJSONObject("request"));
                handler.post(()->{if(!control.get()){finishStartFailure(host,id,"任务已取消",true);return;}controller=new HandoffController(this,host,job,payload,control);controller.begin();});
            }catch(Exception e){if(claimed)try{store.failure(id,"任务账号或文件不可用，请重新确认",!control.get());}catch(Exception ignored){}release(id);handler.post(()->host.failed("任务授权无效、已经执行或账号已改变；不会自动重发"));}
        });
    }
    private void finishStartFailure(HandoffController.Host host,String id,String reason,boolean cancelled){io.execute(()->{try{store.failure(id,reason,cancelled);hostUpdate(host,store.get(id));}catch(Exception ignored){}release(id);});}
    void hostUpdate(HandoffController.Host host,JSONObject job){handler.post(()->host.changed(job));}
    synchronized void release(String id){if(id.equals(activeId)){activeId=null;controller=null;}controls.remove(id);}
    HandoffController controller(){return controller;}
    void cancel(String id,String account)throws Exception{JSONObject job=store.get(id);if(!job.getString("sourceAccountId").equals(account)&&!job.getString("targetAccountId").equals(account))throw new IOException("任务不属于当前账号");AtomicBoolean control;synchronized(this){control=controls.get(id);}if(control!=null)control.set(false);HandoffController current=controller;if(current!=null&&current.id().equals(id))handler.post(()->current.cancel("已停止自动发送；网页可能已形成附件或草稿，请手动检查"));else{store.failure(id,"任务已取消，原件保留",true);try{HandoffFiles.cleanup(context.getFilesDir(),id);}catch(Exception ignored){}}}
}
