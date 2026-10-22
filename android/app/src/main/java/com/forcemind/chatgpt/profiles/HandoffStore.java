package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Atomic task journal. Prompts and capabilities only appear in sealed payloads. */
final class HandoffStore {
    private static final Object LOCK=new Object();
    private static final Set<String> ACTIVE=new HashSet<>(Arrays.asList("preparing","ready","opening","uploading","drafting","sending"));
    private static final Map<String,List<String>> NEXT=new HashMap<>();
    static{
        NEXT.put("preparing",Arrays.asList("ready","failed","cancelled","interrupted"));NEXT.put("ready",Arrays.asList("opening","failed","cancelled","interrupted"));NEXT.put("opening",Arrays.asList("uploading","drafting","failed","cancelled","interrupted"));NEXT.put("uploading",Arrays.asList("drafting","failed","cancelled","interrupted"));NEXT.put("drafting",Arrays.asList("sending","failed","cancelled","interrupted"));NEXT.put("sending",Arrays.asList("submitted","uncertain"));
        for(String terminal:new String[]{"submitted","failed","cancelled","interrupted","uncertain"})NEXT.put(terminal,Collections.emptyList());
    }
    private final File root,index,backup,payloads,fences;
    private final EncryptedPayloads cipher;
    HandoffStore(File files,EncryptedPayloads cipher)throws Exception{
        this.cipher=cipher;root=HandoffFiles.root(files,true);index=new File(root,"jobs.json");backup=new File(root,"jobs.backup.json");payloads=new File(root,"payloads");fences=new File(root,"sent");HandoffFiles.directory(payloads,true);HandoffFiles.directory(fences,true);
        synchronized(LOCK){read();}
    }
    private static JSONObject clone(JSONObject value)throws JSONException{return new JSONObject(value.toString());}
    private static long integer(JSONObject value,String key)throws Exception{Object raw=value.get(key);if(!(raw instanceof Number)||value.getDouble(key)!=value.getLong(key)||value.getLong(key)<0)throw new IOException("任务数字字段无效");return value.getLong(key);}
    private JSONObject validate(byte[] bytes)throws Exception{
        if(bytes.length>25*1024*1024)throw new IOException("任务记录超过大小限制");JSONObject data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(integer(data,"schema")!=1)throw new IOException("任务记录版本无效");JSONArray jobs=data.getJSONArray("jobs");if(jobs.length()>1000)throw new IOException("任务记录超过 1000 条");Set<String> ids=new HashSet<>();
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.getJSONObject(i);String id=job.getString("id");WorkspaceState.profile(id);if(!ids.add(id)||!NEXT.containsKey(job.getString("status")))throw new IOException("任务状态无效");
            for(String key:new String[]{"sourceAccountId","targetAccountId","sourceStorageId","targetStorageId"})WorkspaceState.profile(job.getString(key));
            for(String key:new String[]{"fingerprint","promptSha256","launchHash"})if(!job.getString(key).matches("[a-f0-9]{64}"))throw new IOException("任务摘要无效");
            for(String key:new String[]{"sourceName","targetName","label","message"})HandoffRequest.string(job,key,key.equals("message")?1000:200,true);
            if(integer(job,"updatedAt")<integer(job,"createdAt"))throw new IOException("任务时间无效");if(job.has("tabId"))WorkspaceState.profile(job.getString("tabId"));
            if(job.has("conversationURL")){String url=job.getString("conversationURL");if(!NavigationPolicy.chat(url)||!NavigationPolicy.restorable(url).equals(url))throw new IOException("任务对话地址无效");}
            JSONArray files=job.getJSONArray("files");if(files.length()>20)throw new IOException("任务附件过多");Set<String> assets=new HashSet<>(),stages=new HashSet<>(),names=new HashSet<>();long total=0;
            for(int j=0;j<files.length();j++){
                JSONObject file=files.getJSONObject(j);String asset=file.getString("assetId"),stage=file.getString("stageId"),name=file.getString("uploadName");WorkspaceState.profile(asset);WorkspaceState.profile(stage);
                if(!assets.add(asset)||!stages.add(stage)||!names.add(name.toLowerCase(Locale.ROOT))||!file.getString("sourceAccountId").equals(job.getString("sourceAccountId"))||!AssetLedger.filename(name).equals(name)||!AssetLedger.filename(file.getString("name")).equals(file.getString("name"))||!file.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("任务附件归属或格式无效");
                for(String key:new String[]{"mime","uploadMime"})if(!file.getString(key).matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))throw new IOException("附件类型无效");
                long size=integer(file,"size");if(size>HandoffRequest.MAX_FILE||(total+=size)>HandoffRequest.MAX_TOTAL)throw new IOException("任务附件超过大小限制");
            }
        }
        return data;
    }
    private byte[] bytes(File file)throws Exception{return Files.readAllBytes(HandoffFiles.regular(root,file.getName(),25*1024*1024).toPath());}
    private JSONObject read()throws Exception{
        HandoffFiles.directory(root,false);HandoffFiles.directory(payloads,false);HandoffFiles.directory(fences,false);
        if(!index.exists()&&!backup.exists()){
            String[] retained=payloads.list(),sent=fences.list();File staging=new File(root,"staging");String[] stages=staging.list();if(retained==null||retained.length>0||sent==null||sent.length>0||stages!=null&&stages.length>0)throw new IOException("任务索引缺失，已保留数据并停止自动发送");JSONObject fresh=new JSONObject().put("schema",1).put("jobs",new JSONArray());atomic(index,fresh.toString().getBytes(StandardCharsets.UTF_8));return fresh;
        }
        try{return validate(bytes(index));}catch(Exception primary){
            JSONObject recovered;try{recovered=validate(bytes(backup));}catch(Exception invalid){throw new IOException("任务记录和备份不可读，已停止自动发送，请保留应用数据",invalid);}
            if(index.exists()){if(Files.isSymbolicLink(index.toPath()))throw primary;Files.move(index.toPath(),new File(root,"jobs.corrupt-"+WorkspaceState.id()+".json").toPath(),StandardCopyOption.ATOMIC_MOVE);}atomic(index,recovered.toString().getBytes(StandardCharsets.UTF_8));return recovered;
        }
    }
    private void atomic(File destination,byte[] data)throws Exception{
        HandoffFiles.directory(destination.getParentFile(),false);if(Files.isSymbolicLink(destination.toPath()))throw new IOException("任务文件不能是链接");File temporary=new File(destination.getParentFile(),destination.getName()+"."+WorkspaceState.id()+".tmp");
        try{try(FileChannel channel=FileChannel.open(temporary.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary.toPath());}
    }
    private void save(JSONObject data)throws Exception{byte[] next=data.toString().getBytes(StandardCharsets.UTF_8);validate(next);if(index.exists()){byte[] previous=bytes(index);validate(previous);atomic(backup,previous);}atomic(index,next);}
    private JSONObject record(JSONObject data,String id)throws Exception{WorkspaceState.profile(id);JSONArray jobs=data.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)if(jobs.getJSONObject(i).getString("id").equals(id))return jobs.getJSONObject(i);throw new IOException("处理任务不存在");}
    JSONObject get(String id)throws Exception{synchronized(LOCK){return clone(record(read(),id));}}
    List<JSONObject> list(String account)throws Exception{synchronized(LOCK){WorkspaceState.profile(account);JSONArray jobs=read().getJSONArray("jobs");List<JSONObject> result=new ArrayList<>();for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);if(job.getString("sourceAccountId").equals(account)||job.getString("targetAccountId").equals(account))result.add(clone(job));}result.sort((a,b)->Long.compare(b.optLong("createdAt"),a.optLong("createdAt")));return result;}}
    JSONObject create(JSONObject raw,JSONArray files,String sourceName,String targetName,String label,String prompt)throws Exception{
        JSONObject request=HandoffRequest.normalize(raw);if(prompt.trim().isEmpty()||prompt.length()>64000)throw new IOException("任务指令为空或过长");String id=request.getString("requestId"),fingerprint=HandoffRequest.fingerprint(request);
        synchronized(LOCK){JSONObject data=read();JSONArray jobs=data.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)if(jobs.getJSONObject(i).getString("id").equals(id)){JSONObject old=jobs.getJSONObject(i);if(!old.getString("fingerprint").equals(fingerprint))throw new IOException("同一任务标识不能更改文件、目标账号或要求");return clone(old);}if(jobs.length()>=1000)throw new IOException("任务记录已达 1000 条，请先整理");
            if(files.length()!=request.getJSONArray("assetIds").length())throw new IOException("任务附件快照不一致");for(int i=0;i<files.length();i++)if(!files.getJSONObject(i).getString("assetId").equals(request.getJSONArray("assetIds").getString(i)))throw new IOException("任务附件顺序不一致");
            String launch=WorkspaceState.id();JSONObject payload=new JSONObject().put("request",request).put("files",new JSONArray(files.toString())).put("prompt",prompt).put("launchToken",launch).put("uploadToken",WorkspaceState.id()).put("marker",WorkspaceState.id());
            long now=System.currentTimeMillis();JSONObject job=new JSONObject().put("id",id).put("fingerprint",fingerprint).put("sourceName",sourceName).put("targetName",targetName).put("label",label).put("status","preparing").put("message","正在校验选中的文件与目标账号").put("createdAt",now).put("updatedAt",now).put("files",new JSONArray(files.toString())).put("promptSha256",HandoffRequest.hash(prompt.getBytes(StandardCharsets.UTF_8))).put("launchHash",HandoffRequest.hash(launch.getBytes(StandardCharsets.UTF_8)));
            for(String key:new String[]{"sourceAccountId","targetAccountId","sourceStorageId","targetStorageId"})job.put(key,request.getString(key));jobs.put(job);validate(data.toString().getBytes(StandardCharsets.UTF_8));
            File file=new File(payloads,id+".bin");if(file.exists())throw new IOException("任务加密内容已存在，禁止覆盖");atomic(file,cipher.seal(id,payload.toString()));try{save(data);}catch(Exception e){Files.deleteIfExists(file.toPath());throw e;}return clone(job);
        }
    }
    JSONObject payload(String id)throws Exception{
        synchronized(LOCK){
            JSONObject job=record(read(),id);File file=HandoffFiles.regular(payloads,id+".bin",513*1024);JSONObject payload=new JSONObject(cipher.open(id,Files.readAllBytes(file.toPath())));JSONObject request=HandoffRequest.normalize(payload.getJSONObject("request"));String prompt=HandoffRequest.string(payload,"prompt",64000,true);
            if(!HandoffRequest.fingerprint(request).equals(job.getString("fingerprint"))||!HandoffRequest.hash(prompt.getBytes(StandardCharsets.UTF_8)).equals(job.getString("promptSha256")))throw new IOException("任务加密内容与记录不一致");
            for(String key:new String[]{"sourceAccountId","targetAccountId","sourceStorageId","targetStorageId"})if(!job.getString(key).equals(request.getString(key)))throw new IOException("任务账号与加密授权不一致");
            JSONArray sealed=payload.getJSONArray("files"),visible=job.getJSONArray("files");if(sealed.length()!=visible.length())throw new IOException("任务附件与加密授权不一致");
            for(int i=0;i<sealed.length();i++){JSONObject a=sealed.getJSONObject(i),b=visible.getJSONObject(i);for(String key:new String[]{"assetId","sourceAccountId","stageId","name","uploadName","mime","uploadMime","sha256"})if(!a.getString(key).equals(b.getString(key)))throw new IOException("任务附件与加密授权不一致");if(a.getLong("size")!=b.getLong("size"))throw new IOException("任务附件大小与加密授权不一致");}
            for(String key:new String[]{"launchToken","uploadToken","marker"})WorkspaceState.profile(payload.getString(key));return payload;
        }
    }
    JSONObject transition(String id,String next,String message)throws Exception{synchronized(LOCK){JSONObject data=read(),job=record(data,id);if(next.equals("sending")||!NEXT.get(job.getString("status")).contains(next))throw new IOException("任务已结束或状态冲突，禁止重复发送");job.put("status",next).put("message",message).put("updatedAt",System.currentTimeMillis());save(data);return clone(job);}}
    JSONObject claim(String id,String token)throws Exception{synchronized(LOCK){WorkspaceState.profile(token);JSONObject data=read(),job=record(data,id),sealed=payload(id);if(fenced(job)||!job.getString("status").equals("ready")||!MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),sealed.getString("launchToken").getBytes(StandardCharsets.US_ASCII))||!MessageDigest.isEqual(job.getString("launchHash").getBytes(StandardCharsets.US_ASCII),HandoffRequest.hash(token.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII)))throw new IOException("任务授权无效或已经执行，不会重复发送");job.put("status","opening").put("message","正在目标账号新建对话").put("updatedAt",System.currentTimeMillis());save(data);return clone(job);}}
    void bindTab(String id,String tab)throws Exception{synchronized(LOCK){WorkspaceState.profile(tab);JSONObject data=read(),job=record(data,id);if(!job.getString("status").equals("opening")||job.has("tabId"))throw new IOException("任务标签页已绑定");job.put("tabId",tab).put("updatedAt",System.currentTimeMillis());save(data);}}
    private boolean fenced(JSONObject job)throws Exception{
        File file=new File(fences,job.getString("id")+".json");if(!file.exists())return false;JSONObject fence=new JSONObject(new String(Files.readAllBytes(HandoffFiles.regular(fences,file.getName(),2048).toPath()),StandardCharsets.UTF_8));
        if(!job.getString("id").equals(fence.getString("id"))||!job.getString("promptSha256").equals(fence.getString("promptSha256")))throw new IOException("发送标记与任务不一致，已停止自动发送");return true;
    }
    void beginSend(String id)throws Exception{
        synchronized(LOCK){
            JSONObject data=read(),job=record(data,id);if(!job.getString("status").equals("drafting")||!job.has("tabId")||fenced(job))throw new IOException("任务尚未准备好或已经尝试发送");
            byte[] flag=new JSONObject().put("id",id).put("promptSha256",job.getString("promptSha256")).put("tabId",job.getString("tabId")).toString().getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel=FileChannel.open(new File(fences,id+".json").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(flag);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            job.put("status","sending").put("message","正在发送一次，结果不确定时不会重发").put("updatedAt",System.currentTimeMillis());save(data);
        }
    }
    void failure(String id,String message,boolean cancelled)throws Exception{
        synchronized(LOCK){JSONObject data=read(),job=record(data,id);if(job.getString("status").equals("submitted"))return;boolean uncertain=fenced(job)||job.getString("status").equals("sending");if(!uncertain&&!ACTIVE.contains(job.getString("status")))return;job.put("status",uncertain?"uncertain":cancelled?"cancelled":"failed").put("message",uncertain?"发送结果待确认，请检查目标对话；应用不会自动重发":message).put("updatedAt",System.currentTimeMillis());save(data);}
    }
    void submitted(String id,String url)throws Exception{synchronized(LOCK){if(!NavigationPolicy.chat(url))throw new IOException("目标对话地址无效");JSONObject data=read(),job=record(data,id);if(!job.getString("status").equals("sending"))throw new IOException("任务不处于发送阶段");job.put("status","submitted").put("conversationURL",NavigationPolicy.restorable(url)).put("message","网页已显示本次用户消息；模型结果请在对话中查看").put("updatedAt",System.currentTimeMillis());save(data);}}
    List<String> recover()throws Exception{
        synchronized(LOCK){JSONObject data=read();JSONArray jobs=data.getJSONArray("jobs");List<String> ids=new ArrayList<>();
            for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);boolean sent=fenced(job);if(ACTIVE.contains(job.getString("status"))||sent&&!job.getString("status").equals("submitted")&&!job.getString("status").equals("uncertain")){boolean sending=sent||job.getString("status").equals("sending");if(sent&&!job.has("tabId")){JSONObject fence=new JSONObject(new String(Files.readAllBytes(HandoffFiles.regular(fences,job.getString("id")+".json",2048).toPath()),StandardCharsets.UTF_8));WorkspaceState.profile(fence.getString("tabId"));job.put("tabId",fence.getString("tabId"));}job.put("status",sending?"uncertain":"interrupted").put("message",sending?"上次发送结果不确定，请检查目标对话；不会自动重发":"上次任务已中断，原始文件保留；不会自动继续发送").put("updatedAt",System.currentTimeMillis());ids.add(job.getString("id"));}}
            if(!ids.isEmpty())save(data);return ids;
        }
    }
    static boolean active(String status){return ACTIVE.contains(status);}
}
