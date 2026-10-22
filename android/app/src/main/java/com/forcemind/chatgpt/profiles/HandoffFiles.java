package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

final class HandoffFiles {
    static void directory(File directory,boolean create)throws IOException{
        if(Files.isSymbolicLink(directory.toPath())||!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new IOException("任务目录无效");
        if(create&&!directory.exists()&&!directory.mkdir())throw new IOException("无法建立任务目录");if(!directory.isDirectory())throw new IOException("任务目录不存在");
    }
    static File root(File files,boolean create)throws Exception{File root=new File(files.getCanonicalFile(),"handoff");directory(root,create);return root;}
    static File staging(File files,String id,boolean create)throws Exception{WorkspaceState.profile(id);File root=new File(root(files,create),"staging");directory(root,create);File task=new File(root,id);directory(task,create);return task;}
    static File regular(File directory,String name,long limit)throws IOException{File file=new File(directory,name);if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||!file.getCanonicalFile().equals(file.getAbsoluteFile())||file.length()>limit)throw new IOException("任务文件无效");return file;}
    static JSONArray snapshot(AssetLedger ledger,JSONArray ids)throws Exception{
        JSONArray result=new JSONArray();long total=0;Set<String> names=new HashSet<>();
        if(ids.length()>20)throw new IOException("一次最多 20 个附件");
        for(int i=0;i<ids.length();i++){
            JSONObject asset=ledger.get(ids.getString(i));if(!asset.getString("status").equals("ready")||asset.getBoolean("trashed"))throw new IOException("附件未就绪或已在回收站");long size=asset.getLong("size");if(size>HandoffRequest.MAX_FILE||(total+=size)>HandoffRequest.MAX_TOTAL)throw new IOException("单文件上限 100 MiB，附件总量上限 200 MiB");
            String name=asset.getString("name"),upload=name;boolean text=name.toLowerCase(Locale.ROOT).matches(".*\\.(html?|svg|[cm]?jsx?|tsx?|css|sh|py)$")||asset.getString("mime").equals("text/html")||asset.getString("mime").equals("image/svg+xml");
            if(text)upload+=".txt";upload=AssetLedger.filename(upload);if(!names.add(upload.toLowerCase(Locale.ROOT))){upload=AssetLedger.filename((i+1)+"-"+upload);if(!names.add(upload.toLowerCase(Locale.ROOT)))throw new IOException("附件上传名称重复");}
            result.put(new JSONObject().put("assetId",asset.getString("id")).put("sourceAccountId",ledger.accountId).put("stageId",WorkspaceState.id()).put("name",name).put("uploadName",upload).put("mime",asset.getString("mime")).put("uploadMime",text?"text/plain":asset.getString("mime")).put("size",size).put("sha256",asset.getString("sha256")));
        }
        return result;
    }
    static void stage(File files,String id,AssetLedger ledger,JSONArray snapshots,BooleanSupplier available)throws Exception{
        WorkspaceState.profile(id);File parent=new File(root(files,true),"staging");directory(parent,true);File task=new File(parent,id);if(!task.mkdir())throw new IOException("任务暂存目录已经存在，禁止重复准备附件");directory(task,false);boolean success=false;
        try{
            for(int i=0;i<snapshots.length();i++){
                if(!available.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("任务已取消");JSONObject snapshot=snapshots.getJSONObject(i);File original=ledger.verifiedFile(snapshot.getString("assetId"));String stage=snapshot.getString("stageId");WorkspaceState.profile(stage);long count=0;MessageDigest hash=MessageDigest.getInstance("SHA-256");long next=0;
                try(InputStream in=Files.newInputStream(original.toPath(),LinkOption.NOFOLLOW_LINKS);FileChannel out=FileChannel.open(new File(task,stage+".bin").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("任务已取消");if(System.nanoTime()>=next){if(!available.getAsBoolean())throw new InterruptedIOException("任务已取消");next=System.nanoTime()+250_000_000L;}count+=n;if(count>snapshot.getLong("size")||count>HandoffRequest.MAX_FILE)throw new IOException("源文件大小已改变");hash.update(buffer,0,n);ByteBuffer bytes=ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())out.write(bytes);}out.force(true);
                }
                StringBuilder digest=new StringBuilder();for(byte b:hash.digest())digest.append(String.format(Locale.ROOT,"%02x",b&255));JSONObject current=ledger.get(snapshot.getString("assetId"));
                if(count!=snapshot.getLong("size")||!digest.toString().equals(snapshot.getString("sha256"))||!current.getString("sha256").equals(snapshot.getString("sha256"))||current.getBoolean("trashed")||!current.getString("status").equals("ready")||!available.getAsBoolean())throw new IOException("附件在准备过程中发生变化，已停止");
            }
            success=true;
        }finally{if(!success)cleanup(files,id);}
    }
    static void cleanup(File files,String id)throws Exception{
        File directory;try{directory=staging(files,id,false);}catch(FileNotFoundException e){return;}catch(IOException e){File root=new File(new File(new File(files.getCanonicalFile(),"handoff"),"staging"),id);if(!root.exists())return;throw e;}
        File[] children=directory.listFiles();if(children==null)throw new IOException("无法检查临时附件");for(File file:children){String name=file.getName();if(!name.endsWith(".bin"))continue;try{WorkspaceState.profile(name.substring(0,name.length()-4));}catch(IllegalArgumentException ignored){continue;}Files.deleteIfExists(file.toPath());}Files.delete(directory.toPath());
    }
}
