package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Worker-thread conversions that publish a new owned asset only after success. */
final class DocumentConversions {
    static final int MAX_INPUT=8*1024*1024,MAX_MARKDOWN=2*1024*1024,MAX_OUTPUT=16*1024*1024;
    static List<String> formats(JSONObject source){
        String name=source.optString("name").toLowerCase(Locale.ROOT),mime=source.optString("mime");
        if(name.endsWith(".csv"))return Collections.singletonList("JSON");
        if(name.endsWith(".json"))return Collections.singletonList("CSV");
        if(name.endsWith(".md")||name.endsWith(".markdown"))return Collections.singletonList("HTML");
        if(mime.equals("text/csv"))return Collections.singletonList("JSON");
        if(mime.equals("application/json"))return Collections.singletonList("CSV");
        if(mime.equals("text/markdown"))return Collections.singletonList("HTML");
        return Collections.emptyList();
    }
    private static void active(BooleanSupplier available)throws IOException{
        if(Thread.currentThread().isInterrupted()||!available.getAsBoolean())throw new InterruptedIOException("转换已取消，原文件保留");
    }
    private static final class Checkpoint {
        final BooleanSupplier available;long next;
        Checkpoint(BooleanSupplier available){this.available=available;}
        void check()throws IOException{
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");
            if(System.nanoTime()>=next){active(available);next=System.nanoTime()+250_000_000L;}
        }
    }
    private static String text(File original,int maximum,Checkpoint checkpoint)throws Exception{
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(InputStream file=Files.newInputStream(original.toPath(),LinkOption.NOFOLLOW_LINKS);
            Reader reader=new InputStreamReader(new FilterInputStream(file){
                private long size;
                private void count(int n)throws IOException{checkpoint.check();if(n>0)size+=n;if(size>maximum)throw new IOException("文本转换上限为 "+(maximum/1024/1024)+" MiB，请拆分文件");}
                @Override public int read()throws IOException{int value=in.read();count(value<0?0:1);return value;}
                @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);count(n);return n;}
            },decoder)){
            StringBuilder value=new StringBuilder();char[] buffer=new char[8192];int n;
            while((n=reader.read(buffer))!=-1){checkpoint.check();value.append(buffer,0,n);}return value.toString();
        }
    }
    static JSONObject convert(File cacheRoot,AssetLedger ledger,String id,String format,Character separator,BooleanSupplier available)throws Exception{
        active(available);JSONObject source=ledger.get(id);
        if(!formats(source).contains(format))throw new IOException("原文件不支持所选转换格式");
        File original=ledger.verifiedFile(id);int maximum=format.equals("HTML")?MAX_MARKDOWN:MAX_INPUT;
        Checkpoint checkpoint=new Checkpoint(available);String input;
        try{input=text(original,maximum,checkpoint);}
        catch(CharacterCodingException error){throw new IOException("原文件不是有效的 UTF-8 文本，请先转换编码",error);}
        catch(OutOfMemoryError error){throw new IOException("文件内容过大，请拆分后再转换",error);}
        File cache=new File(cacheRoot.getCanonicalFile(),"asset-conversions");
        if(Files.isSymbolicLink(cache.toPath())||!cache.getCanonicalFile().equals(cache.getAbsoluteFile()))throw new IOException("转换临时目录无效");
        if(!cache.exists()&&!cache.mkdir())throw new IOException("无法创建转换临时目录");
        File output=File.createTempFile("document-",".tmp",cache);
        try{
            try(FileOutputStream file=new FileOutputStream(output);
                Writer writer=new OutputStreamWriter(new FilterOutputStream(file){
                    private long size;
                    @Override public void write(int value)throws IOException{checkpoint.check();if(++size>MAX_OUTPUT)throw new IOException("转换结果超过 16 MiB，请拆分文件");out.write(value);}
                    @Override public void write(byte[] bytes,int offset,int length)throws IOException{checkpoint.check();size+=length;if(size>MAX_OUTPUT)throw new IOException("转换结果超过 16 MiB，请拆分文件");out.write(bytes,offset,length);}
                },StandardCharsets.UTF_8)){
                if(format.equals("JSON"))DocumentFormats.csvToJson(input,separator==null?DocumentFormats.delimiter(input):separator,writer);
                else if(format.equals("CSV"))DocumentFormats.jsonToCsv(input,writer);
                else DocumentFormats.markdownHtml(input,writer);
                writer.flush();file.getFD().sync();
            }
            active(available);ledger.verifiedFile(id);
            String name=source.getString("name");int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);
            String mime=format.equals("JSON")?"application/json":format.equals("CSV")?"text/csv":"text/html";
            try(InputStream result=Files.newInputStream(output.toPath(),LinkOption.NOFOLLOW_LINKS)){return ledger.importCopy(result,name+"-converted."+format.toLowerCase(Locale.ROOT),mime,"conversion",id,available);}
        }catch(OutOfMemoryError|StackOverflowError error){throw new IOException("文件内容过大或过于复杂，请拆分后再转换",error);}
        finally{Files.deleteIfExists(output.toPath());}
    }
}
