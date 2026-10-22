package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** File-only protocol. The renderer never opens an account state or asset index. */
final class RenderTasks {
    static final int MAX_SOURCE=2*1024*1024,MAX_RESULT=64*1024*1024,MAX_HEIGHT=16384;
    static final long MAX_PIXELS=16_000_000L;
    static String kind(JSONObject source){
        String kind=ConversionType.of(source);return kind.equals("svg")||kind.equals("html")||kind.equals("markdown")?kind:"";
    }
    static List<String> formats(String kind){
        switch(kind){case "html":return Arrays.asList("PDF","PNG");case "markdown":return Collections.singletonList("PDF");case "svg":return Arrays.asList("PNG","JPEG","WEBP");default:return Collections.emptyList();}
    }
    private static void safeDirectory(File directory,boolean create)throws IOException{
        if(Files.isSymbolicLink(directory.toPath())||!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new IOException("转换临时目录无效");
        if(create&&!directory.exists()&&!directory.mkdir())throw new IOException("无法建立转换临时目录");
        if(!directory.isDirectory())throw new IOException("转换临时目录不存在");
    }
    static File directory(File cache,String token,boolean create)throws Exception{
        WorkspaceState.profile(token);File root=new File(cache.getCanonicalFile(),"asset-render-tasks");safeDirectory(root,create);
        File task=new File(root,token);safeDirectory(task,create);return task;
    }
    private static File regular(File directory,String name,long limit)throws IOException{
        File file=new File(directory,name);
        if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||!file.getCanonicalFile().equals(file.getAbsoluteFile())||file.length()>limit)throw new IOException("转换文件无效或超过大小限制");return file;
    }
    private static void active(BooleanSupplier available)throws IOException{if(Thread.currentThread().isInterrupted()||!available.getAsBoolean())throw new InterruptedIOException("转换已取消，原件保留");}
    private static String digest(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static JSONObject prepare(File cache,AssetLedger ledger,String id,String format,int width,int quality,String owner,BooleanSupplier available)throws Exception{
        active(available);JSONObject source=ledger.get(id);String kind=kind(source);validate(kind,format,width,quality);
        File original=ledger.verifiedFile(id);if(original.length()>MAX_SOURCE)throw new IOException("HTML / Markdown / SVG 渲染上限为 2 MiB");
        cleanupExpired(cache);
        String token=WorkspaceState.id();File task=directory(cache,token,true);boolean complete=false;
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream in=Files.newInputStream(original.toPath(),LinkOption.NOFOLLOW_LINKS);FileChannel out=FileChannel.open(new File(task,"source.utf8").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");size+=n;if(size>MAX_SOURCE)throw new IOException("渲染原文件超过 2 MiB");hash.update(buffer,0,n);ByteBuffer data=ByteBuffer.wrap(buffer,0,n);while(data.hasRemaining())out.write(data);}out.force(true);
            }
            String sha=hex(hash.digest());if(!sha.equals(source.getString("sha256")))throw new IOException("原文件已改变，请重新选择");ledger.verifiedFile(id);active(available);
            JSONObject request=new JSONObject().put("schema",1).put("kind",kind).put("format",format).put("width",width).put("quality",quality).put("inputSha256",sha).put("owner",owner==null?"":owner.substring(0,Math.min(owner.length(),600))).put("createdAt",System.currentTimeMillis());
            try(FileChannel out=FileChannel.open(new File(task,"request.json").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(request.toString().getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())out.write(bytes);out.force(true);}
            complete=true;return new JSONObject().put("task",token).put("sourceId",id).put("accountId",ledger.accountId).put("sourceSha256",sha).put("format",format);
        }finally{if(!complete)cleanup(cache,token);}
    }
    static void validate(String kind,String format,int width,int quality)throws IOException{
        if(!formats(kind).contains(format))throw new IOException("此文件不支持所选渲染格式");
        if(width!=640&&width!=1280&&width!=2048&&width!=4096)throw new IOException("输出宽度无效");
        if(quality<1||quality>100)throw new IOException("输出质量无效");
    }
    static JSONObject request(File cache,String token)throws Exception{
        File task=directory(cache,token,false);JSONObject request=new JSONObject(new String(Files.readAllBytes(regular(task,"request.json",8192).toPath()),StandardCharsets.UTF_8));
        if(request.getInt("schema")!=1||!request.getString("inputSha256").matches("[a-f0-9]{64}"))throw new IOException("转换任务无效");
        validate(request.getString("kind"),request.getString("format"),request.getInt("width"),request.getInt("quality"));return request;
    }
    static String source(File cache,String token,JSONObject request)throws Exception{
        byte[] bytes=Files.readAllBytes(regular(directory(cache,token,false),"source.utf8",MAX_SOURCE).toPath());
        if(!digest(bytes).equals(request.getString("inputSha256")))throw new IOException("转换副本已改变");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IOException("原文件不是有效的 UTF-8 文本",e);}
    }
    static File result(File cache,String token)throws Exception{return regular(directory(cache,token,false),"result.bin",MAX_RESULT);}
    static JSONObject archive(File cache,AssetLedger ledger,JSONObject pending,BooleanSupplier available)throws Exception{
        active(available);String token=pending.getString("task"),id=pending.getString("sourceId");JSONObject request=request(cache,token),source=ledger.get(id);
        if(!ledger.accountId.equals(pending.getString("accountId"))||!request.getString("format").equals(pending.getString("format"))||!request.getString("inputSha256").equals(pending.getString("sourceSha256"))||!source.getString("sha256").equals(pending.getString("sourceSha256")))throw new IOException("转换结果与原账号或原文件不一致");
        ledger.verifiedFile(id);File result=result(cache,token);if(result.length()==0)throw new IOException("转换结果为空");
        String format=request.getString("format"),extension=format.equals("JPEG")?"jpg":format.toLowerCase(Locale.ROOT);
        String mime=format.equals("PDF")?"application/pdf":"image/"+(format.equals("JPEG")?"jpeg":format.toLowerCase(Locale.ROOT));
        String name=source.getString("name");int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);
        try(InputStream in=Files.newInputStream(result.toPath(),LinkOption.NOFOLLOW_LINKS)){return ledger.importCopy(in,name+"-converted."+extension,mime,"conversion",id,available);}
    }
    static void cleanup(File cache,String token)throws Exception{
        File task=directory(cache,token,false);for(String name:new String[]{"source.utf8","request.json","result.partial","result.bin"})Files.deleteIfExists(new File(task,name).toPath());Files.delete(task.toPath());
    }
    private static void cleanupExpired(File cache)throws Exception{
        File root=new File(cache.getCanonicalFile(),"asset-render-tasks");if(!root.exists())return;safeDirectory(root,false);File[] tasks=root.listFiles();if(tasks==null)return;
        long cutoff=System.currentTimeMillis()-24L*60*60*1000;
        for(File task:tasks)if(task.lastModified()<cutoff&&!Files.isSymbolicLink(task.toPath())&&task.isDirectory()){
            try{WorkspaceState.profile(task.getName());}catch(IllegalArgumentException ignored){continue;}
            try{cleanup(cache,task.getName());}catch(IOException ignored){/* retain unknown files; never recurse */}
        }
    }
}
