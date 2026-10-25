package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.json.*;

/** Local bounded event journal. Never records URLs, page text, cookies or prompts. */
final class BrowserDiagnostics {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final File file;
    private final JSONArray events=new JSONArray();
    BrowserDiagnostics(File directory){
        file=new File(directory,"browser-events.json");
        try{if(file.isFile()&&file.length()<131072){JSONArray saved=new JSONArray(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));for(int i=Math.max(0,saved.length()-100);i<saved.length();i++)events.put(saved.getJSONObject(i));}}catch(Exception ignored){}
    }
    synchronized void event(String type,String account,String tab,String detail){
        try{
            if(!type.matches("[a-z-]{1,48}"))return;
            JSONObject item=new JSONObject().put("time",System.currentTimeMillis()).put("type",type);
            if(account!=null&&account.matches("[a-f0-9-]{36}"))item.put("account",account);
            if(tab!=null&&tab.matches("[a-f0-9-]{36}"))item.put("tab",tab);
            if(detail!=null&&detail.matches("[a-zA-Z0-9_.: -]{1,100}"))item.put("detail",detail);
            events.put(item);while(events.length()>100)events.remove(0);
            byte[] bytes=events.toString().getBytes(StandardCharsets.UTF_8);
            IO.execute(()->{File temporary=new File(file.getParentFile(),"browser-events.tmp");try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);}try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}catch(Exception ignored){temporary.delete();}});
        }catch(Exception ignored){}
    }
    synchronized JSONArray snapshot(){try{return new JSONArray(events.toString());}catch(Exception e){return new JSONArray();}}
    static String label(String type){return switch(type){
        case "process-start"->"应用进程启动";case "activity-retained"->"窗口重建，网页已保留";case "page-load"->"网页开始载入";
        case "reload-request"->"手动重新加载";case "renderer-crash"->"网页渲染进程崩溃";case "renderer-reclaimed"->"系统回收网页渲染进程";
        case "network-lost"->"网络已断开";case "network-available"->"网络已恢复";case "page-error"->"网页加载失败";case "ssl-error"->"连接证书校验失败";
        case "background-start"->"后台任务模式已开启";case "background-stop"->"后台任务模式已关闭";case "background-error"->"后台任务模式未能启动";
        case "unsupported-link"->"网页链接未受支持";default->type;};}
}
