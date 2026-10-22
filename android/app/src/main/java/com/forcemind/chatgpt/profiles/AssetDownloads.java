package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.webkit.WebViewCompat;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A job retains the initiating account/cookie jar; switching UI never retargets it. */
final class AssetDownloads {
    private final Activity activity;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,AtomicInteger> epochs=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private volatile String connectionAccount;
    AssetDownloads(Activity activity){this.activity=activity;}
    private int epoch(String id){return epochs.computeIfAbsent(id,x->new AtomicInteger()).get();}
    private boolean available(String id,int epoch){
        return !closed&&epoch(id)==epoch&&!Thread.currentThread().isInterrupted()&&WorkspaceFiles.contains(activity.getFilesDir(),id);
    }
    void start(WebView web,String accountId,String accountName,String url,String agent,String disposition,String mime){
        if(closed||!NavigationPolicy.https(url)){notice("当前资产归档支持 HTTPS 下载；此链接暂不支持");return;}
        final CookieManager cookies=WebViewCompat.getProfile(web).getCookieManager();
        final int generation=epoch(accountId);final String filename=URLUtil.guessFileName(url,disposition,mime);
        notice(accountName+" · 正在下载到资产库…");
        executor.execute(()->{
            try{
                String target=url;boolean saved=false;
                for(int redirect=0;redirect<=5;redirect++){
                    if(!available(accountId,generation)||!NavigationPolicy.https(target))throw new IOException("下载已取消");
                    HttpURLConnection request=(HttpURLConnection)new URL(target).openConnection();connection=request;connectionAccount=accountId;
                    try{
                        request.setConnectTimeout(20000);request.setReadTimeout(30000);request.setInstanceFollowRedirects(false);request.setRequestProperty("User-Agent",agent);
                        String cookie=cookies.getCookie(target);if(cookie!=null)request.setRequestProperty("Cookie",cookie);
                        int code=request.getResponseCode();
                        if(code>=300&&code<400){String location=request.getHeaderField("Location");if(location==null)throw new IOException("重定向缺少目标");target=new URL(new URL(target),location).toString();continue;}
                        if(code!=200||request.getContentLengthLong()>AssetLedger.MAX_FILE)throw new IOException("下载响应无效或文件过大");
                        if(!available(accountId,generation))throw new IOException("下载已取消");
                        AssetLedger ledger=new AssetLedger(activity.getFilesDir(),accountId);
                        try(InputStream raw=request.getInputStream();InputStream input=new FilterInputStream(raw){
                            @Override public int read(byte[] b,int off,int len)throws IOException {
                                if(closed||epoch(accountId)!=generation||Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载已取消");
                                return super.read(b,off,len);
                            }
                        }){
                            ledger.importCopy(input,filename,mime,"download",null,()->available(accountId,generation));
                        }
                        saved=true;break;
                    }finally{request.disconnect();connection=null;connectionAccount=null;}
                }
                notice(saved?accountName+" · 文件已归档，可从账号菜单打开资产库":"下载未完成，请重新下载");
            }catch(Exception error){notice("下载失败或已取消。原网页和其他账号文件保持不变。");}
        });
    }
    void cancel(String accountId){epochs.computeIfAbsent(accountId,x->new AtomicInteger()).incrementAndGet();if(accountId.equals(connectionAccount)){HttpURLConnection c=connection;if(c!=null)c.disconnect();}}
    void close(){closed=true;executor.shutdownNow();HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    private void notice(String text){activity.runOnUiThread(()->{if(!closed&&!activity.isFinishing())Toast.makeText(activity,text,Toast.LENGTH_LONG).show();});}
}
