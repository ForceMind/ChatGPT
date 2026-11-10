package com.forcemind.chatgpt.profiles;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.system.Os;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.mozilla.geckoview.*;

/** Real account browser. Each nested activity owns exactly one immutable environment process. */
public class EnvironmentBrowserActivity extends ComponentActivity implements EnvironmentDocumentBridge.Host,EnvironmentHandoffController.Host {
    private static final int HISTORY_PAGES_PER_TAB=6,HISTORY_PAGES_PER_PROCESS=48,HISTORY_TEXT_BUDGET=256000;
    private static final class HistoryDocument{
        final JSONObject snapshot;final int size;long usedAt;
        HistoryDocument(JSONObject snapshot){this.snapshot=snapshot;size=snapshot.toString().length();usedAt=SystemClock.uptimeMillis();}
    }
    static final class Entry {
        final String accountId,storageId,tabId;final GeckoSession session;
        String url,committedUri,requestedUrl,loadingUrl,token="",documentId,resumeAfterDocument,restoringDraft,restoreRequest,nativeState="";volatile String lastFingerprint="";
        volatile long epoch;long sequence,writeVersion;int activeTransfers;volatile boolean closed;boolean started,previewDismissed,sensitive,canBack,canForward;
        JSONObject record,pendingRestore,liveDocument,conflictLocal,restoringDocument;
        final List<File> pickerDirectories=new ArrayList<>();
        final LinkedHashMap<String,HistoryDocument> historyDocuments=new LinkedHashMap<>(8,.75f,true);
        WebExtension.Port port;Runnable saveTask;
        Entry(WorkspaceState.Account a,WorkspaceState.Tab tab){accountId=a.id;storageId=a.storageId;tabId=tab.id;url=tab.url.equals(NavigationPolicy.HOME)?CIBuild.home():tab.url;session=new GeckoSession(new GeckoSessionSettings.Builder().contextId("studio-"+a.storageId).build());}
    }
    private static GeckoRuntime runtime;private static String runtimeEnvironment;private static EnvironmentRouteController processRoutes;
    private static final Set<String> REQUESTED_DELETES=new HashSet<>();
    static final Map<String,Entry> SESSIONS=new LinkedHashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final ExecutorService PICKER_IO=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private StateStore store;private EnvironmentRecoveryStore recovery;private EnvironmentDocumentBridge documents;private EnvironmentRouteController routes;
    private WorkspaceState state;private String accountId;private Entry current;private GeckoView browser;
    private StudioBrowserHeader header;private TextView status;private FrameLayout canvas;private View preview;
    private boolean routeReady,appForeground,destroyed,resumed,conflictShowing;
    private EnvironmentHandoffController handoff;
    private JSONObject handoffJob;
    private String pendingHandoff,pendingHandoffToken;
    private boolean startingHandoff;
    private long handoffAttempt;
    private LinearLayout handoffBanner;private TextView handoffStatus,handoffStop;
    private enum PageCommand { REFRESH, FORCE_REFRESH, BACK, FORWARD }
    private static final class PageOperation {
        final Entry entry;final String request=UUID.randomUUID().toString(),url,token;final long epoch;final PageCommand command;
        PageOperation(Entry e,PageCommand command){entry=e;url=e.url;token=e.token;epoch=e.epoch;this.command=command;}
    }
    private PageOperation pageOperation;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;private Entry fileOwner;private long fileEpoch;private String fileUrl;private EnvironmentTransferController.Lease fileLease;private PickerCopy fileCopy;

