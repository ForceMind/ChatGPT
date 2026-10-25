package com.forcemind.chatgpt.profiles;

import android.content.*;
import android.net.*;
import android.os.*;
import android.webkit.*;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.*;

/** Owns browser pages for this process, independently of the current window. */
final class BrowserMemory {
    private static BrowserMemory instance;
    static synchronized BrowserMemory get(Context context){if(instance==null)instance=new BrowserMemory(context.getApplicationContext());return instance;}
    final Context context;
    final Map<String,WebView> views=new LinkedHashMap<>();
    final Map<String,String> accounts=new HashMap<>(),storage=new HashMap<>(),popupOwners=new HashMap<>();
    final Map<String,Long> epochs=new HashMap<>();
    final LinkedHashMap<String,android.graphics.Bitmap> thumbnails=new LinkedHashMap<>(24,.75f,true);
    final Map<String,Long> thumbnailTimes=new HashMap<>();
    final LinkedHashMap<String,Long> recentClosed=new LinkedHashMap<>();
    final Handler handler=new Handler(Looper.getMainLooper());
    final BrowserDiagnostics diagnostics;
    PageRecoveryManager recovery;
    AssetDownloads assetDownloads;
    DownloadHelper downloads;
    private WeakReference<MainActivity> owner=new WeakReference<>(null);
    private final Runnable recentPruner=this::pruneRecent;
    private BrowserMemory(Context context){
        this.context=context;diagnostics=new BrowserDiagnostics(context.getFilesDir());diagnostics.event("process-start",null,null,null);
        try{((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){diagnostics.event("network-available",null,null,null);}
            @Override public void onLost(Network network){diagnostics.event("network-lost",null,null,null);}
        });}catch(RuntimeException ignored){}
    }
    MainActivity owner(){return owner.get();}
    void attach(MainActivity activity){owner=new WeakReference<>(activity);if(recovery!=null)recovery.attach(activity);if(downloads!=null)downloads.attach(activity);}
    void register(String tab,String account,String storageId,WebView web){views.put(tab,web);accounts.put(tab,account);storage.put(tab,storageId);epochs.putIfAbsent(tab,0L);}
    void detach(MainActivity activity){
        if(owner()!=activity)return;owner.clear();if(recovery!=null)recovery.attach(null);if(downloads!=null)downloads.attach(null);
        for(Map.Entry<String,WebView> entry:new ArrayList<>(views.entrySet())){
            String tab=entry.getKey(),account=accounts.get(tab);WebView web=entry.getValue();
            if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
            if(web.getContext() instanceof MutableContextWrapper)((MutableContextWrapper)web.getContext()).setBaseContext(context);
            web.setOnTouchListener(null);web.setDownloadListener(null);
            web.setWebChromeClient(new WebChromeClient(){@Override public void onReceivedTitle(WebView view,String title){remember(account,tab,null,title);}});
            web.setWebViewClient(new WebViewClient(){
                @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){epochs.put(tab,epochs.getOrDefault(tab,0L)+1);if(recovery!=null)recovery.navigating(view);diagnostics.event("page-load",account,tab,null);}
                @Override public void onPageFinished(WebView view,String url){remember(account,tab,url,null);}
                @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){remember(account,tab,url,null);}
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !NavigationPolicy.internal(request.getUrl().toString());}
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return BuildConfig.DEBUG&&MainActivity.handoffFixture!=null?MainActivity.handoffFixture.response(account,request):null;}
                @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){diagnostics.event(detail.didCrash()?"renderer-crash":"renderer-reclaimed",account,tab,"priority:"+detail.rendererPriorityAtExit());destroy(tab);return true;}
                @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();diagnostics.event("ssl-error",account,tab,null);}
            });
        }
    }
    private void remember(String account,String tab,String url,String title){
        if(owner()!=null)return;
        try{StateStore store=new StateStore(context.getFilesDir());WorkspaceState state=store.load();WorkspaceState.Account a=state.find(account);for(WorkspaceState.Tab t:a.tabs)if(t.id.equals(tab)){if(url!=null)t.url=NavigationPolicy.restorable(url);if(!t.customTitle&&title!=null&&!title.trim().isEmpty())t.title=title.substring(0,Math.min(120,title.length()));store.save(state);break;}}catch(Exception ignored){}
    }
    void destroy(String tab){
        WebView web=views.remove(tab);accounts.remove(tab);storage.remove(tab);epochs.remove(tab);popupOwners.remove(tab);recentClosed.remove(tab);thumbnails.remove(tab);thumbnailTimes.remove(tab);
        if(web!=null){if(recovery!=null)recovery.unbind(web);if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);web.stopLoading();web.destroy();}
    }
    void pruneRecent(){
        handler.removeCallbacks(recentPruner);long now=SystemClock.elapsedRealtime();for(String id:new ArrayList<>(recentClosed.keySet()))if(recentClosed.get(id)<=now)destroy(id);
        while(recentClosed.size()>5)destroy(recentClosed.keySet().iterator().next());
        if(!recentClosed.isEmpty())handler.postDelayed(recentPruner,Math.max(1,Collections.min(recentClosed.values())-now));
    }
    void clear(){
        handler.removeCallbacks(recentPruner);if(recovery!=null){recovery.close();recovery=null;}
        for(String id:new ArrayList<>(views.keySet()))destroy(id);
        if(assetDownloads!=null){assetDownloads.close();assetDownloads=null;}if(downloads!=null){downloads.close();downloads=null;}owner.clear();
    }
}
