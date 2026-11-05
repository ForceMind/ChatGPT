package com.forcemind.chatgpt.profiles;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.system.Os;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.mozilla.geckoview.*;

/** Real account browser. Each nested activity owns exactly one immutable environment process. */
public class EnvironmentBrowserActivity extends Activity implements EnvironmentDocumentBridge.Host {
    static final class Entry {
        final String accountId,storageId,tabId;final GeckoSession session;
        String url,token="",documentId,restoringDraft,nativeState="";volatile String lastFingerprint="";
        long epoch,sequence,writeVersion;volatile boolean closed;boolean started,previewDismissed,sensitive,canBack,canForward;
        JSONObject record,pendingRestore,liveDocument,conflictLocal;
        WebExtension.Port port;Runnable saveTask;
        Entry(WorkspaceState.Account a,WorkspaceState.Tab tab){accountId=a.id;storageId=a.storageId;tabId=tab.id;url=tab.url.equals(NavigationPolicy.HOME)?CIBuild.home():tab.url;session=new GeckoSession(new GeckoSessionSettings.Builder().contextId("studio-"+a.storageId).build());}
    }
    private static GeckoRuntime runtime;private static String runtimeEnvironment;private static EnvironmentRouteController processRoutes;
    private static final Set<String> REQUESTED_DELETES=new HashSet<>();
    static final Map<String,Entry> SESSIONS=new LinkedHashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private StateStore store;private EnvironmentRecoveryStore recovery;private EnvironmentDocumentBridge documents;private EnvironmentRouteController routes;
    private WorkspaceState state;private String accountId;private Entry current;private GeckoView browser;
    private TextView accountButton,tabButton,status;private FrameLayout canvas;private View preview;
    private boolean routeReady,appForeground,destroyed,resumed,conflictShowing;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;private Entry fileOwner;private long fileEpoch;private String fileUrl;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store=new StateStore(getFilesDir());accountId=getIntent().getStringExtra("accountId");
        try{
            state=store.load();WorkspaceState.Account a=state.find(accountId);if(!a.isEnvironment()||a.environmentSlot!=slot())throw new IOException("账号环境归属已改变");
            String environment=a.language+"/"+a.timeZone;
            if(runtime==null){
                Locale locale=Locale.forLanguageTag(a.language);Locale.setDefault(locale);LocaleList.setDefault(new LocaleList(locale));TimeZone.setDefault(TimeZone.getTimeZone(a.timeZone));Os.setenv("TZ",a.timeZone,true);
                File profiles=new File(getFilesDir(),"environment-profiles");HandoffFiles.directory(profiles,true);File profile=new File(profiles,Integer.toString(slot()));HandoffFiles.directory(profile,true);
                Bundle env=new Bundle();env.putString("env0","TZ="+a.timeZone);
                runtime=GeckoRuntime.create(getApplicationContext(),new GeckoRuntimeSettings.Builder().arguments(new String[]{"-profile",profile.getAbsolutePath()}).extras(env).locales(new String[]{a.language}).remoteDebuggingEnabled(false).consoleOutput(false).build());runtimeEnvironment=environment;
            }
            if(!environment.equals(runtimeEnvironment))throw new IOException("账号环境与进程不一致，请重新打开应用");
            recovery=new EnvironmentRecoveryStore(this);documents=new EnvironmentDocumentBridge(this);buildUi();
            // Reattach delegates to this Activity after Android recreates the window.
            reconcile();for(Entry e:SESSIONS.values())configure(e,this);
            EnvironmentRouteController.Listener routeListener=(ready,foreground,notice)->{
                boolean wasReady=routeReady;routeReady=ready;appForeground=foreground;
                if(status!=null){status.setText(notice);status.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);}
                for(Entry e:SESSIONS.values())if(!e.closed){e.session.setActive(foreground);if(wasReady&&!ready&&e.started)e.session.stop();}
                if(ready)startCurrent();
            };
            if(processRoutes==null)processRoutes=new EnvironmentRouteController(getApplicationContext(),runtime,routeListener);
            routes=processRoutes;routes.listener(routeListener);
            routes.whenExtensionReady(()->{for(Entry e:SESSIONS.values())documents.attach(e,routes.extensionReady);startCurrent();});
            showCurrent();
        }catch(Exception error){failure("账号页面暂时无法打开",error.getMessage());}
    }
    private int slot(){String name=getClass().getSimpleName();if(!name.startsWith("Slot"))throw new IllegalStateException("缺少账号环境");return Integer.parseInt(name.substring(4));}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(10),dp(3),dp(8),dp(3));
        accountButton=button("账号","选择或管理账号");accountButton.setMaxLines(1);accountButton.setEllipsize(android.text.TextUtils.TruncateAt.END);accountButton.setGravity(Gravity.CENTER_VERTICAL);head.addView(accountButton,new LinearLayout.LayoutParams(0,dp(52),1));accountButton.setOnClickListener(v->accounts());
        tabButton=button("标签 1","切换标签页");head.addView(tabButton);tabButton.setOnClickListener(v->tabs());TextView more=button("•••","更多功能");head.addView(more,new LinearLayout.LayoutParams(dp(52),dp(52)));more.setOnClickListener(v->more());root.addView(head);
        View divider=new View(this);divider.setBackgroundColor(0xffeeeeee);root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        status=text("正在连接账号环境",12);status.setTextColor(0xff666666);status.setPadding(dp(16),dp(7),dp(16),dp(7));root.addView(status);
        canvas=new FrameLayout(this);browser=new GeckoView(this);canvas.addView(browser,new FrameLayout.LayoutParams(-1,-1));root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
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
            if(e.record!=null){e.nativeState=e.record.optString("nativeSessionState","");e.pendingRestore=e.record.optJSONObject("document");}
        }catch(Exception error){notice("本地恢复记录暂不可读取，原件已保留");}
        if(e.record==null)e.record=freshRecord(e);
        if(routes!=null&&routes.extensionReady!=null)documents.attach(e,routes.extensionReady);
        return e;
    }
    private JSONObject freshRecord(Entry e)throws Exception{return new JSONObject().put("schema",1).put("accountId",e.accountId).put("storageId",e.storageId).put("tabId",e.tabId).put("url",EnvironmentUrls.recoverable(e.url));}
    private void showCurrent(){
        try{
            reconcile();WorkspaceState.Account a=state.find(accountId);if(!a.isEnvironment()||a.environmentSlot!=slot()){EnvironmentLauncher.open(this,a.id);finish();return;}
            if(a.current().url.equals(NavigationPolicy.HOME)&&!CIBuild.home().equals(NavigationPolicy.HOME)){state=store.update(s->{WorkspaceState.Account owner=s.find(accountId);owner.current().url=CIBuild.home();});a=state.find(accountId);}
            Entry next=entry(a,a.current());if(current!=next){if(current!=null&&!current.closed){documents.request(current);current.session.flushSessionState();current.session.setFocused(false);}if(browser.getSession()!=null)browser.releaseSession();if(current!=null&&!current.closed)current.session.setActive(appForeground||resumed);current=next;if(!next.session.isOpen())next.session.open(runtime);browser.setSession(next.session);next.session.setActive(appForeground||resumed);next.session.setFocused(resumed);}
            accountButton.setText(a.name);tabButton.setText("标签 "+a.tabs.size());
            if(preview!=null){canvas.removeView(preview);preview=null;}
            if(!next.started&&!next.previewDismissed&&next.record.optJSONObject("document")!=null)showPreview(next);else startCurrent();
        }catch(Exception error){failure("账号已改变",error.getMessage());}
    }
    private void startCurrent(){
        if(destroyed||current==null||current.closed||current.started||!routeReady||routes==null||routes.extensionReady==null)return;
        if(!owns(current))return;if(!current.previewDismissed&&current.record.optJSONObject("document")!=null)return;
        current.started=true;current.session.setActive(appForeground||resumed);current.session.setFocused(resumed);
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
                if(!ownerActivity.routeReady||!ownerActivity.owns(e))return GeckoResult.fromValue(AllowOrDeny.DENY);
                if(!NavigationPolicy.internal(request.uri)&&!CIBuild.environmentUrl(request.uri)){if(e==ownerActivity.current)ownerActivity.external(request.uri);return GeckoResult.fromValue(AllowOrDeny.DENY);}
                if(!request.uri.equals(e.url))ownerActivity.navigation(e,request.uri);return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override public void onLocationChange(GeckoSession session,String url,List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean gesture){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(url!=null&&!url.equals(e.url))ownerActivity.navigation(e,url);}
            @Override public void onCanGoBack(GeckoSession session,boolean value){e.canBack=value;}
            @Override public void onCanGoForward(GeckoSession session,boolean value){e.canForward=value;}
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session,String uri){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(null);}
                if(!ownerActivity.routeReady||!ownerActivity.owns(e)||(!NavigationPolicy.internal(uri)&&!CIBuild.environmentUrl(uri))){ownerActivity.notice("此窗口无法在账号中打开");return GeckoResult.fromValue(null);}
                try{final String[] id={null};ownerActivity.state=ownerActivity.store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))throw new IllegalStateException("登录空间已改变");WorkspaceState.Tab t=s.addTab(a);if(EnvironmentUrls.chat(uri))t.url=EnvironmentUrls.recoverable(uri);id[0]=t.id;});WorkspaceState.Account a=ownerActivity.state.find(e.accountId);Entry child=ownerActivity.entry(a,a.current());child.previewDismissed=true;child.started=true;ownerActivity.ui.post(ownerActivity::showCurrent);return GeckoResult.fromValue(child.session);}catch(Exception error){ownerActivity.notice("无法创建新标签");return GeckoResult.fromValue(null);}
            }
        });
        e.session.setProgressDelegate(new GeckoSession.ProgressDelegate(){
            @Override public void onSessionStateChange(GeckoSession session,GeckoSession.SessionState value){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(!ownerActivity.owns(e)||e.sensitive||EnvironmentUrls.recoverable(e.url)==null||!e.url.equals(EnvironmentUrls.recoverable(e.url)))return;try{String nativeValue=ownerActivity.nativeState(e,value);if(nativeValue==null||nativeValue.length()>700000)return;e.nativeState=nativeValue;ownerActivity.queueSave(e);}catch(Exception ignored){}}
            @Override public void onPageStop(GeckoSession session,boolean success){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(e==ownerActivity.current&&success&&ownerActivity.routeReady){ownerActivity.status.setVisibility(View.GONE);}else if(e==ownerActivity.current&&!success&&ownerActivity.routeReady){ownerActivity.status.setText("页面加载未完成，可在更多中刷新");ownerActivity.status.setVisibility(View.VISIBLE);}}
        });
        e.session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onTitleChange(GeckoSession session,String title){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}if(title==null||!ownerActivity.owns(e)||EnvironmentUrls.recoverable(e.url)==null)return;try{ownerActivity.state=ownerActivity.store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))return;for(WorkspaceState.Tab t:a.tabs)if(t.id.equals(e.tabId)&&!t.customTitle)t.title=title.substring(0,Math.min(120,title.length()));});}catch(Exception ignored){}}
            @Override public void onExternalResponse(GeckoSession session,WebResponse response){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){try{if(response.body!=null)response.body.close();}catch(Exception ignored){}return;}ownerActivity.download(e,response);}
            @Override public void onCrash(GeckoSession session){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}e.started=false;ownerActivity.documents.invalidate(e);ownerActivity.notice("页面进程已停止，本地恢复副本已保留");}
            @Override public void onKill(GeckoSession session){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return;}e.started=false;ownerActivity.documents.invalidate(e);ownerActivity.notice("页面已释放，可重新打开恢复");}
        });
        e.session.setPromptDelegate(new GeckoSession.PromptDelegate(){
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session,FilePrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(prompt.dismiss());}
                if(e!=ownerActivity.current||!ownerActivity.owns(e)||!ownerActivity.routeReady||ownerActivity.fileResult!=null||prompt.type==FilePrompt.Type.FOLDER){ownerActivity.notice("当前页面无法选择文件");return GeckoResult.fromValue(prompt.dismiss());}
                ownerActivity.fileOwner=e;ownerActivity.fileEpoch=e.epoch;ownerActivity.fileUrl=e.url;ownerActivity.filePrompt=prompt;ownerActivity.fileResult=new GeckoResult<>();GeckoResult<PromptResponse> selected=ownerActivity.fileResult;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,prompt.type==FilePrompt.Type.MULTIPLE);
                if(prompt.mimeTypes!=null&&prompt.mimeTypes.length>0)pick.putExtra(Intent.EXTRA_MIME_TYPES,prompt.mimeTypes);try{ownerActivity.startActivityForResult(pick,804);}catch(Exception error){ownerActivity.cancelFile();ownerActivity.notice("系统文件选择器不可用");}return selected;
            }
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session,AlertPrompt prompt){EnvironmentBrowserActivity ownerActivity=target.get();if(ownerActivity==null||ownerActivity.destroyed){return GeckoResult.fromValue(prompt.dismiss());}GeckoResult<PromptResponse> result=new GeckoResult<>();new AlertDialog.Builder(ownerActivity).setTitle("页面提示").setMessage(prompt.message).setPositiveButton("好",(d,w)->result.complete(prompt.dismiss())).setOnCancelListener(d->result.complete(prompt.dismiss())).show();return result;}
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
        if(e.closed)return;if(fileOwner==e)cancelFile();documents.invalidate(e);e.url=url;e.sensitive=false;e.nativeState="";e.pendingRestore=null;e.conflictLocal=null;e.restoringDraft=null;
        String safe=EnvironmentUrls.recoverable(url);if(safe==null)return;
        try{
            state=store.update(s->{WorkspaceState.Account a=s.find(e.accountId);if(!a.storageId.equals(e.storageId))throw new IllegalStateException("登录空间已改变");for(WorkspaceState.Tab t:a.tabs)if(t.id.equals(e.tabId))t.url=safe;});
            JSONObject saved=recovery.load(e.accountId,e.storageId,e.tabId,safe);e.record=saved==null?freshRecord(e):saved;e.pendingRestore=saved==null?null:saved.optJSONObject("document");
        }catch(Exception error){try{e.record=freshRecord(e);}catch(Exception ignored){}notice("此页面的本地恢复暂不可用");}
    }
    @Override public boolean owns(Entry e){
        if(e.closed)return false;try{WorkspaceState.Account a=store.load().find(e.accountId);return a.isEnvironment()&&a.environmentSlot==slot()&&a.storageId.equals(e.storageId)&&a.language.concat("/").concat(a.timeZone).equals(runtimeEnvironment)&&a.tabs.stream().anyMatch(t->t.id.equals(e.tabId));}catch(Exception error){return false;}
    }
    @Override public void document(Entry e,JSONObject value){if(!owns(e)||e.sensitive)return;try{e.record.put("document",value);queueSave(e);}catch(Exception ignored){}}
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
        if(e!=current||conflictShowing||e.conflictLocal==null)return;conflictShowing=true;
        StudioSheet sheet=new StudioSheet(this,"发现两份草稿","网页和本地草稿不同，请选择。任何选择都不会发送消息。");TextView local=sheet.text("本地草稿\n"+e.conflictLocal.optString("draft"),14);local.setTextIsSelectable(true);sheet.body.addView(local);TextView webpage=sheet.text("\n网页草稿\n"+live.optString("draft"),14);webpage.setTextIsSelectable(true);sheet.body.addView(webpage);
        sheet.action("保留网页草稿",false,()->{if(owns(e)&&e.liveDocument!=null){try{e.record.put("alternativeDraft",e.conflictLocal);e.pendingRestore=null;e.conflictLocal=null;document(e,e.liveDocument);}catch(Exception ignored){}}sheet.dismiss();});
        sheet.action("恢复本地草稿",true,()->{if(owns(e)&&e.liveDocument!=null)try{JSONObject localCopy=e.conflictLocal;e.record.put("alternativeDraft",e.liveDocument);e.pendingRestore=null;e.conflictLocal=null;documents.restore(e,localCopy,e.liveDocument);}catch(Exception error){notice("草稿恢复未完成");}sheet.dismiss();});sheet.setOnDismissListener(d->conflictShowing=false);sheet.show();
    }
    private void tabs(){
        try{reconcile();WorkspaceState.Account a=state.find(accountId);StudioSheet sheet=new StudioSheet(this,"标签页",a.name);for(WorkspaceState.Tab t:a.tabs){sheet.row((t.id.equals(a.activeTab)?"当前 · ":"")+t.title,"","",()->{sheet.dismiss();update(s->{WorkspaceState.Account owner=s.find(accountId);owner.activeTab=t.id;});});}sheet.action("新建标签页",true,()->{sheet.dismiss();newTab();});if(a.tabs.size()>1)sheet.action("关闭当前标签页",false,()->{sheet.dismiss();update(s->{WorkspaceState.Account owner=s.find(accountId);s.closeTab(owner,owner.activeTab);});});sheet.show();}catch(Exception error){notice("标签列表不可用");}
    }
    private void accounts(){
        try{reconcile();StudioSheet sheet=new StudioSheet(this,"账号空间","每个环境账号保留独立登录与标签");for(WorkspaceState.Account a:state.accounts)sheet.row(a.name,a.isEnvironment()?a.language+" · "+a.timeZone:"原网页登录空间","",()->{sheet.dismiss();EnvironmentLauncher.open(this,a.id);});sheet.action("创建账号",true,()->{sheet.dismiss();AccountEditor.show(this,store,null,saved->{state=saved;EnvironmentLauncher.open(this,saved.activeAccount);});});sheet.action("编辑当前账号",false,()->{sheet.dismiss();AccountEditor.show(this,store,accountId,saved->{state=saved;showCurrent();});});sheet.show();}catch(Exception error){notice("账号列表不可用");}
    }
    private void more(){
        StudioSheet sheet=new StudioSheet(this,"更多","");sheet.row("新建标签页","","plus",()->{sheet.dismiss();newTab();});sheet.row("账号管理","","user",()->{sheet.dismiss();accounts();});sheet.row("资产库","","folder",()->{sheet.dismiss();assets(false);});sheet.row("能力库","","spark",()->{sheet.dismiss();assets(true);});sheet.row("网络设置","","",()->{sheet.dismiss();startActivity(new Intent(this,MainActivity.class).putExtra("networkSettings",true));});sheet.row("刷新页面","","refresh",()->{sheet.dismiss();if(current!=null&&routeReady&&owns(current)){if(!current.started){current.previewDismissed=true;startCurrent();}else current.session.reload();}});sheet.row("恢复草稿","查看本地保存的草稿","",()->{sheet.dismiss();recoverySheet();});sheet.row("后退","","",()->{sheet.dismiss();if(current!=null&&routeReady&&current.canBack)current.session.goBack();});sheet.row("前进","","",()->{sheet.dismiss();if(current!=null&&routeReady&&current.canForward)current.session.goForward();});sheet.show();
    }
    private void recoverySheet(){
        if(current==null||current.record==null)return;Entry e=current;JSONObject doc=e.record.optJSONObject("document");StudioSheet sheet=new StudioSheet(this,"恢复草稿","本地副本不会自动发送");if(doc==null){sheet.body.addView(sheet.text("暂无本地草稿",14));}else{TextView copy=sheet.text(doc.optString("draft").isEmpty()?"暂无草稿文字":doc.optString("draft"),15);copy.setTextIsSelectable(true);sheet.body.addView(copy);sheet.action("恢复到当前页面",true,()->{sheet.dismiss();if(e.liveDocument==null){notice("请先继续页面，等待编辑器就绪");return;}e.pendingRestore=doc;conflict(e,e.liveDocument);});}sheet.show();
    }
    private void newTab(){update(s->{WorkspaceState.Account a=s.find(accountId);s.addTab(a);});}
    private void update(java.util.function.Consumer<WorkspaceState> change){try{state=store.update(change);showCurrent();}catch(Exception error){notice(error.getMessage()==null?"操作未完成":error.getMessage());}}
    private void assets(boolean skills){startActivity(new Intent(this,AssetWorkbenchActivity.class).putExtra(AssetWorkbenchActivity.ACCOUNT,accountId).putExtra("skills",skills));}
    private void external(String url){if(!NavigationPolicy.https(url)){notice("此链接无法在应用中打开");return;}new AlertDialog.Builder(this).setTitle("在外部浏览器打开？").setMessage(Uri.parse(url).getHost()).setPositiveButton("打开",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception error){notice("没有可用的外部浏览器");}}).setNegativeButton("取消",null).show();}
    private void download(Entry e,WebResponse response){
        if(response.body==null)return;if(!owns(e)||!routeReady||routes==null){try{response.body.close();}catch(Exception ignored){}return;}
        String mime=response.headers.get("Content-Type");if(mime==null)mime="application/octet-stream";String contentType=mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);if(!contentType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))contentType="application/octet-stream";String name=android.webkit.URLUtil.guessFileName(response.uri,response.headers.get("Content-Disposition"),contentType);
        final String filename=AssetLedger.filename(name),type=contentType;long expected=-1;
        try{String header=response.headers.get("Content-Length");if(header!=null){expected=Long.parseLong(header);if(expected<0)expected=-1;}}catch(NumberFormatException ignored){}
        java.lang.ref.WeakReference<EnvironmentBrowserActivity> target=new java.lang.ref.WeakReference<>(this);
        routes.importAsset(e.accountId,e.storageId,e.tabId,e.url,filename,type,response.body,expected,(success,message)->{
            EnvironmentBrowserActivity activity=target.get();if(activity!=null&&!activity.destroyed)activity.notice(message);
        });
    }
    private void cancelFile(){if(fileResult!=null){if(!filePrompt.isComplete())fileResult.complete(filePrompt.dismiss());fileResult=null;filePrompt=null;fileOwner=null;}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=804||fileResult==null)return;GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pending=fileResult;GeckoSession.PromptDelegate.FilePrompt prompt=filePrompt;Entry owner=fileOwner;fileResult=null;filePrompt=null;fileOwner=null;if(result!=RESULT_OK||data==null||owner!=current||!owns(owner)||owner.epoch!=fileEpoch||!owner.url.equals(fileUrl)){pending.complete(prompt.dismiss());return;}try{ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty()||uris.size()>30)throw new IOException();for(Uri uri:uris)if(!"content".equals(uri.getScheme()))throw new IOException();pending.complete(prompt.confirm(getApplicationContext(),uris.toArray(new Uri[0])));}catch(Exception error){pending.complete(prompt.dismiss());notice("所选文件不可用");}}
    private void close(Entry e){e.closed=true;if(e.saveTask!=null)ui.removeCallbacks(e.saveTask);if(documents!=null)documents.invalidate(e);if(e.session.isOpen())e.session.close();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);accountId=intent.getStringExtra("accountId");if(store!=null)showCurrent();}
    @Override protected void onResume(){super.onResume();resumed=true;if(routes!=null)routes.foreground(true);if(browser!=null){showCurrent();if(current!=null)current.session.setFocused(true);}}
    @Override protected void onPause(){resumed=false;if(current!=null&&!current.closed){documents.request(current);current.session.flushSessionState();current.session.setFocused(false);}for(Entry e:SESSIONS.values())if(e.saveTask!=null){ui.removeCallbacks(e.saveTask);e.saveTask.run();}if(routes!=null)routes.foreground(false);super.onPause();}
    @Override protected void onStop(){super.onStop();ui.post(()->{for(Entry e:SESSIONS.values())if(!e.closed&&e.session.isOpen())e.session.setActive(appForeground);});}
    @Override protected void onDestroy(){destroyed=true;for(Entry e:SESSIONS.values())if(e.saveTask!=null){ui.removeCallbacks(e.saveTask);e.saveTask=null;}cancelFile();if(browser!=null&&browser.getSession()!=null)browser.releaseSession();if(routes!=null){routes.foreground(false);routes.whenExtensionReady(null);routes.listener((ready,foreground,notice)->{for(Entry e:SESSIONS.values())if(!e.closed&&e.session.isOpen())e.session.setActive(foreground);});}super.onDestroy();}
    @Override public void onBackPressed(){if(current!=null&&current.canBack&&routeReady)current.session.goBack();else{startActivity(new Intent(this,MainActivity.class));finish();}}
    @Override public void notice(String message){if(destroyed)return;Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void failure(String title,String message){LinearLayout pane=new LinearLayout(this);pane.setOrientation(LinearLayout.VERTICAL);pane.setPadding(dp(24),dp(32),dp(24),dp(24));pane.addView(text(title,20));pane.addView(text(message==null?"请保留应用数据后重新打开":message,14));Button back=new Button(this);back.setText("返回账号列表");back.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class));finish();});pane.addView(back);setContentView(pane);}
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
