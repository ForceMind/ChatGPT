package com.forcemind.chatgpt.profiles;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.provider.Settings;
import android.os.PowerManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONObject;

public final class MainActivity extends ComponentActivity implements HandoffController.Host {
    private static final int INK = Color.rgb(23, 23, 23), GRAY = Color.rgb(244, 244, 244);
    private WorkspaceState state;
    private StateStore store;
    private LinearLayout root, header;
    private StudioBrowserHeader browserHeader;
    private final Map<WebView,Boolean> loadingPages=new java.util.IdentityHashMap<>();
    private final Map<WebView,Integer> forcedCacheModes=new java.util.IdentityHashMap<>();
    private boolean navigationPending;
    private WebView navigationWeb;
    private long navigationTicket;
    private FrameLayout content;
    private ProgressBar progress;
    private BrowserMemory memory;
    private FrameLayout browserArea;
    private OfflineSnapshotView offlineSnapshot;
    private WebView snapshotOwner;
    private LinearLayout undoBar;
    private TabOverview overview;
    private boolean keyboardVisible;
    private long undoSerial;
    private Map<String, WebView> views = new HashMap<>();
    private Map<String, String> popupOwners = new HashMap<>();
    private ValueCallback<Uri[]> fileCallback;
    private String fileOwner;
    private PermissionRequest mediaRequest;
    private String mediaOwner;
    private boolean ready, fatal;
    private boolean resumed;
    private String redirectedEnvironment;
    private boolean environmentLaunchPending;
    private boolean networkSettingsPending,networkSettingsShowing;
    private LinearLayout networkNotice;
    private TextView networkNoticeText;
    private Map<String,Long> pageEpochs=new HashMap<>();
    private HandoffRuntime handoffs;
    private PageRecoveryManager recovery;
    private java.util.LinkedHashMap<String,Long> recentClosed=new java.util.LinkedHashMap<>();
    private final android.os.Handler recentHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String pendingJob,pendingJobToken;
    private LinearLayout taskBanner;
    private TextView taskText,taskAction;
    private JSONObject bannerJob;
    interface HandoffFixture {WebResourceResponse response(String accountId,WebResourceRequest request);}
    static volatile HandoffFixture handoffFixture;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        memory=BrowserMemory.get(this);views=memory.views;popupOwners=memory.popupOwners;pageEpochs=memory.epochs;recentClosed=memory.recentClosed;memory.attach(this);assetDownloads=memory.assetDownloads;downloadHelper=memory.downloads;
        buildLayout();
        networkSettingsPending=bundle!=null&&bundle.getBoolean("networkSettingsOpen",false);consumeNetworkSettingsIntent(getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        try{store=new StateStore(getFilesDir());state=store.load();if(getIntent().hasExtra(EnvironmentLauncher.ACCOUNT_ID)){String accountId=getIntent().getStringExtra(EnvironmentLauncher.ACCOUNT_ID);state=store.update(next->{next.find(accountId);next.activeAccount=accountId;});getIntent().removeExtra(EnvironmentLauncher.ACCOUNT_ID);}}catch(Exception e){fail("无法读取账号记录",e);return;}
        boolean supportsProfiles;
        try { supportsProfiles = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE); }
        catch (RuntimeException error) { supportsProfiles = false; }
        if (!supportsProfiles) {
            if(state.current().isEnvironment()){memory.network();ready=true;showCurrent();return;}
            showMessage("请更新 Android System WebView", "此设备的网页组件不支持独立账号空间。请更新 Android System WebView / Chrome 后重新打开。", "打开更新页", () -> openExternal("https://play.google.com/store/apps/details?id=com.google.android.webview"));
            return;
        }
        try {
            store = new StateStore(getFilesDir()); state = store.load();memory.network();
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) throw new IllegalStateException("WebView 多档案能力不可用，请重新打开应用");
            // Delete before binding any WebView. Loaded profiles cannot be deleted in this process.
            List<String> cleared = new ArrayList<>();
            for (String id : state.pendingDeletes) {
                try { ProfileStore.getInstance().deleteProfile(WorkspaceState.profile(id)); cleared.add(id); }
                catch (IllegalStateException ignored) { /* retry at the next process start */ }
            }
            if (!cleared.isEmpty()) state=store.update(next->next.pendingDeletes.removeAll(cleared));
            try{if(memory.recovery==null)memory.recovery=new PageRecoveryManager(this);recovery=memory.recovery;recovery.attach(this);}catch(Exception e){toast("本地页面恢复暂不可用，网页可继续使用。请手动备份草稿。");}
            boolean retainedPages=!views.isEmpty();rebindLivePages();ready = true; showCurrent();acceptHandoffIntent(getIntent());
            if(retainedPages&&bundle!=null&&bundle.getBoolean("tabsOverview")){showTabOverview();overview.restoreUI(bundle.getString("tabFilter","all"),bundle.getString("tabQuery",""),bundle.getInt("tabPosition",0));}
        } catch (Exception e) { fail("无法读取账号记录", e); }
    }

    private void consumeNetworkSettingsIntent(Intent intent){
        if(intent==null||!intent.hasExtra("networkSettings"))return;
        if(intent.getBooleanExtra("networkSettings",false)&&!networkSettingsShowing)networkSettingsPending=true;
        intent.removeExtra("networkSettings");
    }
    private void openRequestedNetworkSettings(){
        networkSettingsPending=false;networkSettingsShowing=true;environmentLaunchPending=false;
        try{
            NetworkSheet.show(this,memory.network(),()->{
                networkSettingsShowing=false;
                if(isFinishing()||isDestroyed()||isChangingConfigurations()||fatal||!ready)return;
                try{state=store.load();environmentLaunchPending=false;rebindLivePages();showCurrent();}
                catch(Exception e){fail("无法读取账号记录",e);}
            });
        }catch(RuntimeException error){networkSettingsShowing=false;toast("网络设置暂时无法打开");}
    }

    private void buildLayout() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));keyboardVisible=insets.isVisible(WindowInsets.Type.ime());}
            else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());keyboardVisible=insets.getSystemWindowInsetBottom()>dp(150);}
            return insets;
        });
        header=new LinearLayout(this);header.setBackgroundColor(Color.WHITE);root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        browserHeader=new StudioBrowserHeader(this,new StudioBrowserHeader.Host(){
            @Override public void accounts(){chooseAccount();}
            @Override public void select(String tabId){selectHeaderTab(tabId);}
            @Override public void tabs(){showTabOverview();}
            @Override public void refresh(){refreshPage(false);}
            @Override public void stop(){stopPage();}
            @Override public void more(){showAccountMenu();}
        });header.addView(browserHeader,new LinearLayout.LayoutParams(-1,-2));
        networkNotice=new LinearLayout(this);networkNotice.setGravity(Gravity.CENTER_VERTICAL);networkNotice.setPadding(dp(12),dp(4),dp(8),dp(4));networkNotice.setBackgroundColor(GRAY);networkNotice.setVisibility(View.GONE);
        networkNoticeText=text("",13);networkNotice.addView(networkNoticeText,new LinearLayout.LayoutParams(0,-2,1));TextView networkAction=button("网络","查看网络设置");networkAction.setOnClickListener(v->NetworkSheet.show(this,memory.network()));networkNotice.addView(networkAction);root.addView(networkNotice,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));progress.setVisibility(View.INVISIBLE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        taskBanner=new LinearLayout(this);taskBanner.setGravity(Gravity.CENTER_VERTICAL);taskBanner.setBackgroundColor(GRAY);taskBanner.setVisibility(View.GONE);root.addView(taskBanner,new LinearLayout.LayoutParams(-1,-2));taskText=text("",12);taskText.setMaxLines(3);taskText.setPadding(dp(10),dp(6),dp(6),dp(6));taskBanner.addView(taskText,new LinearLayout.LayoutParams(0,-2,1));taskAction=button("停止","停止自动任务");taskBanner.addView(taskAction,new LinearLayout.LayoutParams(-2,StudioTypography.controlHeight(this)));TextView details=button("任务","查看网页任务");taskBanner.addView(details,new LinearLayout.LayoutParams(-2,StudioTypography.controlHeight(this)));details.setOnClickListener(v->showHandoffHistory());taskAction.setOnClickListener(v->{if(bannerJob==null)return;if(HandoffStore.active(bannerJob.optString("status")))try{handoffs.cancel(bannerJob.optString("id"),state.activeAccount);}catch(Exception e){toast("无法停止任务，请查看任务记录");}else taskBanner.setVisibility(View.GONE);});
        browserArea=new FrameLayout(this);root.addView(browserArea,new LinearLayout.LayoutParams(-1,0,1));content=new FrameLayout(this);browserArea.addView(content,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
    }

    private void renderHeader(){
        if(!ready||fatal||state==null||state.current().isEnvironment())return;
        WebView web=currentWeb();browserHeader.render(state.current(),navigationPending||web!=null&&loadingPages.getOrDefault(web,web.getProgress()<100),web!=null&&overview==null&&offlineSnapshot==null&&!nativePageBusy(web));
    }
    private void selectHeaderTab(String tabId){
        if(!ready||fatal||state.current().activeTab.equals(tabId))return;
        if(change(next->{WorkspaceState.Account account=next.current();if(account.tabs.stream().noneMatch(tab->tab.id.equals(tabId)))throw new IllegalStateException("标签页已关闭");account.activeTab=tabId;})){closeOverview();showCurrent();}
    }
    private boolean nativePageBusy(WebView web){
        HandoffController controller=handoffs==null?null:handoffs.controller();
        return pendingJob!=null||fileCallback!=null||mediaRequest!=null||memory!=null&&memory.network().busy()||controller!=null&&controller.owns(web);
    }
    private void cancelPageNavigation(){navigationTicket++;navigationPending=false;WebView pending=navigationWeb;navigationWeb=null;if(pending!=null)selectPage(pending,currentWeb()==pending);}
    private void restoreCacheMode(WebView web){Integer mode=forcedCacheModes.remove(web);if(mode!=null)web.getSettings().setCacheMode(mode);}
    private boolean samePage(WebView web,String account,String storage,String tab,String url,long epoch){
        if(!ready||fatal||!resumed||overview!=null||offlineSnapshot!=null||currentWeb()!=web||!state.activeAccount.equals(account)||!state.current().storageId.equals(storage)||!state.current().activeTab.equals(tab)||!java.util.Objects.equals(url,web.getUrl())||pageEpochs.getOrDefault(tab,-1L)!=epoch)return false;
        try{WorkspaceState latest=store.load();WorkspaceState.Account owner=latest.find(account);return latest.activeAccount.equals(account)&&owner.storageId.equals(storage)&&owner.activeTab.equals(tab);}catch(Exception error){return false;}
    }
    private void pageAction(Consumer<WebView> action){
        WebView web=currentWeb();if(web==null||!ready||fatal)return;
        if(overview!=null||offlineSnapshot!=null){toast("请先返回原网页，再操作页面");return;}
        if(navigationPending||nativePageBusy(web)){toast("页面或文件任务正在处理中，请完成后再操作");return;}
        String account=state.activeAccount,storage=state.current().storageId,tab=state.current().activeTab,url=web.getUrl();long epoch=pageEpochs.getOrDefault(tab,-1L);
        if(url==null)return;
        navigationPending=true;navigationWeb=web;selectPage(web,true);long ticket=++navigationTicket;renderHeader();
        recentHandler.postDelayed(()->{if(navigationPending&&navigationTicket==ticket){cancelPageNavigation();renderHeader();toast("页面暂未完成保护，请稍候重试");}},7000);
        String check="(function(){try{var visible=function(e){return !!e&&!!e.getClientRects().length;};var busy=Array.from(document.querySelectorAll('[aria-busy=\"true\"],[role=\"progressbar\"],[data-testid=\"stop-button\"],[data-testid=\"remove-file-button\"],[data-testid=\"attachment\"],button[aria-label*=\"Remove file\" i],button[aria-label*=\"Remove attachment\" i],button[aria-label*=\"移除附件\"],button[aria-label*=\"删除附件\"]')).some(visible)||Array.from(document.querySelectorAll('input[type=file]')).some(function(e){return e.files&&e.files.length;});var e=Array.from(document.querySelectorAll('#prompt-textarea,textarea,[contenteditable=\"true\"]')).find(visible);return JSON.stringify({busy:busy,draft:!!e&&!!(e.value||e.innerText||'')});}catch(e){return JSON.stringify({busy:true});}})()";
        try{web.evaluateJavascript(check,raw->{
            if(navigationTicket!=ticket||!navigationPending)return;
            if(!samePage(web,account,storage,tab,url,epoch)){cancelPageNavigation();renderHeader();return;}
            try{
                Object parsed=new org.json.JSONTokener(raw).nextValue();JSONObject value=parsed instanceof JSONObject?(JSONObject)parsed:new JSONObject(String.valueOf(parsed));
                if(value.optBoolean("busy",true)||nativePageBusy(web)){cancelPageNavigation();renderHeader();toast("网页正在生成或处理附件，请完成后再操作");return;}
                Runnable proceed=()->{if(navigationTicket!=ticket||!navigationPending)return;boolean current=samePage(web,account,storage,tab,url,epoch)&&!nativePageBusy(web);cancelPageNavigation();if(current){restoreCacheMode(web);try{action.accept(web);}catch(RuntimeException error){restoreCacheMode(web);loadingPages.put(web,false);toast("页面操作未完成，请重试");}}renderHeader();};
                if(recovery!=null)recovery.beforeNavigation(web,proceed,()->{if(navigationTicket==ticket){cancelPageNavigation();renderHeader();}});
                else if(!value.optBoolean("draft"))proceed.run();
                else{cancelPageNavigation();renderHeader();toast("当前草稿暂不能保护，请先复制备份后再操作");}
            }catch(Exception error){cancelPageNavigation();renderHeader();toast("无法确认页面状态，已保留当前页面");}
        });}catch(RuntimeException error){cancelPageNavigation();renderHeader();toast("页面暂不能操作，请重试");}
    }
    private void refreshPage(boolean force){
        pageAction(web->{String url=web.getUrl();if(!NavigationPolicy.internal(url)){toast("当前页面不能刷新，请重新打开原页面");return;}if(force){forcedCacheModes.put(web,web.getSettings().getCacheMode());web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);}loadingPages.put(web,true);web.loadUrl(url);});
    }
    private void historyPage(boolean forward){pageAction(web->{if(forward){if(web.canGoForward())web.goForward();}else if(web.canGoBack())web.goBack();});}
    private void stopPage(){
        cancelPageNavigation();WebView web=currentWeb();if(web==null)return;if(nativePageBusy(web)){toast("页面或文件任务正在处理中，请完成后再停止加载");renderHeader();return;}
        web.stopLoading();loadingPages.put(web,false);restoreCacheMode(web);renderHeader();
    }
    private void rebindLivePages(){
        int retained=0;for(String id:new ArrayList<>(views.keySet())){String account=memory.accounts.get(id),storage=memory.storage.get(id);WorkspaceState.Account found=null;for(WorkspaceState.Account a:state.accounts)if(a.id.equals(account)&&a.storageId.equals(storage)&&(a.tabs.stream().anyMatch(t->t.id.equals(id))||a.closedTabs.stream().anyMatch(t->t.id.equals(id)))){found=a;break;}if(found==null){memory.destroy(id);continue;}
            WebView web=views.get(id);if(web.getContext() instanceof MutableContextWrapper)((MutableContextWrapper)web.getContext()).setBaseContext(this);configureWebView(web,account,id,storage,false);retained++;
        }if(retained>0)memory.diagnostics.event("activity-retained",null,null,"pages:"+retained);
    }
    private void showCurrent(){
        if(!ready||fatal)return;
        cancelPageNavigation();
        if(networkSettingsPending&&!networkSettingsShowing)openRequestedNetworkSettings();
        if(state.current().isEnvironment()){
            if(networkSettingsShowing||networkSettingsPending)return;
            if(environmentLaunchPending&&state.activeAccount.equals(redirectedEnvironment))return;
            environmentLaunchPending=true;redirectedEnvironment=state.activeAccount;
            try{for(WebView held:views.values())selectPage(held,false);EnvironmentLauncher.open(this,state.activeAccount);}
            catch(Exception e){environmentLaunchPending=false;redirectedEnvironment=null;toast(e.getMessage()==null?"账号环境暂不可用":e.getMessage());}
            return;
        }
        redirectedEnvironment=null;environmentLaunchPending=false;
        if(recovery!=null)recovery.checkpointVisible();if(snapshotOwner!=null)recoveryHideSnapshot(snapshotOwner);cancelRequests();renderHeader();WorkspaceState.Account account=state.current();WorkspaceState.Tab tab=account.current();recentClosed.remove(tab.id);pruneRecent();
        try{
            for(Map.Entry<String,WebView> held:views.entrySet())selectPage(held.getValue(),held.getKey().equals(tab.id));memory.refreshExecutionPolicy();WebView web=views.get(tab.id);boolean created=web==null;
            if(created&&!memory.network().ready()){networkChanged();return;}
            if(created){web=makeWebView(account.id,tab.id,account.storageId);WebView fresh=web;String address=tab.url;if(recovery!=null)recovery.prepare(fresh,address,()->fresh.loadUrl(address));else fresh.loadUrl(address);}
            for(WorkspaceState.Account a:state.accounts)for(WorkspaceState.Tab t:a.tabs){WebView existing=views.get(t.id);if(existing==null)continue;if(existing.getParent()!=content){if(existing.getParent() instanceof ViewGroup)((ViewGroup)existing.getParent()).removeView(existing);content.addView(existing,new FrameLayout.LayoutParams(-1,-1));}selectPage(existing,t.id.equals(tab.id));}
            web.bringToFront();if(overview==null&&offlineSnapshot==null)web.requestFocus();web.onResume();if(recovery!=null){if(!created&&web.getProgress()==100)recovery.ready(web);recovery.visible();}if(overview!=null)overview.refresh();renderHeader();
        }catch(Exception e){fail("标签页暂时无法打开",e);}
    }
    void networkChanged(){
        if(memory==null||memory.owner()!=this||networkNotice==null)return;
        NetworkSession network=memory.network();boolean waiting=!network.ready();networkNotice.setVisibility(waiting?View.VISIBLE:View.GONE);networkNoticeText.setText(network.status()+" · 网页和草稿保留");
        if(!waiting&&ready&&!fatal&&state!=null&&!views.containsKey(state.current().activeTab)&&(!state.current().isEnvironment()||resumed))showCurrent();
        if(!waiting)drainHandoff();
    }
    private void selectPage(WebView web,boolean selected){if(web instanceof RetainedWebView){((RetainedWebView)web).blockInput(overview!=null||snapshotOwner==web||navigationPending&&navigationWeb==web);((RetainedWebView)web).selectPage(selected);}else web.setVisibility(selected?View.VISIBLE:View.INVISIBLE);}

    void recoveryShowSnapshot(WebView web,JSONObject page,String problem){
        if(page==null||!ready||fatal||isFinishing()||isDestroyed()||overview!=null||currentWeb()!=web)return;
        try{
            if(offlineSnapshot==null||snapshotOwner!=web){
                if(snapshotOwner!=null)recoveryHideSnapshot(snapshotOwner);snapshotOwner=web;
                offlineSnapshot=new OfflineSnapshotView(this,page,()->{if(!ready||fatal||currentWeb()!=web||state==null)return;web.stopLoading();web.loadUrl(state.current().current().url);});
                browserArea.addView(offlineSnapshot,new FrameLayout.LayoutParams(-1,-1));selectPage(web,true);web.clearFocus();
            }
            if(!problem.isEmpty())offlineSnapshot.unavailable(problem);offlineSnapshot.bringToFront();renderHeader();
        }catch(Exception error){if(snapshotOwner==web)recoveryHideSnapshot(web);recoveryNotice("已保存的页面暂不可查看；网页与本地记录保留。");}
    }
    void recoveryHideSnapshot(WebView web){if(snapshotOwner!=web)return;OfflineSnapshotView old=offlineSnapshot;offlineSnapshot=null;snapshotOwner=null;if(old!=null)browserArea.removeView(old);if(web!=null)selectPage(web,currentWeb()==web);renderHeader();}
    boolean recoveryCanCapturePicture(WebView web){
        if(BuildConfig.NETWORK_TEST||BuildConfig.ENVIRONMENT_TEST)return false; // Authorized CI contains no image capture.
        if(!ready||fatal||!resumed||isFinishing()||isDestroyed()||keyboardVisible||overview!=null||offlineSnapshot!=null||undoBar!=null||currentWeb()!=web||!web.isShown()||web.getAlpha()<0.99f)return false;
        View decor=getWindow().peekDecorView();if(decor==null||!decor.hasWindowFocus())return false;Rect visible=new Rect();return web.getGlobalVisibleRect(visible)&&visible.width()==web.getWidth()&&visible.height()==web.getHeight();
    }
    void recoveryCapturePicture(WebView web,Consumer<Bitmap> done){
        if(!recoveryCanCapturePicture(web)){done.accept(null);return;}View decor=getWindow().peekDecorView();int width=web.getWidth(),height=web.getHeight();int[] location=new int[2];web.getLocationInWindow(location);
        Rect source=new Rect(location[0],location[1],location[0]+width,location[1]+height);
        if(width<=0||height<=0||source.left<0||source.top<0||source.right>decor.getWidth()||source.bottom>decor.getHeight()){done.accept(null);return;}
        double scale=Math.min(1d,Math.min(720d/width,1440d/height));Bitmap bitmap;
        try{bitmap=Bitmap.createBitmap(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),Bitmap.Config.ARGB_8888);}catch(OutOfMemoryError error){done.accept(null);return;}
        try{android.view.PixelCopy.request(getWindow(),source,bitmap,result->{if(result==android.view.PixelCopy.SUCCESS&&recoveryCanCapturePicture(web))done.accept(bitmap);else{bitmap.recycle();done.accept(null);}},new android.os.Handler(android.os.Looper.getMainLooper()));}
        catch(RuntimeException error){bitmap.recycle();done.accept(null);}
    }

    private void showTabOverview(){
        if(!ready||fatal||overview!=null)return;cancelPageNavigation();if(handoffs!=null&&handoffs.controller()!=null&&handoffs.controller().ownedBy(this))handoffs.controller().cancel("已打开标签总览，自动发送已停止，请检查目标对话");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);
        overview=new TabOverview(this,new TabOverview.Host(){
            public WorkspaceState state(){return state;}
            public void activate(String account,String tab){if(change(next->{next.activeAccount=account;next.find(account).activeTab=tab;})){closeOverview();showCurrent();}}
            public void add(String account){if(change(next->{next.activeAccount=account;next.addTab(next.find(account));})){closeOverview();showCurrent();}}
            public void close(String account,String tab){closeTab(account,tab);}
            public void restore(String account,String tab){restoreTab(account,tab);}
            public void pin(String account,String id){if(change(next->{WorkspaceState.Tab tab=next.find(account).tabs.stream().filter(t->t.id.equals(id)).findFirst().orElseThrow();tab.pinned=!tab.pinned;}))refreshTabs();}
            public void rename(String account,String id,String title){if(change(next->{WorkspaceState.Tab tab=next.find(account).tabs.stream().filter(t->t.id.equals(id)).findFirst().orElseThrow();tab.title=title;tab.customTitle=true;}))refreshTabs();}
            public void move(String account,String id,int direction){if(change(next->{WorkspaceState.Account a=next.find(account);int from=-1;for(int n=0;n<a.tabs.size();n++)if(a.tabs.get(n).id.equals(id))from=n;int to=from+direction;if(from>=0&&to>=0&&to<a.tabs.size())java.util.Collections.swap(a.tabs,from,to);}))refreshTabs();}
            public void done(){closeOverview();}
        });browserArea.addView(overview,new FrameLayout.LayoutParams(-1,-1));WebView behind=currentWeb();if(behind!=null)selectPage(behind,true);renderHeader();
    }
    private void closeOverview(){if(overview==null)return;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(overview.getWindowToken(),0);browserArea.removeView(overview);overview=null;WebView web=currentWeb();if(web!=null){selectPage(web,true);if(offlineSnapshot==null)web.requestFocus();}if(recovery!=null)recovery.visible();renderHeader();}
    private void refreshTabs(){renderHeader();if(overview!=null)overview.refresh();}
    private void restoreTab(String account,String id){if(change(next->{WorkspaceState.Account a=next.find(account);if(a.tabs.size()>=WorkspaceState.MAX_TABS)throw new IllegalStateException("标签页已达上限");WorkspaceState.Tab tab=a.closedTabs.stream().filter(t->t.id.equals(id)).findFirst().orElseThrow();a.closedTabs.remove(tab);a.tabs.add(tab);a.activeTab=id;next.activeAccount=account;})){if(undoBar!=null){browserArea.removeView(undoBar);undoBar=null;}closeOverview();showCurrent();}}
    private void showUndo(String account,String tab,String title){
        if(undoBar!=null)browserArea.removeView(undoBar);LinearLayout bar=new LinearLayout(this);undoBar=bar;bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(14),dp(3),dp(4),dp(3));bar.setBackground(round(INK));TextView label=text("已关闭 "+title,12);label.setTextColor(Color.WHITE);label.setMaxLines(2);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView undo=button("撤销","撤销关闭标签页");undo.setTextColor(Color.WHITE);bar.addView(undo,new LinearLayout.LayoutParams(dp(56),dp(44)));undo.setOnClickListener(v->restoreTab(account,tab));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);p.setMargins(dp(16),0,dp(16),dp(overview==null?14:72));browserArea.addView(bar,p);long ticket=++undoSerial;recentHandler.postDelayed(()->{if(ticket==undoSerial&&undoBar==bar){browserArea.removeView(bar);undoBar=null;}},6000);
    }
    private void showDiagnostics(){
        String provider="未知";try{android.content.pm.PackageInfo info=WebView.getCurrentWebViewPackage();if(info!=null)provider=info.packageName+" "+info.versionName;}catch(RuntimeException ignored){}PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
        StringBuilder report=new StringBuilder("Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\nWebView："+provider+"\n保留的网页："+views.size()+"\n页面执行："+(memory.retainsExecution()?"保持活跃":"跟随系统调度")+"\n后台任务："+(KeepaliveService.running()?"运行中":"未开启")+"\n省电模式："+(power.isPowerSaveMode()?"开启":"关闭")+"\n系统休眠："+(power.isDeviceIdleMode()?"是":"否")+"\n电池优化豁免："+(power.isIgnoringBatteryOptimizations(getPackageName())?"已允许":"未允许")+"\n\n");org.json.JSONArray events=memory.diagnostics.snapshot();java.text.DateFormat time=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault());for(int i=events.length()-1;i>=Math.max(0,events.length()-18);i--){JSONObject event=events.optJSONObject(i);report.append(time.format(new java.util.Date(event.optLong("time")))).append(" · ").append(BrowserDiagnostics.label(event.optString("type")));String eventAccount=event.optString("account");if(state!=null)for(WorkspaceState.Account a:state.accounts)if(a.id.equals(eventAccount)){report.append(" · ").append(a.name);break;}String id=event.optString("tab");if(!id.isEmpty())report.append(" · 标签 ").append(id.substring(0,6));if(event.has("detail"))report.append(" (").append(event.optString("detail")).append(")");report.append('\n');}
        report.append("\n诊断不记录网页正文、完整网址、Cookie 或处理指令。锁屏省电、系统回收与网页服务本身仍可能中断连接；不会为恢复连接自动重发消息。");
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),0,dp(20),0);TextView text=text(report.toString(),12);text.setTextIsSelectable(true);android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(text);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(Math.min(280,getResources().getConfiguration().screenHeightDp/3))));TextView copy=button("复制诊断信息","复制诊断信息");copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("浏览器诊断",report));toast("诊断已复制，不含网页登录内容");});body.addView(copy);TextView battery=button("打开系统电池设置","打开系统电池设置");battery.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}});body.addView(battery);
        new AlertDialog.Builder(this).setTitle("保活与诊断").setView(body).setNegativeButton("关闭",null).setPositiveButton(KeepaliveService.enabled(this)?"关闭后台任务":"开启后台任务",(d,n)->{if(KeepaliveService.enabled(this)){KeepaliveService.disable(this);toast("已关闭后台任务模式，网页仍保留");}else new AlertDialog.Builder(this).setTitle("在后台继续网页任务").setMessage("开启后显示常驻通知，可从通知停止。此模式会增加耗电，系统省电或进程回收时仍可能断开；不会在后台自动发送处理指令。").setNegativeButton("取消",null).setPositiveButton("开启",(dialog,which)->enableKeepalive()).show();}).show();
    }
    private void enableKeepalive(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},30);return;}try{KeepaliveService.enable(this);toast("后台任务模式已开启，可从通知停止");}catch(RuntimeException e){KeepaliveService.disable(this);memory.diagnostics.event("background-error",null,null,e.getClass().getSimpleName());toast("系统未允许后台任务，请查看保活与诊断");}}

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView makeWebView(String accountId, String tabId, String storageId) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) throw new IllegalStateException("WebView 不支持独立账号空间");
        MutableContextWrapper wrapper=new MutableContextWrapper(getApplicationContext());wrapper.setBaseContext(this);WebView web=new RetainedWebView(wrapper);
        try { WebViewCompat.setProfile(web, WorkspaceState.profile(storageId)); }
        catch (RuntimeException e) { web.destroy(); throw e; }
        memory.register(tabId,accountId,storageId,web);configureWebView(web,accountId,tabId,storageId,true);return web;
    }
    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView(WebView web,String accountId,String tabId,String storageId,boolean initial){
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSafeBrowsingEnabled(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.WHITE);
        loadingPages.putIfAbsent(web,web.getProgress()<100);web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,false);pageEpochs.putIfAbsent(tabId,0L);if(initial&&recovery!=null)recovery.bind(web,accountId,storageId,tabId);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){pageEpochs.put(tabId,pageEpochs.getOrDefault(tabId,0L)+1);if(recovery!=null)recovery.navigating(view,url);memory.diagnostics.event("page-load",accountId,tabId,null);loadingPages.put(view,true);if(active(tabId)){cancelPageNavigation();renderHeader();}}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if(!memory.networkReady())return null; /* WebSettings blocks network; preserve the native failure. */if((BuildConfig.DEBUG||BuildConfig.ENVIRONMENT_TEST)&&handoffFixture!=null)return handoffFixture.response(accountId,request);return null;}
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame()) return !NavigationPolicy.https(url);
                if (NavigationPolicy.internal(url)) return false;
                if (request.isForMainFrame() && request.hasGesture() && NavigationPolicy.https(url)) askExternal(url);else if(request.isForMainFrame())memory.diagnostics.event("unsupported-link",accountId,tabId,Uri.parse(url).getScheme());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                rememberURL(accountId,tabId,url);if(recovery!=null)recovery.ready(view);loadingPages.put(view,false);restoreCacheMode(view);if(active(tabId))renderHeader();
            }
            @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){rememberURL(accountId,tabId,url);if(recovery!=null)recovery.ready(view);if(active(tabId))renderHeader();}
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if(!request.isForMainFrame())return;loadingPages.put(view,false);restoreCacheMode(view);if(active(tabId))renderHeader();memory.diagnostics.event("page-error",accountId,tabId,"code:"+error.getErrorCode());boolean saved=recovery!=null&&recovery.failed(view,request.getUrl().toString(),false);if(active(tabId)&&resumed&&!saved)showMessage("网页暂时无法加载", "请检查网络后重试。账号登录数据仍保留。", "重试", () -> { showCurrent(); currentWeb().loadUrl(state.current().current().url); });
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                if(!request.isForMainFrame()||recovery==null)return;int code=response.getStatusCode();
                if(code==401||code==403)recovery.revealForAuthentication(view);else if(code>=400)recovery.failed(view,request.getUrl().toString(),false);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();loadingPages.put(view,false);restoreCacheMode(view);if(active(tabId))renderHeader();memory.diagnostics.event("ssl-error",accountId,tabId,null);boolean saved=recovery!=null&&recovery.failed(view,error.getUrl(),true);if(active(tabId)&&resumed&&!saved)showMessage("连接证书校验失败", "请检查设备时间与网络，确认后重新打开。", "重试", () -> { showCurrent(); currentWeb().reload(); });
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                memory.diagnostics.event(detail.didCrash()?"renderer-crash":"renderer-reclaimed",accountId,tabId,"priority:"+detail.rendererPriorityAtExit());restoreCacheMode(view);loadingPages.remove(view);memory.destroy(tabId);if(overview!=null)overview.refresh();if(active(tabId))renderHeader();
                if (active(tabId)&&resumed) showMessage("网页进程已退出", "重新加载后会尝试恢复最近保存的阅读位置和草稿；网页附件及生成任务需手动检查。", "重新加载", MainActivity.this::showCurrent);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (snapshotOwner==view||title == null || title.trim().isEmpty() || !exists(accountId, tabId)||state.find(accountId).tabs.stream().anyMatch(t->t.id.equals(tabId)&&(t.customTitle||t.title.equals(title.substring(0,Math.min(120,title.length())))))) return;
                if (change(s -> s.find(accountId).tabs.stream().filter(t -> t.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")).title = title.substring(0, Math.min(120, title.length()))) && active(tabId)) renderHeader();
            }
            @Override public void onProgressChanged(WebView view, int value) {
                loadingPages.put(view,value<100);if (active(tabId)) { progress.setProgress(value); progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);renderHeader(); }
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
                if (!gesture || !exists(accountId, tabId) || fatal || !memory.networkReady()) return false;
                if (!change(s -> s.addTab(s.find(accountId)))) return false;
                WorkspaceState.Account a = state.find(accountId); WorkspaceState.Tab popup = a.current();
                try {
                    WebView child = makeWebView(accountId, popup.id, a.storageId); views.put(popup.id, child); popupOwners.put(popup.id, tabId);
                    ((WebView.WebViewTransport) result.obj).setWebView(child); result.sendToTarget();
                    if (state.activeAccount.equals(accountId)) showCurrent(); return true;
                } catch (RuntimeException error) { closeTab(accountId, popup.id); toast("无法创建登录弹窗，请重试"); return false; }
            }
            @Override public void onCloseWindow(WebView window) { if (exists(accountId, tabId)) closeTab(accountId, tabId); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                HandoffController controller=handoffs==null?null:handoffs.controller();if(controller!=null&&controller.owns(view))return controller.chooser(view,callback,params);
                if (!active(tabId)) return false;
                cancelRequests(); fileCallback = callback; fileOwner = tabId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                String[] accepts = params.getAcceptTypes(); if (accepts.length > 0 && !accepts[0].trim().isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, accepts);
                try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException e) { cancelRequests(); toast("设备没有可用的文件选择器"); }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> requestMedia(request, tabId)); }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (mediaRequest == request) { mediaRequest = null; mediaOwner = null; } }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
        });
        web.setDownloadListener((url,userAgent,disposition,type,size)->{
            if(active(tabId))new AlertDialog.Builder(this).setTitle("下载文件")
                .setMessage(state.find(accountId).name+" · 请选择保存方式。资产库中的副本按账号归档。")
                .setNegativeButton("取消",null).setNeutralButton("另存为…",(d,w)->downloads().choose(web,url,userAgent,disposition,type))
                .setPositiveButton("保存到资产库",(d,w)->{if(exists(accountId,tabId))assetDownloads().start(web,accountId,state.find(accountId).name,url,userAgent,disposition,type);}).show();
        });

    }

    private DownloadHelper downloadHelper;
    private AssetDownloads assetDownloads;
    private AssetDownloads assetDownloads(){if(memory.assetDownloads==null)memory.assetDownloads=new AssetDownloads(getApplicationContext());assetDownloads=memory.assetDownloads;return assetDownloads;}
    private DownloadHelper downloads() { if(memory.downloads==null)memory.downloads=new DownloadHelper(this);else memory.downloads.attach(this);downloadHelper=memory.downloads;return downloadHelper; }
    private void showAccountMenu(){
        if(!ready||fatal){toast("账号正在准备，请稍候");return;}
        StudioSheet sheet=new StudioSheet(this,"更多","");WebView web=currentWeb();boolean enabled=web!=null&&overview==null&&offlineSnapshot==null&&!navigationPending&&!nativePageBusy(web);
        StudioPageActions.navigation(sheet,enabled&&web.canGoBack(),enabled&&web.canGoForward(),()->{sheet.dismiss();historyPage(false);},()->{sheet.dismiss();historyPage(true);});
        StudioPageActions.action(sheet,"强制刷新","绕过本页缓存，保留登录与本地草稿","refresh",enabled,()->{sheet.dismiss();refreshPage(true);});
        sheet.row("标签管理","","",()->{sheet.dismiss();showTabOverview();});
        sheet.row("新建标签页","","plus",()->{sheet.dismiss();if(change(next->next.addTab(next.current())))showCurrent();});
        sheet.row("资产库","","folder",()->{sheet.dismiss();startActivity(new Intent(this,AssetWorkbenchActivity.class).putExtra(AssetWorkbenchActivity.ACCOUNT,state.activeAccount));});
        sheet.row("能力库","","spark",()->{sheet.dismiss();startActivity(new Intent(this,AssetWorkbenchActivity.class).putExtra(AssetWorkbenchActivity.ACCOUNT,state.activeAccount).putExtra("skills",true));});
        sheet.row("网络",memory.network().status(),"",()->{sheet.dismiss();NetworkSheet.show(this,memory.network());});
        sheet.row("处理记录","","file",()->{sheet.dismiss();showHandoffHistory();});
        sheet.row("账号管理","","account",()->{sheet.dismiss();manageAccount();});sheet.show();
    }
    private void chooseAccount(){
        StudioSheet sheet=new StudioSheet(this,"切换账号","");
        for(WorkspaceState.Account account:state.accounts){
            LinearLayout row=sheet.row(account.name,account.email,"",()->{sheet.dismiss();if(change(next->next.activeAccount=account.id)){closeOverview();showCurrent();}});
            View dot=new View(this);GradientDrawable point=new GradientDrawable();point.setColor(Color.parseColor(account.color));point.setShape(GradientDrawable.OVAL);dot.setBackground(point);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(6),dp(6));p.rightMargin=dp(12);row.addView(dot,0,p);
            if(account.id.equals(state.activeAccount)){ImageView check=new ImageView(this);check.setImageDrawable(new StudioIcon("check"));row.addView(check,new LinearLayout.LayoutParams(dp(20),dp(20)));}
        }
        sheet.action("添加账号",true,()->{sheet.dismiss();editAccount(null);});
        sheet.action("管理当前账号",false,()->{sheet.dismiss();manageAccount();});sheet.show();
    }
    private void editAccount(String accountId) {
        AccountEditor.show(this,store,accountId,saved->{state=saved;showCurrent();});
    }

    private void manageAccount() {
        String id = state.activeAccount;
        new AlertDialog.Builder(this).setTitle(state.current().name).setItems(new String[]{"编辑名称与邮箱备注", "向前移动账号", "向后移动账号", "清除本机登录数据", "移除账号", "本机草稿"}, (d, i) -> {
            if(i==5){showLocalDraft();return;}
            if (i == 0) editAccount(id);
            else if (i == 1 || i == 2) { if (change(s -> { int index = s.accounts.indexOf(s.find(id)), target = index + (i == 1 ? -1 : 1); if (target >= 0 && target < s.accounts.size()) java.util.Collections.swap(s.accounts, index, target); })) renderHeader(); }
            else new AlertDialog.Builder(this).setTitle(i == 3 ? "清除本机登录数据？" : "移除此账号？")
                .setMessage("此账号的本机标签、登录和本地恢复草稿将清除，云端对话不受影响。旧浏览器档案在下次启动时清理，其他账号保持独立。"+(i==3?"资产库文件保留。":"移除前需先导出并清空此账号资产。"))
                .setNegativeButton("取消", null).setPositiveButton("确认", (x, y) -> {
                    if (i == 4 && state.accounts.size() == 1) { toast("请至少保留一个账号空间"); return; }
                    try {
                        if(i==4)AssetLedger.withEmptyAccount(getFilesDir(),id,()->finishAccountChange(id,true));
                        else finishAccountChange(id,false);
                    }catch(Exception e){toast(e.getMessage()==null?"账号操作未完成":e.getMessage());}

                }).show();
        }).show();
    }
    private void finishAccountChange(String id,boolean remove) {
        List<String> ids=new ArrayList<>();for(WorkspaceState.Tab t:state.find(id).tabs)ids.add(t.id);
        for(WorkspaceState.Tab t:state.find(id).closedTabs)ids.add(t.id);
        String erasedStorage=state.find(id).storageId;
        memory.network().invalidateAccount(id);
        try{if(recovery!=null)recovery.clear(id,state.find(id).storageId);else new PageRecoveryStore(getFilesDir(),DevicePayloadKeys.recoveryCipher(getApplicationContext())).clear(id,state.find(id).storageId);store.revoke(state.find(id).storageId);}catch(Exception e){fail("无法保护账号清除记录",e);return;}
        cancelRequests();for(String tabId:ids)destroyView(tabId);
        if(change(s->{if(remove)s.removeAccount(s.find(id));else if(s.find(id).storageId.equals(erasedStorage))s.clearAccount(s.find(id));}))showCurrent();
        else fail("账号记录无法保存",new IllegalStateException("已停止使用旧空间，请重新打开应用"));
    }
    private void showTabMenu(String accountId, String tabId) {
        WorkspaceState.Tab tab = state.find(accountId).tabs.stream().filter(t -> t.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab"));
        new AlertDialog.Builder(this).setTitle(tab.title).setItems(new String[]{tab.pinned ? "取消固定" : "固定标签页", "向前移动", "向后移动", "关闭标签页"}, (d, i) -> {
            if (i == 3) { closeTab(accountId, tabId); return; }
            if (change(s -> { WorkspaceState.Account a = s.find(accountId); WorkspaceState.Tab t = a.tabs.stream().filter(v -> v.id.equals(tabId)).findFirst().orElseThrow(() -> new IllegalStateException("Missing account or tab")); if (i == 0) t.pinned = !t.pinned; else { int from = a.tabs.indexOf(t), to = from + (i == 1 ? -1 : 1); if (to >= 0 && to < a.tabs.size()) java.util.Collections.swap(a.tabs, from, to); } })) renderHeader();
        }).show();
    }
    private void closeTab(String accountId, String tabId) {
        WorkspaceState.Account owner=state.find(accountId);String storage=owner.storageId,evicted=owner.closedTabs.size()>=10?owner.closedTabs.get(0).id:null;
        if (change(s -> s.closeTab(s.find(accountId), tabId))) {
            recentClosed.put(tabId,android.os.SystemClock.elapsedRealtime()+600000);showCurrent();pruneRecent();showUndo(accountId,tabId,owner.tabs.stream().filter(t->t.id.equals(tabId)).findFirst().map(t->t.title).orElse("标签页"));
            if(evicted!=null){destroyView(evicted);if(recovery!=null)recovery.forget(accountId,storage,evicted);}
        }
    }
    private void destroyView(String tabId){WebView web=views.get(tabId);if(web!=null){if(navigationWeb==web)cancelPageNavigation();restoreCacheMode(web);loadingPages.remove(web);}memory.destroy(tabId);}
    private void pruneRecent(){memory.pruneRecent();}
    WebView recoveryCurrentWeb(){return currentWeb();}
    boolean recoveryValid(String account,String storage,String tab,WebView web){
        try{WorkspaceState.Account owner=state.find(account);return ready&&!fatal&&views.get(tab)==web&&owner.storageId.equals(storage)&&(owner.tabs.stream().anyMatch(t->t.id.equals(tab))||owner.closedTabs.stream().anyMatch(t->t.id.equals(tab)));}catch(Exception e){return false;}
    }
    void recoveryNotice(String message){if(!isFinishing()&&!isDestroyed())toast(message);}
    private void showLocalDraft(){
        if(recovery==null){toast("本地恢复暂不可用，请手动备份网页内容。");return;}
        recovery.localDraft(record->{if(isFinishing()||isDestroyed())return;
            if(record==null){toast("当前页面没有本地恢复草稿。");return;}
            String draft=record.optString("alternativeDraft",record.optString("draft"));if(draft.isEmpty()){toast("当前页面没有本地恢复草稿。");return;}
            String preview=draft.length()>4000?draft.substring(0,4000)+"\n\n预览已截短，复制会包含完整草稿。":draft;
            new AlertDialog.Builder(this).setTitle("本地恢复草稿 · "+state.current().name).setMessage(preview).setNegativeButton("关闭",null).setPositiveButton("复制草稿",(d,w)->{
                android.content.ClipData clip=android.content.ClipData.newPlainText("本地恢复草稿",draft);android.os.PersistableBundle extra=new android.os.PersistableBundle();extra.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extra);
                ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);toast("完整草稿已复制，请在网页确认后粘贴。");
            }).show();
        });
    }
    private boolean change(Consumer<WorkspaceState> mutation) {
        if (fatal) return false;
        try { state=store.update(mutation); return true; }
        catch (Exception e) { toast("操作未保存：" + e.getMessage()); return false; }
    }
    private boolean exists(String accountId, String tabId) { return !fatal && state.accounts.stream().anyMatch(a -> a.id.equals(accountId) && a.tabs.stream().anyMatch(t -> t.id.equals(tabId))); }
    private boolean active(String tabId) { return ready && !fatal && state.current().activeTab.equals(tabId); }
    private WebView currentWeb() { return ready && !fatal ? views.get(state.current().activeTab) : null; }

    private void requestMedia(PermissionRequest request, String tabId) {
        if (!active(tabId) || !NavigationPolicy.chat(request.getOrigin().toString())) { request.deny(); return; }
        List<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needed.add(Manifest.permission.RECORD_AUDIO);
            else if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needed.add(Manifest.permission.CAMERA);
            else { request.deny(); return; }
        }
        if (needed.isEmpty()) { request.deny(); return; }
        cancelRequests(); mediaRequest = request; mediaOwner = tabId;
        new AlertDialog.Builder(this).setTitle("允许网页使用麦克风 / 摄像头？").setMessage("来源：" + request.getOrigin() + "\n账号：" + state.current().name)
            .setNegativeButton("拒绝", (d, w) -> cancelRequests()).setOnCancelListener(d -> cancelRequests())
            .setPositiveButton("允许", (d, w) -> { if (mediaRequest == request && active(tabId)) requestPermissions(needed.toArray(new String[0]), 20); }).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if(code==30){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)enableKeepalive();else toast("未开启通知，后台任务模式未启动");return;}
        if (code == 20 && mediaRequest != null) {
            PermissionRequest request = mediaRequest; boolean allowed = active(mediaOwner) && grants.length > 0;
            for (int result : grants) allowed &= result == PackageManager.PERMISSION_GRANTED;
            mediaRequest = null; mediaOwner = null; if (allowed) request.grant(request.getResources()); else request.deny();
        }
    }
    @Override protected void onActivityResult(int code, int result, Intent intent) {
        super.onActivityResult(code, result, intent);
        if (code == 10 && fileCallback != null) {
            List<Uri> uris = new ArrayList<>();
            if (result == RESULT_OK && intent != null && active(fileOwner)) {
                if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) uris.add(intent.getClipData().getItemAt(i).getUri());
                else if (intent.getData() != null) uris.add(intent.getData());
            }
            uris.removeIf(uri -> !"content".equals(uri.getScheme())); ValueCallback<Uri[]> callback = fileCallback; fileCallback = null; fileOwner = null; callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        }
        if (code == DownloadHelper.REQUEST_SAVE && downloadHelper != null) downloadHelper.onResult(result, intent);
    }
    private void cancelRequests() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; fileOwner = null; }
        if (mediaRequest != null) { mediaRequest.deny(); mediaRequest = null; mediaOwner = null; }
    }
    private void navigateBack() {
        if(overview!=null){closeOverview();return;}
        WebView web = currentWeb();
        if (web != null && web.canGoBack()) historyPage(false);
        else if (ready && !fatal && popupOwners.containsKey(state.current().activeTab)) closeTab(state.activeAccount, state.current().activeTab);
        else moveTaskToBack(true);
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(memory!=null&&ready&&!fatal){
            try{
                WorkspaceState latest=store.load();boolean selectionChanged=!latest.activeAccount.equals(state.activeAccount)||!latest.current().activeTab.equals(state.current().activeTab)||!latest.current().storageId.equals(state.current().storageId);
                state=latest;
                boolean reattach=memory.owner()!=this;if(reattach)memory.attach(this);
                memory.network().routes.recheckCoreState();
                if(state.current().isEnvironment()){
                    // Initial creation already launches once; later Recents/app resumes reopen the selected environment.
                    if(networkSettingsPending||!networkSettingsShowing&&!environmentLaunchPending)showCurrent();
                }else if(selectionChanged||reattach){rebindLivePages();showCurrent();}
            }catch(Exception e){fail("无法读取账号记录",e);}
        }
        for(WebView web:views.values())web.onResume();if(recovery!=null)recovery.resume();if(ready&&!fatal&&KeepaliveService.enabled(this)&&!KeepaliveService.running())enableKeepalive();drainHandoff();
    }

    @Override protected void onPause(){cancelPageNavigation();for(WebView web:new ArrayList<>(forcedCacheModes.keySet()))restoreCacheMode(web);environmentLaunchPending=false;if(recovery!=null)recovery.pause();resumed=false;if(handoffs!=null&&handoffs.controller()!=null&&handoffs.controller().ownedBy(this))handoffs.controller().cancel("应用已离开前台，已停止自动发送；请检查目标对话");if(memory==null||!memory.retainsExecution())for(WebView web:views.values())web.onPause();super.onPause();}
    @Override protected void onStop(){try{if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))for(WebView web:views.values())WebViewCompat.getProfile(web).getCookieManager().flush();}catch(RuntimeException ignored){}super.onStop();}
    @Override protected void onSaveInstanceState(Bundle bundle){bundle.putBoolean("networkSettingsOpen",networkSettingsShowing||networkSettingsPending);if(overview!=null){bundle.putBoolean("tabsOverview",true);bundle.putString("tabFilter",overview.filter());bundle.putString("tabQuery",overview.query());bundle.putInt("tabPosition",overview.position());}super.onSaveInstanceState(bundle);}
    @Override protected void onDestroy(){
        if(handoffs!=null&&handoffs.controller()!=null&&handoffs.controller().ownedBy(this))handoffs.controller().cancel("窗口已关闭，未确认的发送不会自动重试");recentHandler.removeCallbacksAndMessages(null);cancelRequests();
        if(memory!=null&&memory.owner()==this){if(!fatal&&(!isFinishing()||KeepaliveService.running()))memory.detach(this);else memory.clear();}ready=false;super.onDestroy();
    }
    private void fail(String title, Exception error) {
        closeOverview();KeepaliveService.disable(this);if(memory!=null)memory.closeNetwork();fatal = true; ready = false; cancelRequests(); for (String id : new ArrayList<>(views.keySet())) destroyView(id);
        header.removeAllViews(); showMessage(title, error.getMessage() == null ? "请保留应用数据并反馈。" : error.getMessage(), "关闭", this::finish);
    }
    private void rememberURL(String account,String tab,String url){
        if(!exists(account,tab))return;String clean=NavigationPolicy.restorable(url);WorkspaceState.Tab current=state.find(account).tabs.stream().filter(t->t.id.equals(tab)).findFirst().orElse(null);if(current==null||current.url.equals(clean))return;
        change(s->s.find(account).tabs.stream().filter(t->t.id.equals(tab)).findFirst().orElseThrow(IllegalStateException::new).url=clean);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);consumeNetworkSettingsIntent(intent);if(store!=null&&ready&&!fatal)try{state=store.load();if(intent.hasExtra(EnvironmentLauncher.ACCOUNT_ID)){String accountId=intent.getStringExtra(EnvironmentLauncher.ACCOUNT_ID);state=store.update(next->{next.find(accountId);next.activeAccount=accountId;});intent.removeExtra(EnvironmentLauncher.ACCOUNT_ID);}environmentLaunchPending=false;showCurrent();}catch(Exception e){fail("无法读取账号记录",e);}acceptHandoffIntent(intent);}
    private void acceptHandoffIntent(Intent intent){
        if(intent==null||!intent.hasExtra("handoffJob"))return;
        try{pendingJob=intent.getStringExtra("handoffJob");pendingJobToken=intent.getStringExtra("handoffToken");WorkspaceState.profile(pendingJob);WorkspaceState.profile(pendingJobToken);}catch(Exception e){pendingJob=null;pendingJobToken=null;toast("任务授权参数无效");}
        intent.removeExtra("handoffJob");intent.removeExtra("handoffToken");drainHandoff();
    }
    private void drainHandoff(){if(!resumed||!ready||fatal||pendingJob==null||!memory.networkReady())return;String id=pendingJob,token=pendingJobToken;pendingJob=null;pendingJobToken=null;try{handoffs=HandoffRuntime.get(this);handoffs.start(this,id,token);}catch(Exception e){toast("任务无法启动或已有任务正在运行，请查看任务记录");}}
    @Override public HandoffController.Page open(JSONObject job)throws Exception{
        if(!resumed||!ready||fatal)throw new IllegalStateException("应用不可用");closeOverview();String target=job.getString("targetAccountId");
        if(!state.find(target).storageId.equals(job.getString("targetStorageId"))||!state.find(job.getString("sourceAccountId")).storageId.equals(job.getString("sourceStorageId")))throw new IllegalStateException("账号空间已改变");
        String[] tab=new String[1];if(!change(s->{s.activeAccount=target;tab[0]=s.addTab(s.find(target)).id;}))throw new IllegalStateException("无法创建标签页");showCurrent();WebView web=views.get(tab[0]);if(web==null)throw new IllegalStateException("目标标签不可用");return new HandoffController.Page(tab[0],web);
    }
    @Override public boolean valid(JSONObject job,HandoffController.Page page){
        try{return memory.networkReady()&&resumed&&ready&&!fatal&&overview==null&&state.activeAccount.equals(job.getString("targetAccountId"))&&state.current().activeTab.equals(page.tab)&&views.get(page.tab)==page.web&&page.web.getParent()==content&&page.web.isShown()&&page.web.getAlpha()==1f&&page.web.isEnabled()&&state.find(job.getString("sourceAccountId")).storageId.equals(job.getString("sourceStorageId"))&&state.find(job.getString("targetAccountId")).storageId.equals(job.getString("targetStorageId"));}catch(Exception e){return false;}
    }
    @Override public long epoch(HandoffController.Page page){return pageEpochs.getOrDefault(page.tab,-1L);}
    @Override public void changed(JSONObject job){
        if(isFinishing()||isDestroyed())return;bannerJob=job;taskText.setText(HandoffStatus.name(job.optString("status"))+" · "+job.optString("label")+"\n"+job.optString("sourceName")+" → "+job.optString("targetName"));taskAction.setText(HandoffStore.active(job.optString("status"))?"停止":"关闭");taskBanner.setVisibility(View.VISIBLE);renderHeader();
    }
    @Override public void failed(String message){if(!isFinishing()&&!isDestroyed())toast(message);}
    private void showHandoffHistory(){if(!ready||fatal)return;try{handoffs=HandoffRuntime.get(this);List<JSONObject> jobs=handoffs.store.list(state.activeAccount);String[] labels=new String[jobs.size()];for(int i=0;i<jobs.size();i++){JSONObject job=jobs.get(i);labels[i]=HandoffStatus.name(job.optString("status"))+" · "+job.optString("label")+"\n"+job.optString("sourceName")+" → "+job.optString("targetName");}
        if(jobs.isEmpty()){new AlertDialog.Builder(this).setTitle("网页处理任务").setMessage("当前账号还没有任务。从资产库选择文件或从 Skill 能力库发起。").setPositiveButton("关闭",null).show();return;}
        new AlertDialog.Builder(this).setTitle("当前账号的网页任务").setItems(labels,(d,i)->showHandoffJob(jobs.get(i))).setNegativeButton("关闭",null).show();
    }catch(Exception e){toast("任务记录不可读，已停止自动发送，请保留应用数据");}}
    private void showHandoffJob(JSONObject snapshot){try{JSONObject job=handoffs.store.get(snapshot.getString("id"));StringBuilder detail=new StringBuilder(job.optString("sourceName")).append(" → ").append(job.optString("targetName")).append("\n\n").append(job.optString("message")).append("\n\n附件：\n");org.json.JSONArray files=job.getJSONArray("files");for(int i=0;i<files.length();i++)detail.append("- ").append(files.getJSONObject(i).getString("uploadName")).append('\n');
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(HandoffStatus.name(job.getString("status"))+" · "+job.optString("label")).setMessage(detail.toString()).setNegativeButton("关闭",null).setPositiveButton("打开目标对话",(d,w)->openHandoffJob(job));if(HandoffStore.active(job.getString("status")))dialog.setNeutralButton("停止自动处理",(d,w)->{try{handoffs.cancel(job.optString("id"),state.activeAccount);}catch(Exception e){toast("任务已结束或无法停止，请检查目标页");}});dialog.show();
    }catch(Exception e){toast("无法读取任务，请保留数据");}}
    private void openHandoffJob(JSONObject job){try{String target=job.getString("targetAccountId");if(!state.find(target).storageId.equals(job.getString("targetStorageId")))throw new IllegalStateException();String tab=job.optString("tabId"),url=job.optString("conversationURL");boolean existing=state.find(target).tabs.stream().anyMatch(t->t.id.equals(tab));
        if(!existing&&url.isEmpty()){toast("目标标签页已关闭，请手动检查原对话");return;}
        if(!change(s->{s.activeAccount=target;WorkspaceState.Account account=s.find(target);if(existing)account.activeTab=tab;else{s.addTab(account).url=url;}}))return;if(state.find(target).isEnvironment()){EnvironmentLauncher.open(this,target);return;}showCurrent();if(!url.isEmpty()&&!NavigationPolicy.restorable(currentWeb().getUrl()==null?"":currentWeb().getUrl()).equals(url))currentWeb().loadUrl(url);
    }catch(Exception e){toast("目标账号已改变或清理，不能在新的登录空间自动打开旧任务");}}
    private void showMessage(String title, String body, String action, Runnable onClick) {
        content.removeAllViews(); progress.setVisibility(View.INVISIBLE);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(28), dp(24), dp(28), dp(24));
        TextView heading = text(title, 20); heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        TextView message = text(body, 14); message.setPadding(0, dp(16), 0, dp(24)); message.setTextColor(Color.DKGRAY); card.addView(message);
        TextView retry = button(action, action); retry.setBackground(round(INK)); retry.setTextColor(Color.WHITE); retry.setPadding(dp(24), 0, dp(24), 0); retry.setOnClickListener(v -> onClick.run()); card.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
        content.addView(card, new FrameLayout.LayoutParams(-1, -1));
    }
    private void askExternal(String url) { new AlertDialog.Builder(this).setTitle("在系统浏览器中打开？").setMessage(Uri.parse(url).getHost()).setNegativeButton("取消", null).setPositiveButton("打开", (d, w) -> openExternal(url)).show(); }
    private void openExternal(String url) { if (NavigationPolicy.https(url)) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException e) { toast("没有可用的浏览器"); } }
    private EditText field(String hint, String value, int max) { EditText v = new EditText(this); v.setTextColor(INK); v.setTextSize(15); v.setHint(hint); v.setText(value); v.setSingleLine(true); v.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)}); return v; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextColor(INK); StudioTypography.text(t,Math.max(14,size)); t.setGravity(Gravity.CENTER); return t; }
    private TextView button(String value, String description) { TextView t = text(value, 14); t.setContentDescription(description); t.setGravity(Gravity.CENTER); t.setPadding(dp(8),dp(8),dp(8),dp(8));t.setMinHeight(StudioTypography.controlHeight(this)); t.setClickable(true); t.setFocusable(true); t.setBackgroundResource(android.R.drawable.list_selector_background); return t; }
    private GradientDrawable round(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
