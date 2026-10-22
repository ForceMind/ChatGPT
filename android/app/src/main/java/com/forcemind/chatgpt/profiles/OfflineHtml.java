package com.forcemind.chatgpt.profiles;

import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Same offline policy for previews and conversion; no account/native bridge. */
final class OfflineHtml {
    static void load(WebView web,String html,Runnable ready,Consumer<String> failed){
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(false);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setGeolocationEnabled(false);settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);
        String policy="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
        String meta="<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\""+policy+"\">";
        java.util.regex.Matcher head=java.util.regex.Pattern.compile("(?i)<head\\b[^>]*>").matcher(html);
        String safe=head.find()?html.substring(0,head.end())+meta+html.substring(head.end()):meta+html;
        byte[] bytes=safe.getBytes(StandardCharsets.UTF_8);String url="https://preview-"+UUID.randomUUID()+".invalid/";
        Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Content-Security-Policy",policy);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        web.setWebViewClient(new WebViewClient(){
            private boolean done;
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if(request.isForMainFrame()&&"GET".equals(request.getMethod())&&url.equals(request.getUrl().toString()))return new WebResourceResponse("text/html","UTF-8",200,"OK",headers,new ByteArrayInputStream(bytes));
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view,String current){if(!done&&url.equals(current)){done=true;ready.run();}}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){done=true;failed.accept("本地页面未能加载，原文件已保留");}}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){done=true;failed.accept("预览进程已退出。原文件已保留，可以返回重试或导出。");return true;}
        });
        web.loadUrl(url);
    }
}