    private static final class PickerCopy {
        final Entry owner;final EnvironmentTransferController.Lease lease;final long epoch;final String url;final CancellationSignal signal=new CancellationSignal();
        volatile boolean cancelled,ready;volatile InputStream input;volatile OutputStream output;volatile File directory;
        PickerCopy(Entry owner,EnvironmentTransferController.Lease lease,long epoch,String url){this.owner=owner;this.lease=lease;this.epoch=epoch;this.url=url;}
        void cancel(){if(ready)return;cancelled=true;signal.cancel();InputStream in=input;OutputStream out=output;try{if(in!=null)in.close();}catch(IOException ignored){}try{if(out!=null)out.close();}catch(IOException ignored){}}
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){if(handoffBusy()){cancelHandoff("已离开任务页面；不会自动重发");moveTaskToBack(true);}else if(current!=null&&!current.closed&&current.canBack&&routeReady)pageAction(current,PageCommand.BACK);else moveTaskToBack(true);}});getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store=new StateStore(getFilesDir());accountId=getIntent().getStringExtra("accountId");CIBuild.installLocalExportProbe(getFilesDir());CIBuild.installHandoffProbe(getFilesDir());captureHandoff(getIntent());
        try{
            state=store.load();WorkspaceState.Account a=state.find(accountId);if(!a.isEnvironment()||a.environmentSlot!=slot())throw new IOException("账号环境归属已改变");
            String environment=a.language+"/"+a.timeZone;
            if(runtime==null){
                cleanColdPickerCache();
                EnvironmentHandoffController.cleanColdCache(this,slot());
                Locale locale=Locale.forLanguageTag(a.language);Locale.setDefault(locale);LocaleList.setDefault(new LocaleList(locale));TimeZone.setDefault(TimeZone.getTimeZone(a.timeZone));Os.setenv("TZ",a.timeZone,true);
                File privateFiles=getFilesDir().getCanonicalFile();
                File profiles=new File(privateFiles,"environment-profiles");
                if(BuildConfig.ENVIRONMENT_TEST)android.util.Log.i("StudioEnvironmentCI","private directory raw="+getFilesDir()+" canonical="+privateFiles);
                HandoffFiles.directory(profiles,true);File profile=new File(profiles,Integer.toString(slot()));HandoffFiles.directory(profile,true);
                Bundle env=new Bundle();env.putString("env0","TZ="+a.timeZone);
                runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env).locales(new String[]{a.language}).remoteDebuggingEnabled(false).consoleOutput(false).build());runtimeEnvironment=environment;
            }
            if(!environment.equals(runtimeEnvironment))throw new IOException("账号环境与进程不一致，请重新打开应用");
            recovery=new EnvironmentRecoveryStore(this);documents=new EnvironmentDocumentBridge(this);buildUi();
            // Reattach delegates to this Activity after Android recreates the window.
            reconcile();for(Entry e:SESSIONS.values())configure(e,this);
            EnvironmentRouteController.Listener routeListener=(ready,foreground,notice)->{
                boolean wasReady=routeReady;routeReady=ready;appForeground=foreground;if(!ready){cancelPageOperation();cancelFile();}
                if(status!=null){status.setText(notice);status.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);}
                for(Entry e:SESSIONS.values())if(!e.closed){e.session.setActive(foreground);if(wasReady&&!ready&&e.started)e.session.stop();}
                // A cold slot receives initial false before its first broker/extension ACK.
                // Preserve the one-shot launch while waiting; only a ready route can be lost.
                if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.i("StudioHandoff","route ready="+ready+" wasReady="+wasReady+" pending="+(pendingHandoff!=null)+" starting="+startingHandoff);
                if(ready){drainHandoff();startCurrent();}else if(wasReady&&handoffBusy())cancelHandoff("网络连接已失效；不会自动重发");renderHeader();
            };
            if(processRoutes==null)processRoutes=new EnvironmentRouteController(getApplicationContext(),runtime,slot(),routeListener);
            routes=processRoutes;routes.localExports.host(this);routes.listener(routeListener);
            if(BuildConfig.APP_UPDATE_AUTO) routes.updatePrompt=(name,size)->resumed&&!destroyed&&!handoffBusy()
                    &&filePrompt==null&&pageOperation==null&&AppUpdatePrompt.show(this,name,size);
            routes.handoffListener(new EnvironmentRouteController.HandoffListener(){public void changed(JSONObject job){EnvironmentBrowserActivity.this.changed(job);if(handoff!=null)handoff.remoteJob(job);}public void lost(String message){cancelHandoff(message);}});
            routes.whenExtensionReady(()->{for(Entry e:SESSIONS.values()){documents.attach(e,routes.extensionReady);routes.transfers.attach(e,routes.extensionReady);}drainHandoff();startCurrent();});
            showCurrent();
        }catch(Exception error){failure("账号页面暂时无法打开",error.getMessage());}
    }
    private int slot(){String name=getClass().getSimpleName();if(!name.startsWith("Slot"))throw new IllegalStateException("缺少账号环境");return Integer.parseInt(name.substring(4));}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        header=new StudioBrowserHeader(this,new StudioBrowserHeader.Host(){
            public void accounts(){EnvironmentBrowserActivity.this.accounts();}
            public void select(String tabId){selectTab(tabId);}
            public void tabs(){EnvironmentBrowserActivity.this.tabs();}
            public void refresh(){pageAction(current,PageCommand.REFRESH);}
            public void stop(){stopPage();}
            public void more(){EnvironmentBrowserActivity.this.more();}
        });root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        View divider=new View(this);divider.setBackgroundColor(0xffeeeeee);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        status=text("正在连接账号环境",12);status.setTextColor(0xff666666);status.setPadding(dp(16),dp(7),dp(16),dp(7));root.addView(status);
        handoffBanner=new LinearLayout(this);handoffBanner.setGravity(Gravity.CENTER_VERTICAL);handoffBanner.setPadding(dp(16),dp(5),dp(8),dp(5));handoffBanner.setBackgroundColor(0xfff6f6f6);handoffBanner.setVisibility(View.GONE);handoffStatus=text("",13);handoffStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);handoffStatus.setPadding(0,dp(6),dp(8),dp(6));handoffBanner.addView(handoffStatus,new LinearLayout.LayoutParams(0,-2,1));handoffStatus.setOnClickListener(v->{if(handoffJob!=null)showHandoffJob(handoffJob.optString("id"));});handoffStop=button("停止","停止自动任务");handoffStop.setOnClickListener(v->cancelHandoff("已停止自动发送；请检查目标网页"));handoffBanner.addView(handoffStop,new LinearLayout.LayoutParams(-2,-2));root.addView(handoffBanner,new LinearLayout.LayoutParams(-1,-2));
        canvas=new FrameLayout(this);browser=new GeckoView(this);browser.setOnTouchListener((view,event)->handoff!=null&&handoff.active()&&!handoff.dispatching());canvas.addView(browser,new FrameLayout.LayoutParams(-1,-1));root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void reconcile()throws Exception{
        state=store.load();Iterator<Entry> it=SESSIONS.values().iterator();while(it.hasNext()){
            Entry e=it.next();boolean exists=false;for(WorkspaceState.Account a:state.accounts)if(a.isEnvironment()&&a.environmentSlot==slot()&&a.id.equals(e.accountId)&&a.storageId.equals(e.storageId)&&a.tabs.stream().anyMatch(t->t.id.equals(e.tabId))){exists=true;break;}
            if(!exists){close(e);it.remove();}
        }
        for(WorkspaceState.EnvironmentDelete tombstone:state.pendingEnvironmentDeletes)if(tombstone.environmentSlot==slot()&&REQUESTED_DELETES.add(tombstone.storageId)){
            runtime.getStorageController().clearDataForSessionContext("studio-"+tombstone.storageId);
            // Gecko returns no completion ACK: retain the persistent tombstone for cold-start retry.
        }
    }
    private Entry entry(WorkspaceState.Account a,WorkspaceState.Tab tab)throws Exception{
        Entry e=SESSIONS.get(tab.id);if(e!=null)return e;
        e=new Entry(a,tab);SESSIONS.put(tab.id,e);configure(e,this);
        String safe=EnvironmentUrls.recoverable(e.url);
        if(safe!=null)try{
            e.record=recovery.load(a.id,a.storageId,tab.id,safe);
            if(e.record!=null){e.nativeState=e.record.optString("nativeSessionState","");e.pendingRestore=e.record.optJSONObject("document");rememberDocument(e,e.pendingRestore);}
        }catch(Exception error){notice("本地恢复记录暂不可读取，原件已保留");}
        if(e.record==null)e.record=freshRecord(e);
        if(routes!=null&&routes.extensionReady!=null){documents.attach(e,routes.extensionReady);routes.transfers.attach(e,routes.extensionReady);}
        return e;
    }
    private JSONObject freshRecord(Entry e)throws Exception{return new JSONObject().put("schema",1).put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId).put("url",EnvironmentUrls.recoverable(e.url));}
    private void showCurrent(){
        try{
            reconcile();WorkspaceState.Account a=state.find(accountId);if(!a.isEnvironment()||a.environmentSlot!=slot()){EnvironmentLauncher.open(this,a.id);finish();return;}
            if(a.current().url.equals(NavigationPolicy.HOME)&&!CIBuild.home().equals(NavigationPolicy.HOME)){state=store.update(s->{WorkspaceState.Account owner=s.find(accountId);owner.current().url=CIBuild.home();});a=state.find(accountId);}
            Entry next=entry(a,a.current());if(current!=next){if(handoff!=null&&handoff.entry!=next)cancelHandoff("目标标签已切换，已停止自动发送");cancelPageOperation();cancelFile();if(current!=null&&!current.closed){documents.request(current);current.session.flushSessionState();current.session.setFocused(false);}if(browser.getSession()!=null)browser.releaseSession();if(current!=null&&!current.closed)current.session.setActive(appForeground||resumed);current=next;if(!next.session.isOpen())next.session.open(runtime);browser.setSession(next.session);next.session.setActive(appForeground||resumed);next.session.setFocused(resumed);}
            renderHeader();
            if(preview!=null){canvas.removeView(preview);preview=null;}
            if(!next.started&&!next.previewDismissed&&next.record.optJSONObject("document")!=null)showPreview(next);else startCurrent();
        }catch(Exception error){failure("账号已改变",error.getMessage());}
    }
    private void startCurrent(){
        if(destroyed||pendingHandoff!=null||startingHandoff||current==null||current.closed||current.started||!routeReady||routes==null||routes.extensionReady==null)return;
        if(!owns(current))return;if(!current.previewDismissed&&current.record.optJSONObject("document")!=null)return;
        current.started=true;renderHeader();current.session.setActive(appForeground||resumed);current.session.setFocused(resumed);
        GeckoSession.SessionState restored=null;try{if(!current.nativeState.isEmpty()){GeckoSession.SessionState parsed=GeckoSession.SessionState.fromString(current.nativeState);if(parsed!=null){String checked=nativeState(current,parsed);if(checked!=null)restored=GeckoSession.SessionState.fromString(checked);}}}catch(Exception rejected){}
        if(restored!=null)current.session.restoreState(restored);else current.session.loadUri(current.url);
    }
    private void showPreview(Entry e){
        LinearLayout pane=new LinearLayout(this);pane.setOrientation(LinearLayout.VERTICAL);pane.setBackgroundColor(Color.WHITE);pane.setPadding(dp(20),dp(18),dp(20),dp(18));
        TextView label=text("本地恢复副本",18);label.setTypeface(null,android.graphics.Typeface.BOLD);pane.addView(label);TextView hint=text("上次草稿和对话文字，尚未连接网页。继续后恢复历史及阅读位置。",13);hint.setTextColor(0xff777777);hint.setPadding(0,dp(8),0,dp(16));pane.addView(hint);
        ScrollView scroll=new ScrollView(this);LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);JSONObject doc=e.record.optJSONObject("document");
        if(doc!=null){if(!doc.optString("draft").isEmpty()){TextView draft=text("草稿\n"+doc.optString("draft"),15);draft.setTextIsSelectable(true);draft.setPadding(0,0,0,dp(20));copy.addView(draft);}TextView transcript=text(doc.optString("preview","暂无对话文字"),14);transcript.setTextIsSelectable(true);copy.addView(transcript);}scroll.addView(copy);pane.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button continueButton=new Button(this);StudioTypography.button(continueButton);continueButton.setText("继续页面");continueButton.setOnClickListener(v->{if(current==e){e.previewDismissed=true;canvas.removeView(pane);preview=null;startCurrent();}});pane.addView(continueButton,new LinearLayout.LayoutParams(-1,-2));preview=pane;canvas.addView(pane,new FrameLayout.LayoutParams(-1,-1));
    }
    private static void configure(Entry e,EnvironmentBrowserActivity activity){
        final java.lang.ref.WeakReference<EnvironmentBrowserActivity> target=new java.lang.ref.WeakReference<>(activity);
        e.session.setNavigationDelegate(new GeckoSession.NavigationDelegate(){
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,LoadRequest request){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(AllowOrDeny.DENY);}
                if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST&&EnvironmentLocalExports.kind(request.uri)!=null)EnvironmentLocalExports.diagnostic("load-request owned="+ownerActivity.owns(e)+" route="+ownerActivity.routeReady+" source="+e.url.equals(request.triggerUri)+" gesture="+request.hasUserGesture+" direct="+request.isDirectNavigation);
                if(!ownerActivity.routeReady||!ownerActivity.owns(e))return GeckoResult.fromValue(AllowOrDeny.DENY);
                if(!NavigationPolicy.internal(request.uri)&&!CIBuild.environmentUrl(request.uri)){if(e==ownerActivity.current)ownerActivity.external(request.uri);return GeckoResult.fromValue(AllowOrDeny.DENY);}
                if(!request.uri.equals(e.url))e.requestedUrl=request.uri;return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override public void onLocationChange(GeckoSession session,String url,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean gesture){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(url!=null&&!url.equals(e.committedUri)){if(ownerActivity.routes!=null)ownerActivity.routes.localExports.invalidated(e);e.committedUri=url;}if(url!=null&&(NavigationPolicy.internal(url)||CIBuild.environmentUrl(url))){e.requestedUrl=null;if(!url.equals(e.url))ownerActivity.navigation(e,url);}}
            @Override public void onCanGoBack(GeckoSession session,boolean value){e.canBack=value;EnvironmentBrowserActivity host=target.get();if(host!=null&&!host.destroyed)host.renderHeader();}
            @Override public void onCanGoForward(GeckoSession session,boolean value){e.canForward=value;EnvironmentBrowserActivity host=target.get();if(host!=null&&!host.destroyed)host.renderHeader();}
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session,String uri){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(null);}
                if(!ownerActivity.routeReady||!ownerActivity.owns(e)||(!NavigationPolicy.internal(uri)&&!CIBuild.environmentUrl(uri))){ownerActivity.notice("此窗口无法在账号中打开");return GeckoResult.fromValue(null);}
                try{final String[] id={null};ownerActivity.state=ownerActivity.store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))throw new IllegalStateException("登录空间已改变");WorkspaceState.Tab t=s.addTab(a);if(EnvironmentUrls.chat(uri))t.url=EnvironmentUrls.recoverable(uri);id[0]=t.id;});WorkspaceState.Account a=ownerActivity.state.find(e.accountId);Entry child=ownerActivity.entry(a,a.current());child.previewDismissed=true;child.started=true;ownerActivity.ui.post(ownerActivity::showCurrent);return GeckoResult.fromValue(child.session);}catch(Exception error){ownerActivity.notice("无法创建新标签");return GeckoResult.fromValue(null);}
            }
        });
        e.session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onPageStart(GeckoSession session,String url){e.loadingUrl=url;EnvironmentBrowserActivity host=target.get();if(host!=null&&!host.destroyed){if(host.handoff!=null&&host.handoff.entry==e)host.handoff.pageStarted();if(host.pageOperation!=null&&host.pageOperation.entry==e)host.cancelPageOperation();host.renderHeader();}}
            @Override public void onSessionStateChange(GeckoSession session,GeckoSession.SessionState value){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(!ownerActivity.owns(e)||e.sensitive||EnvironmentUrls.recoverable(e.url)==null||!e.url.equals(EnvironmentUrls.recoverable(e.url)))return;try{String nativeValue=ownerActivity.nativeState(e,value);if(nativeValue==null||nativeValue.length()>700000)return;e.nativeState=nativeValue;ownerActivity.queueSave(e);}catch(Exception ignored){}}
            @Override public void onPageStop(GeckoSession session,boolean success){e.loadingUrl=null;e.requestedUrl=null;if(!success)e.resumeAfterDocument=null;EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(!success)ownerActivity.documents.request(e);ownerActivity.renderHeader();if(e==ownerActivity.current&&success&&ownerActivity.routeReady){ownerActivity.status.setVisibility(View.GONE);}else if(e==ownerActivity.current&&!success&&ownerActivity.routeReady){ownerActivity.status.setText("页面加载未完成，可在更多中刷新");ownerActivity.status.setVisibility(View.VISIBLE);}}
        });
        e.session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onTitleChange(GeckoSession session,String title){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(title==null||!ownerActivity.owns(e)||EnvironmentUrls.recoverable(e.url)==null)return;try{ownerActivity.state=ownerActivity.store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))return;for(WorkspaceState.Tab t:a.tabs)if(t.id.equals(e.tabId)&&!t.customTitle)t.title=title.substring(0,Math.min(120,title.length()));});ownerActivity.renderHeader();}catch(Exception ignored){}}
            @Override public void onExternalResponse(GeckoSession session,WebResponse response){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){try{if(response.body!=null)response.body.close();}catch(Exception ignored){}return;}ownerActivity.download(e,response);}
            @Override public void onCrash(GeckoSession session){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(ownerActivity.handoff!=null&&ownerActivity.handoff.entry==e)ownerActivity.cancelHandoff("目标页面进程已停止；不会自动重发");e.started=false;ownerActivity.documents.invalidate(e);ownerActivity.renderHeader();ownerActivity.notice("页面进程已停止，本地恢复副本已保留");}
            @Override public void onKill(GeckoSession session){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(ownerActivity.handoff!=null&&ownerActivity.handoff.entry==e)ownerActivity.cancelHandoff("目标页面已释放；不会自动重发");e.started=false;ownerActivity.documents.invalidate(e);ownerActivity.renderHeader();ownerActivity.notice("页面已释放，可重新打开恢复");}
        });
        e.session.setPromptDelegate(new GeckoSession.PromptDelegate(){
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session,FilePrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(prompt.dismiss());}
                if(ownerActivity.handoff!=null&&ownerActivity.handoff.active()){GeckoResult<PromptResponse> task=ownerActivity.handoff.chooser(session,prompt);return task==null?GeckoResult.fromValue(prompt.dismiss()):task;}
                if(e!=ownerActivity.current||!ownerActivity.owns(e)||!ownerActivity.routeReady||ownerActivity.fileResult!=null||prompt.type==FilePrompt.Type.FOLDER){ownerActivity.notice("当前页面无法选择文件");return GeckoResult.fromValue(prompt.dismiss());}
                ownerActivity.fileOwner=e;ownerActivity.fileEpoch=e.epoch;ownerActivity.fileUrl=e.url;ownerActivity.filePrompt=prompt;ownerActivity.fileResult=new GeckoResult<>();ownerActivity.renderHeader();GeckoResult<PromptResponse> selected=ownerActivity.fileResult;
                ownerActivity.routes.transfers.acquirePicker(e,lease->{
                    if(lease==null||ownerActivity.destroyed||ownerActivity.fileResult!=selected||e!=ownerActivity.current||!ownerActivity.owns(e)||e.epoch!=ownerActivity.fileEpoch||!e.url.equals(ownerActivity.fileUrl)){
                        if(lease!=null)ownerActivity.routes.transfers.release(lease,"cancel");if(ownerActivity.fileResult==selected){ownerActivity.cancelFile();ownerActivity.notice("当前线路暂不能选择文件，请重试");}return;
                    }
                    ownerActivity.fileLease=lease;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,prompt.type==FilePrompt.Type.MULTIPLE);
                    if(prompt.mimeTypes!=null&&prompt.mimeTypes.length>0)pick.putExtra(Intent.EXTRA_MIME_TYPES,prompt.mimeTypes);try{ownerActivity.startActivityForResult(pick,804);}catch(Exception error){ownerActivity.cancelFile();ownerActivity.notice("系统文件选择器不可用");}
                });return selected;
            }
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session,AlertPrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(prompt.dismiss());}GeckoResult<PromptResponse> result=new GeckoResult<>();new AlertDialog.Builder(ownerActivity).setTitle("页面提示").setMessage(prompt.message).setPositiveButton("好",(d,w)->result.complete(prompt.dismiss())).setOnCancelListener(d->result.complete(prompt.dismiss())).show();return result;}
            @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session,RepostConfirmPrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity!=null&&!ownerActivity.destroyed){e.loadingUrl=null;e.resumeAfterDocument=null;ownerActivity.documents.request(e);ownerActivity.renderHeader();ownerActivity.notice("为避免重复提交，已取消重新发送");}return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY));}
            @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session,PopupPrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(prompt.dismiss());}ownerActivity.notice("页面弹窗已拦截，请使用页面内的继续按钮");return GeckoResult.fromValue(prompt.dismiss());}
        });
    }
    private String nativeState(Entry e,GeckoSession.SessionState value)throws Exception{
        int selected=value.getCurrentIndex();if(selected<0||selected>=value.size()||!e.url.equals(value.get(selected).getUri()))return null;
        JSONObject raw=new JSONObject(value.toString());raw.remove("formdata");
        JSONObject history=raw.getJSONObject("history");JSONArray entries=history.getJSONArray("entries"),safe=new JSONArray();int nextIndex=-1;
        for(int i=0;i<entries.length();i++){
            JSONObject item=entries.getJSONObject(i);String original=item.optString("url"),address=EnvironmentUrls.recoverable(original);
            if(address==null)continue;
            // Native history/position survives; form restoration belongs to the validated composer bridge.
            JSONObject clean=new JSONObject(item.toString());clean.put("url",address);clean.remove("children");clean.remove("formdata");clean.remove("postdata_b64");safe.put(clean);if(i==selected)nextIndex=safe.length();
        }
        if(nextIndex<1)return null;history.put("entries",safe).put("index",nextIndex);return raw.toString();
    }
    private void navigation(Entry e,String url){
        if(handoff!=null&&handoff.entry==e)handoff.locationChanged(url);
        if(e.closed)return;if(pageOperation!=null&&pageOperation.entry==e)cancelPageOperation();if(fileOwner==e)cancelFile();if(e.record!=null)rememberDocument(e,e.record.optJSONObject("document"));documents.invalidate(e);e.url=url;e.resumeAfterDocument=null;e.sensitive=false;e.nativeState="";e.pendingRestore=null;e.conflictLocal=null;e.restoringDraft=null;e.restoringDocument=null;e.restoreRequest=null;
        String safe=EnvironmentUrls.recoverable(url);if(safe==null)return;
        try{
            state=store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))throw new IllegalStateException("登录空间已改变");for(WorkspaceState.Tab t:a.tabs)if(t.id.equals(e.tabId))t.url=safe;});
            JSONObject saved=recovery.load(e.accountId,e.storageId,e.tabId,safe);e.record=saved==null?freshRecord(e):saved;
            JSONObject remembered=historyDocument(e,safe);e.pendingRestore=remembered!=null?remembered:(saved==null?null:saved.optJSONObject("document"));
            if(e.pendingRestore!=null)e.record.put("document",e.pendingRestore);renderHeader();
        }catch(Exception error){try{e.record=freshRecord(e);}catch(Exception ignored){}notice("此页面的本地恢复暂不可用");}
    }
    @Override public boolean owns(Entry e){
        if(e.closed)return false;try{WorkspaceState.Account a=store.load().find(e.accountId);return a.isEnvironment()&&a.environmentSlot==slot()&&a.storageId.equals(e.storageId)&&a.language.concat("/").concat(a.timeZone).equals(runtimeEnvironment)&&a.tabs.stream().anyMatch(t->t.id.equals(e.tabId));}catch(Exception error){return false;}
    }
    @Override public void document(Entry e,JSONObject value){if(!owns(e)||e.sensitive)return;try{rememberDocument(e,value);e.record.put("document",value);queueSave(e);renderHeader();}catch(Exception ignored){}}
    /** Runtime-only history drafts; persistent primary/backup still represent the current page. */
    private void rememberDocument(Entry e,JSONObject value){
        if(e.closed||e.sensitive||value==null)return;String address=value.optString("url"),safe=EnvironmentUrls.recoverable(e.url);
        Object text=value.opt("draft");double scroll=value.optDouble("scroll",Double.NaN),ratio=value.optDouble("ratio",Double.NaN);
        if(safe==null||!safe.equals(address)||!(text instanceof String)||((String)text).length()>100000||!Double.isFinite(scroll)||scroll<0||scroll>100000000||!Double.isFinite(ratio)||ratio<0||ratio>1)return;
        Map<String,Object> fields=new LinkedHashMap<>();fields.put("url",address);fields.put("draft",text);fields.put("scroll",scroll);fields.put("ratio",ratio);fields.put("capturedAt",value.optLong("capturedAt",System.currentTimeMillis()));fields.put("preview","");
        e.historyDocuments.put(address,new HistoryDocument(new JSONObject(fields)));
        while(e.historyDocuments.size()>HISTORY_PAGES_PER_TAB)e.historyDocuments.remove(e.historyDocuments.keySet().iterator().next());
        trimHistoryDocuments();
    }
    private JSONObject historyDocument(Entry e,String url)throws JSONException{
        HistoryDocument remembered=e.historyDocuments.get(url);if(remembered==null)return null;remembered.usedAt=SystemClock.uptimeMillis();return new JSONObject(remembered.snapshot.toString());
    }
    private void trimHistoryDocuments(){
        while(true){
            int count=0,size=0;Entry oldestOwner=null;String oldestUrl=null;long oldest=Long.MAX_VALUE;
            for(Entry owner:SESSIONS.values())for(Map.Entry<String,HistoryDocument> item:owner.historyDocuments.entrySet()){
                count++;HistoryDocument document=item.getValue();size+=document.size;
                if(document.usedAt<oldest){oldest=document.usedAt;oldestOwner=owner;oldestUrl=item.getKey();}
            }
            if(count<=HISTORY_PAGES_PER_PROCESS&&size<=HISTORY_TEXT_BUDGET||oldestOwner==null)return;
            oldestOwner.historyDocuments.remove(oldestUrl);
        }
    }
    private void queueSave(Entry e){
        if(e.closed||e.sensitive||e.record==null||EnvironmentUrls.recoverable(e.url)==null)return;
        if(e.saveTask!=null)ui.removeCallbacks(e.saveTask);e.saveTask=()->{
            if(!owns(e)||e.sensitive)return;try{
                JSONObject record=new JSONObject(e.record.toString()).put("nativeSessionState",e.nativeState).put("capturedAt",System.currentTimeMillis());
                JSONObject comparable=new JSONObject(record.toString()).put("capturedAt",0);if(comparable.optJSONObject("document")!=null)comparable.getJSONObject("document").put("capturedAt",0);String fingerprint=comparable.toString();if(fingerprint.equals(e.lastFingerprint))return;
                IO.execute(()->{try{recovery.save(record);e.lastFingerprint=fingerprint;}catch(Exception error){ui.post(()->notice("恢复副本保存未完成，已保留上一份记录"));}});
            }catch(Exception ignored){}
        };ui.postDelayed(e.saveTask,1000);
    }
    @Override public void conflict(Entry e,JSONObject live){
        if(e.conflictLocal==null)e.conflictLocal=e.pendingRestore!=null?e.pendingRestore:e.record.optJSONObject("document");
        if(e!=current||conflictShowing||e.conflictLocal==null)return;conflictShowing=true;renderHeader();final String approvedToken=e.token,approvedDraft=live.optString("draft");final long approvedEpoch=e.epoch;
        StudioSheet sheet=new StudioSheet(this,"发现两份草稿","网页和本地草稿不同，请选择。任何选择都不会发送消息。");TextView local=sheet.text("本地草稿\n"+e.conflictLocal.optString("draft"),14);local.setTextIsSelectable(true);sheet.body.addView(local);TextView webpage=sheet.text("\n网页草稿\n"+live.optString("draft"),14);webpage.setTextIsSelectable(true);sheet.body.addView(webpage);
        sheet.action("保留网页草稿",false,()->{if(!conflictStillCurrent(e,approvedToken,approvedEpoch,approvedDraft)){sheet.dismiss();if(e==current&&e.liveDocument!=null)ui.post(()->conflict(e,e.liveDocument));return;}try{documents.cancelRestore(e);e.record.put("alternativeDraft",e.conflictLocal);e.pendingRestore=null;e.conflictLocal=null;document(e,e.liveDocument);}catch(Exception ignored){}sheet.dismiss();});
        sheet.action("恢复本地草稿",true,()->{if(!conflictStillCurrent(e,approvedToken,approvedEpoch,approvedDraft)){sheet.dismiss();if(e==current&&e.liveDocument!=null)ui.post(()->conflict(e,e.liveDocument));return;}try{JSONObject localCopy=e.conflictLocal;documents.cancelRestore(e);e.record.put("alternativeDraft",e.liveDocument);e.pendingRestore=null;e.conflictLocal=null;documents.restore(e,localCopy,e.liveDocument);queueSave(e);}catch(Exception error){notice("草稿恢复未完成");}sheet.dismiss();});sheet.setOnDismissListener(d->{conflictShowing=false;renderHeader();});sheet.show();
    }
    private boolean conflictStillCurrent(Entry e,String token,long epoch,String draft){return e==current&&owns(e)&&e.liveDocument!=null&&e.port!=null&&token.equals(e.token)&&epoch==e.epoch&&draft.equals(e.liveDocument.optString("draft"));}
    private void tabs(){
        try{reconcile();WorkspaceState.Account a=state.find(accountId);StudioSheet sheet=new StudioSheet(this,"标签页",a.name);for(WorkspaceState.Tab t:a.tabs){sheet.row((t.id.equals(a.activeTab)?"当前 · ":"")+t.title,"","",()->{sheet.dismiss();selectTab(t.id);});}sheet.action("新建标签页",true,()->{sheet.dismiss();newTab();});if(a.tabs.size()>1)sheet.action("关闭当前标签页",false,()->{sheet.dismiss();update(s->{WorkspaceState.Account owner=s.find(accountId);s.closeTab(owner,owner.activeTab);});});sheet.show();}catch(Exception error){notice("标签列表不可用");}
    }
    private void accounts(){
        try{if(runtime!=null&&documents!=null)reconcile();else state=store.load();StudioSheet sheet=new StudioSheet(this,"账号空间","每个环境账号保留独立登录与标签");for(WorkspaceState.Account a:state.accounts)sheet.row(a.name,a.isEnvironment()?a.language+" · "+a.timeZone:"原网页登录空间","",()->{sheet.dismiss();EnvironmentLauncher.open(this,a.id);});sheet.action("创建账号",true,()->{sheet.dismiss();AccountEditor.show(this,store,null,saved->{state=saved;EnvironmentLauncher.open(this,saved.activeAccount);});});sheet.action("编辑当前账号",false,()->{sheet.dismiss();AccountEditor.show(this,store,accountId,saved->{state=saved;showCurrent();});});sheet.show();}catch(Exception error){notice("账号列表不可用");}
    }
    private void renderHeader(){
        if(header==null||state==null)return;
        try{header.render(state.find(accountId),pageOperation!=null||current!=null&&current.started&&current.loadingUrl!=null,pageOperation!=null||pageActionsEnabled(current));}catch(IllegalStateException missing){/* A deleted account is handled by showCurrent. */}
        renderHandoff();
    }
    private void selectTab(String tabId){
        cancelPageOperation();cancelFile();update(s->{WorkspaceState.Account a=s.find(accountId);if(a.tabs.stream().noneMatch(t->t.id.equals(tabId)))throw new IllegalStateException("标签页已关闭");a.activeTab=tabId;});
    }
    private boolean pageTargetAvailable(Entry e){
        return !destroyed&&!handoffBusy()&&e!=null&&e==current&&!e.closed&&e.started&&e.session.isOpen()&&routeReady&&owns(e)&&fileResult==null&&e.activeTransfers==0&&!conflictShowing&&(e.pendingRestore==null||e.loadingUrl!=null)&&e.conflictLocal==null&&e.restoringDraft==null;
    }
    private boolean pageActionsEnabled(Entry e){return pageOperation==null&&pageTargetAvailable(e);}
    private boolean operationCurrent(PageOperation operation){
        Entry e=operation.entry;return pageOperation==operation&&pageTargetAvailable(e)&&operation.url.equals(e.url)&&operation.epoch==e.epoch&&operation.token.equals(e.token)&&
            (operation.command!=PageCommand.BACK||e.canBack)&&(operation.command!=PageCommand.FORWARD||e.canForward);
    }
    private void cancelPageOperation(){
        PageOperation operation=pageOperation;pageOperation=null;if(operation!=null&&documents!=null)documents.cancelCheckpoint(operation.entry,operation.request);renderHeader();
    }
    private void pageAction(Entry e,PageCommand command){
        if(!pageActionsEnabled(e)||(command==PageCommand.BACK&&!e.canBack)||(command==PageCommand.FORWARD&&!e.canForward))return;
        PageOperation operation=new PageOperation(e,command);pageOperation=operation;renderHeader();
        // Login and temporary/private pages do not participate in draft capture or recovery.
        if(e.sensitive||EnvironmentUrls.recoverable(e.url)==null){executePageOperation(operation,null);return;}
        ui.postDelayed(()->{if(pageOperation==operation){cancelPageOperation();notice("页面操作未完成，当前页面已保留");}},8000);
        documents.requestCheckpoint(e,operation.request,(snapshot,error)->{
            if(pageOperation!=operation)return;
            if(error!=null||snapshot==null||!operationCurrent(operation)){cancelPageOperation();notice(error!=null?error:"草稿尚未保存，当前页面已保留");return;}
            try{
                rememberDocument(e,snapshot);e.record.put("document",snapshot);
                if(e.saveTask!=null){ui.removeCallbacks(e.saveTask);e.saveTask=null;}
                JSONObject record=new JSONObject(e.record.toString()).put("nativeSessionState",e.nativeState).put("capturedAt",System.currentTimeMillis());
                IO.execute(()->{
                    try{recovery.save(record);ui.post(()->{if(operationCurrent(operation))executePageOperation(operation,snapshot);else if(pageOperation==operation)cancelPageOperation();});}
                    catch(Exception failed){ui.post(()->{if(pageOperation==operation){cancelPageOperation();notice("草稿保存未完成，当前页面已保留");}});}
                });
            }catch(Exception failed){cancelPageOperation();notice("草稿保存未完成，当前页面已保留");}
        });
    }
    private void executePageOperation(PageOperation operation,JSONObject snapshot){
        if(!operationCurrent(operation)){cancelPageOperation();return;}Entry e=operation.entry;
        if(snapshot!=null&&(e.liveDocument==null||!snapshot.optString("draft").equals(e.liveDocument.optString("draft")))){cancelPageOperation();notice("草稿已有变化，请重新操作");return;}pageOperation=null;
        if(snapshot!=null&&(operation.command==PageCommand.REFRESH||operation.command==PageCommand.FORCE_REFRESH)){e.pendingRestore=snapshot;e.resumeAfterDocument=e.documentId;}
        e.session.flushSessionState();e.loadingUrl=e.url;
        if(operation.command==PageCommand.BACK)e.session.goBack();
        else if(operation.command==PageCommand.FORWARD)e.session.goForward();
        else e.session.reload(operation.command==PageCommand.FORCE_REFRESH?GeckoSession.LOAD_FLAGS_BYPASS_CACHE:GeckoSession.LOAD_FLAGS_NONE);
        renderHeader();
    }
    private void stopPage(){
        if(pageOperation!=null){cancelPageOperation();notice("已取消页面操作，当前页面已保留");return;}
        Entry e=current;if(!pageActionsEnabled(e)||e.loadingUrl==null)return;e.resumeAfterDocument=null;documents.request(e);e.session.stop();e.session.flushSessionState();e.loadingUrl=null;renderHeader();
    }
    private void more(){
        Entry target=current;boolean enabled=pageActionsEnabled(target);
        StudioSheet sheet=new StudioSheet(this,"更多","");
        StudioPageActions.navigation(sheet,enabled&&target!=null&&target.canBack,enabled&&target!=null&&target.canForward,
            ()->{sheet.dismiss();pageAction(target,PageCommand.BACK);},()->{sheet.dismiss();pageAction(target,PageCommand.FORWARD);});
        StudioPageActions.action(sheet,"强制刷新","重新获取当前页面内容","refresh",enabled,()->{sheet.dismiss();pageAction(target,PageCommand.FORCE_REFRESH);});
        sheet.row("查看网页任务","进度、结果和目标对话","",()->{sheet.dismiss();showHandoffHistory();}).setContentDescription("查看网页任务");
        sheet.row("标签管理","切换或关闭标签页","",()->{sheet.dismiss();tabs();});
        if(BuildConfig.APP_UPDATES_ENABLED)sheet.row("应用更新","","",()->{sheet.dismiss();AppUpdateActivity.open(this);});
        sheet.row("新建标签页","","plus",()->{sheet.dismiss();newTab();});sheet.row("账号管理","","user",()->{sheet.dismiss();accounts();});sheet.row("资产库","","folder",()->{sheet.dismiss();assets(false);});sheet.row("能力库","","spark",()->{sheet.dismiss();assets(true);});sheet.row("网络设置","","",()->{sheet.dismiss();startActivity(new Intent(this,MainActivity.class).putExtra("networkSettings",true));});sheet.row("恢复草稿","查看本地保存的草稿","",()->{sheet.dismiss();recoverySheet();});sheet.show();
    }
    private void recoverySheet(){
        if(current==null||current.record==null)return;Entry e=current;JSONObject doc=e.record.optJSONObject("document");StudioSheet sheet=new StudioSheet(this,"恢复草稿","本地副本不会自动发送");if(doc==null){sheet.body.addView(sheet.text("暂无本地草稿",14));}else{TextView copy=sheet.text(doc.optString("draft").isEmpty()?"暂无草稿文字":doc.optString("draft"),15);copy.setTextIsSelectable(true);sheet.body.addView(copy);sheet.action("恢复到当前页面",true,()->{sheet.dismiss();if(e.liveDocument==null){notice("请先继续页面，等待编辑器就绪");return;}e.pendingRestore=doc;conflict(e,e.liveDocument);});}sheet.show();
    }
    private void newTab(){update(s->{WorkspaceState.Account a=s.find(accountId);s.addTab(a);});}
    private void update(java.util.function.Consumer<WorkspaceState> change){try{state=store.update(change);showCurrent();}catch(Exception error){notice(error.getMessage()==null?"操作未完成":error.getMessage());}}
    private void assets(boolean skills){startActivity(new Intent(this,AssetWorkbenchActivity.class).putExtra(AssetWorkbenchActivity.ACCOUNT,accountId).putExtra("skills",skills));}
    private boolean handoffBusy(){return pendingHandoff!=null||startingHandoff||handoff!=null&&handoff.active();}
    private void captureHandoff(Intent intent){
        if(intent==null||!intent.hasExtra("handoffJob"))return;
        try{pendingHandoff=intent.getStringExtra("handoffJob");pendingHandoffToken=intent.getStringExtra("handoffToken");WorkspaceState.profile(pendingHandoff);WorkspaceState.profile(pendingHandoffToken);handoffJob=null;if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.i("StudioHandoff","launch-captured valid=true");}
        catch(Exception invalid){pendingHandoff=null;pendingHandoffToken=null;notice("任务授权参数无效");}
        intent.removeExtra("handoffJob");intent.removeExtra("handoffToken");
    }
    private void drainHandoff(){
        if(destroyed||!resumed||!routeReady||routes==null||routes.extensionReady==null||pendingHandoff==null||startingHandoff||handoff!=null&&handoff.active())return;
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.i("StudioHandoff","launch-claim routeReady=true extensionReady=true resumed=true");
        String id=pendingHandoff,launch=pendingHandoffToken,expectedAccount=accountId;pendingHandoff=null;pendingHandoffToken=null;startingHandoff=true;long attempt=++handoffAttempt;cancelPageOperation();cancelFile();renderHeader();
        try{routes.handoff("claim",new JSONObject().put("id",id).put("token",launch),(result,error)->{
            if(attempt!=handoffAttempt||destroyed)return;startingHandoff=false;
            if(error!=null||result==null||!resumed||!expectedAccount.equals(accountId)){routes.cancelHandoff();renderHeader();notice(error==null?"目标环境已改变，任务未发送":error);return;}
            try{
                state=store.load();WorkspaceState.Account account=state.find(result.getString("accountId"));
                if(!account.id.equals(accountId)||!account.storageId.equals(result.getString("storageId"))||!account.isEnvironment()||account.environmentSlot!=slot()||!account.current().id.equals(result.getString("tabId")))throw new IOException("任务目标已改变");
                Entry entry=entry(account,account.current());if(entry.started)throw new IOException("任务必须使用新的空标签页");
                handoff=new EnvironmentHandoffController(this,this,entry,result);handoff.attach(routes.extensionReady);showCurrent();
            }catch(Exception invalid){routes.cancelHandoff();if(handoff!=null)handoff.invalidated("目标网页无法启动，任务未发送");handoff=null;renderHeader();notice("目标账号或新标签页无法确认，任务未发送");}
        });}catch(Exception invalid){startingHandoff=false;routes.cancelHandoff();renderHeader();notice("任务无法启动；不会自动重发");}
    }
    private void cancelHandoff(String reason){
        String pending=pendingHandoff;boolean claimed=startingHandoff;pendingHandoff=null;pendingHandoffToken=null;startingHandoff=false;handoffAttempt++;
        EnvironmentHandoffController task=handoff;if(task!=null&&task.active())task.cancel();
        else if(claimed&&routes!=null)routes.cancelHandoff();
        if(pending!=null&&routes!=null)try{routes.handoff("cancel",new JSONObject().put("accountId",accountId).put("id",pending),(result,error)->{if(result!=null&&result.optJSONObject("job")!=null)changed(result.optJSONObject("job"));});}catch(Exception ignored){/* The durable ready task remains visible in task history. */}
        renderHandoff();
    }
    @Override public boolean valid(Entry entry){
        if(destroyed||!resumed||!routeReady||entry!=current||entry.closed||browser==null||browser.getSession()!=entry.session||!entry.session.isOpen()||!owns(entry))return false;
        try{WorkspaceState latest=store.load();return latest.activeAccount.equals(entry.accountId)&&latest.find(entry.accountId).activeTab.equals(entry.tabId);}catch(Exception invalid){return false;}
    }
    @Override public GeckoView view(Entry entry){return valid(entry)?browser:null;}
    @Override public void command(String action,JSONObject data,java.util.function.BiConsumer<JSONObject,String> callback){if(routes==null){callback.accept(null,"任务服务不可用");return;}routes.handoff(action,data,callback);}
    @Override public void changed(JSONObject job){if(destroyed)return;try{handoffJob=new JSONObject(job.toString());renderHandoff();}catch(Exception invalid){notice("任务进度暂不可读，请查看任务记录");}}
    @Override public void stopped(EnvironmentHandoffController task,String message){if(handoff==task)handoff=null;renderHeader();if(message!=null&&!message.isEmpty())notice(message);}
    private void renderHandoff(){
        if(handoffBanner==null)return;
        if(handoffJob==null&&handoffBusy()){handoffBanner.setVisibility(View.VISIBLE);handoffStatus.setText("等待打开 · 网页任务");handoffStop.setVisibility(View.VISIBLE);return;}
        boolean visible=handoffJob!=null&&(accountId.equals(handoffJob.optString("sourceAccountId"))||accountId.equals(handoffJob.optString("targetAccountId")));
        handoffBanner.setVisibility(visible?View.VISIBLE:View.GONE);if(!visible)return;
        handoffStatus.setText(HandoffStatus.name(handoffJob.optString("status"))+" · "+handoffJob.optString("label"));
        handoffStop.setVisibility(handoffBusy()?View.VISIBLE:View.GONE);
    }
    private void showHandoffHistory(){showHandoffHistory(0);}
    private void showHandoffHistory(int offset){
        if(routes==null)return;String owner=accountId;
        try{routes.handoff("list",new JSONObject().put("accountId",owner).put("offset",offset),(result,error)->{
            if(destroyed||!owner.equals(accountId))return;if(error!=null||result==null){notice("任务记录暂不可读取，请保留应用数据");return;}
            try{JSONArray jobs=result.getJSONArray("jobs");String[] labels=new String[jobs.length()];for(int i=0;i<jobs.length();i++){JSONObject job=jobs.getJSONObject(i);labels[i]=HandoffStatus.name(job.getString("status"))+" · "+job.getString("label")+"\n"+job.getString("sourceName")+" → "+job.getString("targetName");}
                if(labels.length==0){new AlertDialog.Builder(this).setTitle("当前账号的网页任务").setMessage("暂无网页任务").setNegativeButton("关闭",null).show();return;}
                AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("当前账号的网页任务").setItems(labels,(parent,index)->showHandoffJob(jobs.optJSONObject(index).optString("id"))).setNegativeButton("关闭",null);if(result.optBoolean("more"))dialog.setPositiveButton("较早任务",(parent,which)->showHandoffHistory(offset+20));if(offset>0)dialog.setNeutralButton("较新任务",(parent,which)->showHandoffHistory(Math.max(0,offset-20)));dialog.show();
            }catch(Exception invalid){notice("任务记录暂不可读，请保留应用数据");}
        });}catch(Exception invalid){notice("任务服务暂不可用");}
    }
    private void showHandoffJob(String id){
        if(routes==null)return;String owner=accountId;
        try{routes.handoff("get",new JSONObject().put("accountId",owner).put("id",id),(result,error)->{
            if(destroyed||!owner.equals(accountId))return;if(error!=null||result==null){notice("任务记录暂不可读取");return;}
            try{JSONObject job=result.getJSONObject("job");StringBuilder detail=new StringBuilder(job.getString("sourceName")).append(" → ").append(job.getString("targetName")).append("\n\n").append(job.getString("message")).append("\n\n附件：\n");JSONArray files=job.getJSONArray("files");for(int i=0;i<files.length();i++)detail.append("- ").append(files.getJSONObject(i).getString("uploadName")).append('\n');
                AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(HandoffStatus.name(job.getString("status"))+" · "+job.getString("label")).setMessage(detail.toString()).setNegativeButton("关闭",null).setPositiveButton("打开目标对话",(d,w)->openHandoffJob(job));
                if(HandoffStore.active(job.getString("status")))dialog.setNeutralButton("停止自动处理",(d,w)->{try{routes.handoff("cancel",new JSONObject().put("accountId",owner).put("id",id),(updated,failure)->{if(updated!=null&&updated.optJSONObject("job")!=null)changed(updated.optJSONObject("job"));else notice("任务未能停止，请检查目标网页");});}catch(Exception invalid){notice("任务未能停止，请检查目标网页");}});dialog.show();
            }catch(Exception invalid){notice("任务详情暂不可读");}
        });}catch(Exception invalid){notice("任务服务暂不可用");}
    }
    private void openHandoffJob(JSONObject job){
        try{String target=job.getString("targetAccountId"),storage=job.getString("targetStorageId"),tab=job.optString("tabId"),url=job.optString("conversationURL");WorkspaceState latest=store.load();WorkspaceState.Account destination=latest.find(target);if(!destination.storageId.equals(storage))throw new IOException();boolean exists=destination.tabs.stream().anyMatch(candidate->candidate.id.equals(tab));
            if(!exists&&(url.isEmpty()||(!NavigationPolicy.chat(url)&&!CIBuild.environmentUrl(url)))){notice("目标标签页已关闭，请手动检查原对话");return;}
            store.update(state->{WorkspaceState.Account account=state.find(target);if(!account.storageId.equals(storage))throw new IllegalStateException("目标登录空间已改变");state.activeAccount=target;if(exists){if(account.tabs.stream().noneMatch(candidate->candidate.id.equals(tab)))throw new IllegalStateException("目标标签页已关闭");account.activeTab=tab;}else state.addTab(account).url=url;});
            EnvironmentLauncher.open(this,target);
        }catch(Exception invalid){notice("目标账号或登录空间已改变，不能自动打开旧任务");}
    }
    private void external(String url){if(!NavigationPolicy.https(url)){notice("此链接无法在应用中打开");return;}new AlertDialog.Builder(this).setTitle("在外部浏览器打开？").setMessage(Uri.parse(url).getHost()).setPositiveButton("打开",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception error){notice("没有可用的外部浏览器");}}).setNegativeButton("取消",null).show();}
    private void download(Entry e,WebResponse response){
        e.requestedUrl=null;e.loadingUrl=null;renderHeader();
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST&&EnvironmentLocalExports.kind(response.uri)!=null)EnvironmentLocalExports.diagnostic("external-response body="+(response.body!=null)+" broker="+(routes!=null));
        if(response.body==null)return;if(EnvironmentLocalExports.kind(response.uri)!=null){if(routes!=null)routes.localExports.accept(e,response);else try{response.body.close();}catch(IOException ignored){}return;}if(!owns(e)||!routeReady||routes==null){try{response.body.close();}catch(Exception ignored){}return;}
        String mime=response.headers.get("Content-Type");if(mime==null)mime="application/octet-stream";String contentType=mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);if(!contentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))contentType="application/octet-stream";String name=android.webkit.URLUtil.guessFileName(response.uri,response.headers.get("Content-Disposition"),contentType);
        final String filename=AssetLedger.filename(name),type=contentType;long expected=-1;
        try{String header=response.headers.get("Content-Length");if(header!=null){expected=Long.parseLong(header);if(expected<0)expected=-1;}}catch(NumberFormatException ignored){}
        java.lang.ref.WeakReference<EnvironmentBrowserActivity> target=new java.lang.ref.WeakReference<>(this);
        EnvironmentTransferController.Lease ticket=routes.transfers.claim(e,response.headers.get(EnvironmentTransferController.HEADER),response.uri);
        if(ticket==null){try{response.body.close();}catch(IOException ignored){}notice("此下载的原线路无法确认，请重新从页面发起");return;}
        e.activeTransfers++;renderHeader();
        routes.importAsset(ticket,filename,type,response.body,expected,(success,message)->{
            e.activeTransfers=Math.max(0,e.activeTransfers-1);EnvironmentBrowserActivity activity=target.get();if(activity!=null&&!activity.destroyed){activity.renderHeader();activity.notice(message);}
        });
    }
    @Override public void localActivation(Entry e,JSONObject value)throws Exception{if(routes!=null)routes.localExports.activation(e,value);}
    @Override public void localProof(Entry e,JSONObject value)throws Exception{if(routes!=null)routes.localExports.proof(e,value);}
    @Override public void localSourceInvalidated(Entry e){if(routes!=null)routes.localExports.invalidated(e);}
    void localExportChanged(Entry e){if(!destroyed)renderHeader();}
    StudioSheet confirmLocalExport(Entry e,String filename,Runnable save,Runnable cancel){
        if(destroyed||isFinishing()){cancel.run();return null;}
        String name;try{name=store.load().find(e.accountId).name;}catch(Exception unavailable){cancel.run();return null;}
        StudioSheet sheet=new StudioSheet(this,"保存网页文件",name);sheet.body.addView(sheet.text(filename,15));final boolean[] decided={false};
        sheet.action("保存到资产库",true,()->{decided[0]=true;save.run();});sheet.action("取消",false,()->{decided[0]=true;cancel.run();});
        sheet.setOnDismissListener(dialog->{if(!decided[0])cancel.run();});sheet.show();return sheet;
    }
    private void cancelFile(){
        PickerCopy copying=fileCopy;fileCopy=null;if(copying!=null){copying.cancel();discardPickerDirectory(copying.directory);}
        EnvironmentTransferController.Lease lease=fileLease;fileLease=null;if(lease!=null&&routes!=null)routes.transfers.release(lease,"cancel");
        if(fileResult!=null){if(!filePrompt.isComplete())fileResult.complete(filePrompt.dismiss());fileResult=null;filePrompt=null;fileOwner=null;renderHeader();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=804||fileResult==null)return;
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending=fileResult;GeckoSession.PromptDelegate.FilePrompt prompt=filePrompt;Entry owner=fileOwner;EnvironmentTransferController.Lease lease=fileLease;
        if(result!=RESULT_OK||data==null||owner!=current||!owns(owner)||owner.epoch!=fileEpoch||!owner.url.equals(fileUrl)||!routeReady){cancelFile();return;}
        ArrayList<Uri> uris=new ArrayList<>();try{
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());
            if(uris.isEmpty()||uris.size()>30)throw new IOException();for(Uri uri:uris)if(!"content".equals(uri.getScheme()))throw new IOException();
        }catch(Exception invalid){cancelFile();notice("所选文件不可用");return;}
        routes.transfers.validatePicker(lease,valid->{
            if(fileResult!=pending)return;
            if(!valid||destroyed||owner!=current||!owns(owner)||owner.epoch!=fileEpoch||!owner.url.equals(fileUrl)||!routeReady){cancelFile();notice("线路或账号已改变，请重新选择文件");return;}
            stagePickerFiles(owner,lease,uris,prompt,pending);
        });
    }
    private File pickerSlot()throws IOException{return new File(new File(getCacheDir().getCanonicalFile(),"environment-picker"),Integer.toString(slot()));}
    private void cleanColdPickerCache(){
        PICKER_IO.execute(()->{try{File slot=pickerSlot(),base=slot.getParentFile();if(!base.exists())return;HandoffFiles.directory(base,false);if(slot.exists()){HandoffFiles.directory(slot,false);deletePickerTree(slot);}}catch(Exception unavailable){pickerLog("cold-cleanup-failed");}});
    }
    private static void discardPickerDirectory(File directory){if(directory!=null)PICKER_IO.execute(()->deletePickerTree(directory));}
    private static void deletePickerTree(File directory){
        if(directory==null||!Files.exists(directory.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        try{Files.walkFileTree(directory.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });}catch(IOException cleanup){pickerLog("cleanup-failed");}
    }
    private static void pickerLog(String reason){if(BuildConfig.ENVIRONMENT_NETWORK_TEST)android.util.Log.i("StudioEnvironmentPicker","reason="+reason);}
    private void requirePickerCopy(PickerCopy copy)throws IOException{
        if(copy.cancelled||copy.lease.closed||copy.owner.closed||copy.owner.epoch!=copy.epoch||!copy.url.equals(copy.owner.url)||!owns(copy.owner))throw new IOException("文件选择已失效");
    }
    private String pickerFilename(Uri uri,int index,CancellationSignal signal){
        String label=null;
        try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null,signal)){
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(column>=0&&!cursor.isNull(column))label=cursor.getString(column);}
        }catch(OperationCanceledException cancelled){throw cancelled;}catch(RuntimeException unsupported){signal.throwIfCanceled();pickerLog("display-name-unavailable");}
        return AssetLedger.filename(label==null?"文件"+(index+1):label);
    }
    private void stagePickerFiles(Entry owner,EnvironmentTransferController.Lease lease,List<Uri> sources,GeckoSession.PromptDelegate.FilePrompt prompt,GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending){
        if(fileCopy!=null){cancelFile();return;}
        PickerCopy copy=new PickerCopy(owner,lease,fileEpoch,fileUrl);fileCopy=copy;lease.onRevoke=copy::cancel;
        PICKER_IO.execute(()->{
            ArrayList<Uri> staged=new ArrayList<>();boolean copied=false;
            try{
                requirePickerCopy(copy);File slot=pickerSlot(),base=slot.getParentFile();HandoffFiles.directory(base,true);HandoffFiles.directory(slot,true);
                File entry=new File(slot,owner.storageId+"-"+owner.tabId);HandoffFiles.directory(entry,true);copy.directory=new File(entry,UUID.randomUUID().toString());HandoffFiles.directory(copy.directory,true);
                for(int i=0;i<sources.size();i++){
                    requirePickerCopy(copy);Uri source=sources.get(i);String name=pickerFilename(source,i,copy.signal);File part=new File(copy.directory,Integer.toString(i));HandoffFiles.directory(part,true);File destination=new File(part,name);
                    try(android.content.res.AssetFileDescriptor asset=getContentResolver().openAssetFileDescriptor(source,"r",copy.signal)){
                        if(asset==null)throw new IOException("无法读取所选文件");
                        try(InputStream input=asset.createInputStream();OutputStream output=Files.newOutputStream(destination.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                            copy.input=input;copy.output=output;requirePickerCopy(copy);byte[] buffer=new byte[65536];int count;
                            while((count=input.read(buffer))!=-1){requirePickerCopy(copy);if(count>0)output.write(buffer,0,count);}output.flush();requirePickerCopy(copy);
                        }finally{copy.input=null;copy.output=null;}
                    }
                    staged.add(Uri.fromFile(destination));
                }
                requirePickerCopy(copy);copied=true;
            }catch(Exception failure){copy.cancel();pickerLog("copy-failed-"+failure.getClass().getSimpleName());}
            finally{if(!copied)deletePickerTree(copy.directory);}
            final boolean complete=copied;ui.post(()->{
                if(fileResult!=pending||fileCopy!=copy){copy.cancel();discardPickerDirectory(copy.directory);return;}
                if(!complete){cancelFile();notice("所选文件未能读取，请重新选择");return;}
                // Recheck the original broker lease after all real provider bytes have been copied.
                routes.transfers.validatePicker(lease,valid->{
                    if(fileResult!=pending||fileCopy!=copy){copy.cancel();discardPickerDirectory(copy.directory);return;}
                    if(!valid||copy.cancelled||destroyed||owner!=current||!owns(owner)||owner.epoch!=copy.epoch||!owner.url.equals(copy.url)||!routeReady||prompt.isComplete()){
                        cancelFile();discardPickerDirectory(copy.directory);pickerLog("confirm-rejected");notice("线路或账号已改变，请重新选择文件");return;
                    }
                    fileResult=null;filePrompt=null;fileOwner=null;fileLease=null;fileCopy=null;lease.onRevoke=null;
                    try{
                        copy.ready=true;owner.pickerDirectories.add(copy.directory);
                        pending.complete(prompt.confirm(getApplicationContext(),staged.toArray(new Uri[0])));pickerLog("confirmed-private-copy");
                    }catch(Exception unavailable){
                        owner.pickerDirectories.remove(copy.directory);copy.ready=false;copy.cancel();discardPickerDirectory(copy.directory);if(!prompt.isComplete())pending.complete(prompt.dismiss());pickerLog("confirm-failed");notice("所选文件不可用");
                    }finally{routes.transfers.selected(lease);renderHeader();}
                });
            });
        });
    }
    private void close(Entry e){if(handoff!=null&&handoff.entry==e)cancelHandoff("任务标签页已关闭；不会自动重发");if(routes!=null)routes.localExports.invalidated(e);if(fileOwner==e)cancelFile();if(routes!=null)routes.transfers.closeEntry(e);if(pageOperation!=null&&pageOperation.entry==e)cancelPageOperation();e.closed=true;e.historyDocuments.clear();for(File directory:e.pickerDirectories)discardPickerDirectory(directory);e.pickerDirectories.clear();if(e.saveTask!=null)ui.removeCallbacks(e.saveTask);if(documents!=null)documents.invalidate(e);if(e.session.isOpen())e.session.close();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);cancelHandoff("目标账号或任务已切换；不会自动重发");cancelPageOperation();cancelFile();setIntent(intent);accountId=intent.getStringExtra("accountId");captureHandoff(intent);if(store!=null)showCurrent();drainHandoff();}
    @Override protected void onResume(){super.onResume();resumed=true;if(routes!=null)routes.foreground(true);if(browser!=null){showCurrent();if(current!=null)current.session.setFocused(true);}drainHandoff();}
    @Override protected void onPause(){cancelHandoff("已离开目标网页，已停止自动发送");cancelPageOperation();resumed=false;if(current!=null&&!current.closed){documents.request(current);current.session.flushSessionState();current.session.setFocused(false);}for(Entry e:SESSIONS.values())if(e.saveTask!=null){ui.removeCallbacks(e.saveTask);e.saveTask.run();}if(routes!=null)routes.foreground(false);super.onPause();}
    @Override protected void onStop(){super.onStop();ui.post(()->{for(Entry e:SESSIONS.values())if(!e.closed&&e.session.isOpen())e.session.setActive(appForeground);});}
    @Override protected void onDestroy(){cancelHandoff("目标窗口已关闭；不会自动重发");destroyed=true;if(routes!=null)routes.localExports.detach(this);cancelPageOperation();for(Entry e:SESSIONS.values())if(e.saveTask!=null){ui.removeCallbacks(e.saveTask);e.saveTask=null;}cancelFile();if(browser!=null&&browser.getSession()!=null)browser.releaseSession();if(routes!=null){routes.updatePrompt=null;routes.cancelHandoff();routes.handoffListener(null);routes.foreground(false);routes.whenExtensionReady(null);routes.listener((ready,foreground,notice)->{for(Entry e:SESSIONS.values())if(!e.closed&&e.session.isOpen())e.session.setActive(foreground);});}super.onDestroy();}
    @Override public void notice(String message){if(destroyed)return;Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void failure(String title,String message){LinearLayout pane=new LinearLayout(this);pane.setOrientation(LinearLayout.VERTICAL);pane.setPadding(dp(24),dp(32),dp(24),dp(24));pane.addView(text(title,20));pane.addView(text(message==null?"请保留应用数据后重新打开":message,14));Button back=new Button(this);back.setText("返回账号列表");back.setOnClickListener(v->accounts());pane.addView(back);setContentView(pane);}
    private TextView text(String value,int size){TextView view=new TextView(this);StudioTypography.text(view,size);view.setTextColor(StudioTypography.INK);view.setText(value);return view;}
    private TextView button(String value,String description){TextView view=text(value,14);view.setContentDescription(description);view.setGravity(Gravity.CENTER);view.setMinHeight(StudioTypography.controlHeight(this));view.setPadding(dp(12),dp(8),dp(12),dp(8));view.setBackgroundResource(android.R.drawable.list_selector_background);view.setClickable(true);view.setFocusable(true);return view;}
    private int dp(int value){return StudioTypography.dp(this,value);}
    public static final class Slot0 extends EnvironmentBrowserActivity {}
    public static final class Slot1 extends EnvironmentBrowserActivity {}
    public static final class Slot2 extends EnvironmentBrowserActivity {}
    public static final class Slot3 extends EnvironmentBrowserActivity {}
    public static final class Slot4 extends EnvironmentBrowserActivity {}
    public static final class Slot5 extends EnvironmentBrowserActivity {}
    public static final class Slot6 extends EnvironmentBrowserActivity {}
    public static final class Slot7 extends EnvironmentBrowserActivity {}
    public static final class Slot8 extends EnvironmentBrowserActivity {}
    public static final class Slot9 extends EnvironmentBrowserActivity {}
    public static final class Slot10 extends EnvironmentBrowserActivity {}
    public static final class Slot11 extends EnvironmentBrowserActivity {}
    public static final class Slot12 extends EnvironmentBrowserActivity {}
    public static final class Slot13 extends EnvironmentBrowserActivity {}
    public static final class Slot14 extends EnvironmentBrowserActivity {}
    public static final class Slot15 extends EnvironmentBrowserActivity {}
    public static final class Slot16 extends EnvironmentBrowserActivity {}
    public static final class Slot17 extends EnvironmentBrowserActivity {}
    public static final class Slot18 extends EnvironmentBrowserActivity {}
    public static final class Slot19 extends EnvironmentBrowserActivity {}
    public static final class Slot20 extends EnvironmentBrowserActivity {}
    public static final class Slot21 extends EnvironmentBrowserActivity {}
    public static final class Slot22 extends EnvironmentBrowserActivity {}
    public static final class Slot23 extends EnvironmentBrowserActivity {}
    public static final class Slot24 extends EnvironmentBrowserActivity {}
    public static final class Slot25 extends EnvironmentBrowserActivity {}
    public static final class Slot26 extends EnvironmentBrowserActivity {}
    public static final class Slot27 extends EnvironmentBrowserActivity {}
    public static final class Slot28 extends EnvironmentBrowserActivity {}
    public static final class Slot29 extends EnvironmentBrowserActivity {}
}
