package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.res.Configuration;
import android.os.*;
import android.view.ContextThemeWrapper;
import android.webkit.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.webkit.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Debug-only evidence collection. Reserved HTTPS origins, fresh profiles, no accounts. */
public final class EnvironmentProbeActivity extends ComponentActivity {
    final WebView[] pages=new WebView[2];
    final String[] languages={"zh-CN","ja-JP"},zones={"Asia/Shanghai","America/New_York"};
    final Map<String,String> headers=new ConcurrentHashMap<>(),errors=new ConcurrentHashMap<>();
    boolean customHeaders;
    private static final String ORIGIN="https://language-probe.example.test";
    private static final String FRAME="https://frame-probe.example.test";
    private static final String SNAPSHOT="function snapshot(){var f=new Intl.DateTimeFormat();return {language:navigator.language,languages:Array.from(navigator.languages||[]),locale:f.resolvedOptions().locale,timeZone:f.resolvedOptions().timeZone,winterOffset:new Date('2026-01-15T12:00:00Z').getTimezoneOffset(),summerOffset:new Date('2026-07-15T12:00:00Z').getTimezoneOffset(),localNoon:new Date(2026,0,15,12).toISOString()};}";
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(android.graphics.Color.WHITE);setContentView(root);
        TextView label=new TextView(this);label.setText("离线公开接口探针 · 请求头 / 网页 / iframe / Worker\nConfiguration 使用独立语言；不修改系统语言或时区。");label.setTextColor(android.graphics.Color.BLACK);root.addView(label);
        customHeaders=WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS);
        for(int i=0;i<2;i++){
            final int slot=i;Configuration config=new Configuration(getResources().getConfiguration());config.setLocales(new LocaleList(Locale.forLanguageTag(languages[i])));Context localized=createConfigurationContext(config);
            WebView web=new WebView(new ContextThemeWrapper(localized,R.style.AppTheme));WebViewCompat.setProfile(web,WorkspaceState.profile(WorkspaceState.id()));pages[i]=web;
            web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            if(customHeaders)try{WebViewCompat.getProfile(web).addCustomHeader(new CustomHeader("Accept-Language",languages[i]+","+languages[i].split("-")[0]+";q=0.9,en-US;q=0.8,en;q=0.7",new HashSet<>(Arrays.asList(ORIGIN,FRAME))));}catch(Exception e){errors.put(languages[i],e.getClass().getSimpleName()+": "+e.getMessage());}
            web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String address=request.getUrl().toString(),accept="";for(Map.Entry<String,String> h:request.getRequestHeaders().entrySet())if(h.getKey().equalsIgnoreCase("Accept-Language"))accept=h.getValue();headers.put(languages[slot]+" "+address,accept);
                String host=request.getUrl().getHost(),path=request.getUrl().getPath(),body="",mime="text/plain";
                if("language-probe.example.test".equals(host)&&"/worker.js".equals(path)){mime="application/javascript";body=SNAPSHOT+"self.postMessage(snapshot());";}
                else if("frame-probe.example.test".equals(host)){mime="text/html";body="<!doctype html><meta charset=utf-8><script>"+SNAPSHOT+"parent.postMessage({probe:snapshot()},'"+ORIGIN+"');</script>独立来源 iframe";}
                else if("language-probe.example.test".equals(host)&&"/main".equals(path)){
                    mime="text/html";body="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>离线环境接口探针</title><style>body{background:white;color:#171717;font:12px sans-serif}pre{white-space:pre-wrap}iframe{height:28px;border:0}</style><b>Configuration: "+languages[slot]+" · 仅做测量，不改写 JS</b><pre id=result></pre><script>"+SNAPSHOT+
                        "window.result={main:snapshot()};function show(){document.querySelector('#result').textContent=JSON.stringify(window.result,null,2);}window.addEventListener('message',function(e){if(e.origin==='"+FRAME+"'&&e.source===document.querySelector('iframe').contentWindow&&e.data&&e.data.probe){window.result.frame=e.data.probe;show();}});try{var w=new Worker('/worker.js');w.onmessage=function(e){window.result.worker=e.data;show();w.terminate();};w.onerror=function(){window.result.workerError='worker load failed';show();w.terminate();};}catch(e){window.result.workerError='worker unavailable';}setTimeout(function(){if(!window.result.worker&&!window.result.workerError){window.result.workerError='worker timeout';show();}},10000);show();</script><iframe src='"+FRAME+"/frame'></iframe>";
                }
                return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }});
            root.addView(web,new LinearLayout.LayoutParams(-1,0,1));web.loadUrl(ORIGIN+"/main");
        }
    }
    @Override public void onDestroy(){for(WebView web:pages)if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
