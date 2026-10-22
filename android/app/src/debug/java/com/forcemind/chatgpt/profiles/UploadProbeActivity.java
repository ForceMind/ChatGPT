package com.forcemind.chatgpt.profiles;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import androidx.activity.ComponentActivity;
import androidx.webkit.WebViewCompat;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Debug-only, offline browser API probe. No account state or real messages. */
public final class UploadProbeActivity extends ComponentActivity {
    WebView web;
    final String marker="application/x-profiles-"+UUID.randomUUID().toString().replace("-","");
    volatile int accepted,rejected;
    volatile String[] accepts;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);web=new WebView(this);WebViewCompat.setProfile(web,WorkspaceState.profile(WorkspaceState.id()));
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowContentAccess(false);web.getSettings().setAllowFileAccess(false);setContentView(web);
        String html="<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Offline upload fixture — not ChatGPT</title><style>body{margin:8px}#upload{position:fixed;left:8px;top:8px;width:160px;height:40px}textarea{position:fixed;left:8px;top:64px;width:280px;height:64px}iframe{position:fixed;left:8px;top:160px;width:160px;height:80px}</style><input id=upload type=file multiple><textarea id=prompt-textarea></textarea><iframe src='https://other.example.test/input'></iframe><script>window.uploaded=null;document.querySelector('#upload').onchange=async function(){try{window.uploaded=JSON.stringify(await Promise.all(Array.from(this.files).map(async f=>({name:f.name,size:f.size,sha:Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await f.arrayBuffer()))).map(b=>b.toString(16).padStart(2,'0')).join('')}))))}catch(e){window.uploaded=JSON.stringify({error:String(e)})}}</script>";
        String child="<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{margin:0}input{width:140px;height:40px}</style><input type=file accept=application/octet-stream>";
        web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream((request.getUrl().getHost().equals("other.example.test")?child:html).getBytes(StandardCharsets.UTF_8)));}});
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
            accepts=params.getAcceptTypes();if(!"https://chatgpt.com/__offline_probe__".equals(view.getUrl())||!Arrays.asList(accepts).contains(marker)){rejected++;callback.onReceiveValue(null);return true;}
            accepted++;callback.onReceiveValue(new Uri[]{Uri.parse("content://com.forcemind.chatgpt.profiles.testfiles/html"),Uri.parse("content://com.forcemind.chatgpt.profiles.testfiles/image")});return true;
        }});
        web.loadUrl("https://chatgpt.com/__offline_probe__");
    }
    @Override public void onDestroy(){web.stopLoading();web.destroy();super.onDestroy();}
}
