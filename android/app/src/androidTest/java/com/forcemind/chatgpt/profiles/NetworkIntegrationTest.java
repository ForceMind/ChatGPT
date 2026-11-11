package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.MotionEvent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** Real formal MainActivity and helpers. Owned HTTPS endpoints, never real ChatGPT or image capture. */
@RunWith(AndroidJUnit4.class)
public final class NetworkIntegrationTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private final JSONObject report=new JSONObject();
    private final Map<String,String> accounts=new LinkedHashMap<>(),tabs=new LinkedHashMap<>();
    private final Map<String,String> profileNames=new LinkedHashMap<>();
    private final Map<String,WebView> live=new LinkedHashMap<>();
    private final Map<String,String> documents=new LinkedHashMap<>();
    private final JSONArray files=new JSONArray(),requests=new JSONArray(),uploads=new JSONArray();
    private MainActivity activity;
    private BrowserMemory memory;
    private NetworkSession session;
    private String origin,prefix;
    private String reportFile="formal-network-result.json";
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private void until(String label,BooleanSupplier condition,long seconds)throws Exception{
        long end=SystemClock.elapsedRealtime()+seconds*1000;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}assertTrue(label,condition.getAsBoolean());
    }
    private String js(WebView web,String script)throws Exception{
        AtomicReference<String> value=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        main(()->web.evaluateJavascript(script,result->{value.set(result);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return value.get();
    }
    private void await(WebView web,String script,String expected)throws Exception{
        long end=SystemClock.elapsedRealtime()+20000;String value;
        do{value=js(web,script);if(expected.equals(value))return;Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);
        assertEquals("actual document: "+script,expected,value);
    }
    private Object invoke(MainActivity owner,String name,Class<?>[] types,Object... values){
        try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,values);}catch(Exception e){throw new AssertionError(name,e);}
    }
    private void select(String tab){
        String id=tabs.get(tab),account=accounts.get(tab.substring(0,1));
        main(()->{assertTrue((Boolean)invoke(activity,"change",new Class<?>[]{Consumer.class},(Consumer<WorkspaceState>)(state->{state.activeAccount=account;state.find(account).activeTab=id;})));invoke(activity,"showCurrent",new Class<?>[]{});});
    }
    private WebView view(String tab){AtomicReference<WebView> result=new AtomicReference<>();main(()->result.set(memory.views.get(tabs.get(tab))));assertNotNull(result.get());return result.get();}
    private String path(String name,String kind){return "/joint/"+prefix+name+"/"+kind;}
    private void request(String path,String route,String account)throws Exception{requests.put(new JSONObject().put("path",path).put("route",route).put("syntheticAccount",account).put("method","GET").put("status",200));}
    private void seed()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account a=state.current();a.name="Synthetic A";
        WorkspaceState.Tab a1=a.current(),a2=state.addTab(a);a.activeTab=a1.id;
        WorkspaceState.Account b=state.addAccount("Synthetic B","",WorkspaceState.COLORS[1]),c=state.addAccount("Synthetic C","",WorkspaceState.COLORS[2]);state.activeAccount=a.id;
        accounts.put("A",a.id);accounts.put("B",b.id);accounts.put("C",c.id);tabs.put("A1",a1.id);tabs.put("A2",a2.id);tabs.put("B1",b.activeTab);tabs.put("C1",c.activeTab);
        for(Map.Entry<String,String> item:tabs.entrySet())for(WorkspaceState.Account account:state.accounts)for(WorkspaceState.Tab tab:account.tabs)if(tab.id.equals(item.getValue()))tab.url=origin+path("page-"+item.getKey(),"page");
        new StateStore(context.getFilesDir()).save(state);
        for(String label:accounts.keySet()){
            WorkspaceState.Account account=state.find(accounts.get(label));CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> result=new AtomicReference<>();
            main(()->ProfileStore.getInstance().getOrCreateProfile(WorkspaceState.profile(account.storageId)).getCookieManager().setCookie(origin,"ci_joint_account="+label+"; Path=/; Secure; SameSite=Lax",ok->{result.set(ok);done.countDown();}));
            assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(result.get());
        }
        NetworkSettingsStore.Data settings=new NetworkSettingsStore.Data();settings.nodes.add(NetworkNode.parse(args.getString("nodeA"),"A"));settings.nodes.add(NetworkNode.parse(args.getString("nodeB"),"B"));settings.selected=0;settings.enabled=true;new NetworkSettingsStore(context).write(settings);
    }
    private String document(String tab)throws Exception{return js(view(tab),"({marker:window.__studioMarker,draft:document.getElementById('draft').value,storage:localStorage.getItem('studio-account'),cookie:document.cookie,url:location.href})");}
    private void page(String tab,boolean shared)throws Exception{
        select(tab);WebView web=view(tab);String account=tab.substring(0,1);await(web,"document.title",JSONObject.quote("A:"+account));
        assertEquals(shared?JSONObject.quote(account):"null",js(web,"localStorage.getItem('studio-account')"));
        js(web,"localStorage.setItem('studio-account',"+JSONObject.quote(account)+");window.__studioMarker="+JSONObject.quote(tab)+"+Date.now()+Math.random();document.getElementById('draft').value="+JSONObject.quote("draft-"+tab)+";true");
        AtomicReference<String> profile=new AtomicReference<>();main(()->profile.set(WebViewCompat.getProfile(web).getName()));
        assertEquals(WorkspaceState.profile(WorkspaceFiles.read(context.getFilesDir()).find(accounts.get(account)).storageId),profile.get());profileNames.put(account,profile.get());
        live.put(tab,web);documents.put(tab,document(tab));request(path("page-"+tab,"page"),"A",account);
    }
    private void route(int index)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();main(()->session.select(index,(ok,message)->{if(!ok)error.set(message);done.countDown();}));
        assertTrue(done.await(35,TimeUnit.SECONDS));assertNull(error.get());assertTrue(session.ready());assertEquals(NetworkRoute.State.CONNECTED,session.routes.snapshot().state);
    }
    private void upload(String account, String tab, String expectedRoute) throws Exception {
        select(tab);
        WebView web = view(tab);
        String target = path("upload-" + expectedRoute + "-" + account, "upload");
        String payload = "owned WebView bytes for account " + account + " via route " + expectedRoute + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        StringBuilder sha = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));

        js(web, "window.__ownedUpload=null;(()=>{let form=new FormData();form.append('file',new File(["
                + JSONObject.quote(payload) + "],'owned.bin',{type:'application/octet-stream'}));"
                + "fetch(" + JSONObject.quote(target) + ",{method:'POST',body:form}).then(async response=>"
                + "window.__ownedUpload={status:response.status,body:await response.json()})"
                + ".catch(()=>window.__ownedUpload={status:0});})();true");
        await(web, "window.__ownedUpload!==null", "true");
        JSONObject response = new JSONObject(js(web, "window.__ownedUpload"));
        assertEquals(200, response.getInt("status"));
        JSONObject result = response.getJSONObject("body");
        assertEquals(expectedRoute, result.getString("route"));
        assertEquals(account, result.getString("account"));
        assertEquals(bytes.length, result.getInt("fileBytes"));
        assertEquals(sha.toString(), result.getString("fileSha256"));
        uploads.put(result.put("path", target));
        requests.put(new JSONObject().put("path",target).put("route",expectedRoute)
                .put("syntheticAccount",account).put("method","POST").put("status",200));
    }

    private void asset(String account,String tab,String name,String expectedRoute)throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),accounts.get(account));int before=ledger.list().size();select(tab);
        main(()->{if(memory.assetDownloads==null)memory.assetDownloads=new AssetDownloads(context);memory.assetDownloads.start(viewOnMain(tab),accounts.get(account),"Synthetic "+account,origin+path(name,"download"),"CI-owned","attachment; filename=\""+name+".json\"","application/json");});
        select("C1");until("file task terminal",()->!session.busy(),30);List<JSONObject> after=ledger.list();assertEquals(before+1,after.size());JSONObject entry=after.get(0);assertEquals("ready",entry.getString("status"));assertEquals(accounts.get(account),entry.getString("accountId"));
        JSONObject body=new JSONObject(new String(Files.readAllBytes(ledger.verifiedFile(entry.getString("id")).toPath()),StandardCharsets.UTF_8));assertEquals(expectedRoute,body.getString("route"));assertEquals(account,body.getString("account"));assertEquals(prefix+name,body.getString("operation"));
        files.put(new JSONObject().put("exit","asset").put("account",account).put("route",expectedRoute).put("body",body).put("id",entry.getString("id")).put("sha256",entry.getString("sha256")));request(path(name,"download"),expectedRoute,account);
    }
    private void export(String account,String tab,String name,String expectedRoute)throws Exception{
        select(tab);
        Uri parent=DocumentsContract.buildDocumentUri(BuildConfig.APPLICATION_ID+".network-docs","root");
        Uri target=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/json",name+".json");assertNotNull(target);
        Intent result=new Intent().setData(target).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        IntentFilter filter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,result),true);
        try{
            main(()->((DownloadHelper)invoke(activity,"downloads",new Class<?>[]{})).choose(viewOnMain(tab),origin+path(name,"download"),"CI-owned","attachment; filename=\""+name+".json\"","application/json"));
            select("C1");until("document publication terminal",()->!session.busy(),30);assertEquals(1,monitor.getHits());
            byte[] bytes;try(java.io.InputStream input=context.getContentResolver().openInputStream(target)){assertNotNull(input);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){out.write(buffer,0,n);assertTrue(out.size()<1024*1024);}bytes=out.toByteArray();}
            JSONObject body=new JSONObject(new String(bytes,StandardCharsets.UTF_8));assertEquals(expectedRoute,body.getString("route"));assertEquals(account,body.getString("account"));
            files.put(new JSONObject().put("exit","document").put("account",account).put("route",expectedRoute).put("body",body));request(path(name,"download"),expectedRoute,account);
        }finally{instrumentation.removeMonitor(monitor);}
    }
    private void popup()throws Exception{
        select("A1");WebView parent=view("A1");InstrumentationRegistry.getInstrumentation().waitForIdleSync();AtomicReference<Boolean> inputReady=new AtomicReference<>(false);until("opener input and window ready",()->{main(()->inputReady.set(parent.isShown()&&parent.isEnabled()&&parent.getAlpha()==1f&&parent.hasWindowFocus()));return inputReady.get();},10);CountDownLatch visual=new CountDownLatch(1);main(()->parent.postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){@Override public void onComplete(long id){visual.countDown();}}));assertTrue("opener visual state committed",visual.await(10,TimeUnit.SECONDS));
        JSONObject rect=new JSONObject(js(parent,"(()=>{let r=document.getElementById('popup').getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;return {x,y,dpr:devicePixelRatio,hit:document.elementFromPoint(x,y).id}})()"));assertEquals("popup",rect.getString("hit"));
        int[] location=new int[2];main(()->parent.getLocationOnScreen(location));float x=location[0]+(float)(rect.getDouble("x")*rect.getDouble("dpr")),y=location[1]+(float)(rect.getDouble("y")*rect.getDouble("dpr"));
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+20,MotionEvent.ACTION_UP,x,y,0);down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(down,true));assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}
        AtomicReference<String> child=new AtomicReference<>();until("owned popup from real gesture",()->{main(()->{for(Map.Entry<String,String> item:memory.popupOwners.entrySet())if(item.getValue().equals(tabs.get("A1")))child.set(item.getKey());});return child.get()!=null;},15);
        tabs.put("AP",child.get());WebView web=view("AP");await(web,"document.title","\"A:A\"");assertEquals(accounts.get("A"),memory.accounts.get(child.get()));
        AtomicReference<String> profile=new AtomicReference<>();main(()->profile.set(WebViewCompat.getProfile(web).getName()));assertEquals(profileNames.get("A"),profile.get());
        assertEquals("\"A\"",js(web,"localStorage.getItem('studio-account')"));js(web,"window.__studioMarker='popup-'+Date.now();document.getElementById('draft').value='popup draft';true");live.put("AP",web);documents.put("AP",document("AP"));request(path("page-A1-popup","page"),"A","A");report.put("popupGesture",true);
    }
    private WebView viewOnMain(String tab){return memory.views.get(tabs.get(tab));}
    private void preserved(String tab)throws Exception{select(tab);assertSame(live.get(tab),view(tab));assertEquals(documents.get(tab),document(tab));}
    private void save(boolean complete)throws Exception{File evidence=new File(context.getFilesDir(),"network-document-evidence.json");if(evidence.isFile())report.put("lastDocumentEvidence",new JSONObject(new String(Files.readAllBytes(evidence.toPath()),StandardCharsets.UTF_8)));report.put("complete",complete).put("files",files).put("uploads",uploads).put("expectedRequests",requests);Files.write(new File(context.getFilesDir(),reportFile).toPath(),report.toString().getBytes(StandardCharsets.UTF_8));}
    @Test public void formalPagesAndAssetsRetainTheirAccountAndRoute()throws Exception{
        assertTrue(BuildConfig.NETWORK_TEST);assertTrue(BuildConfig.APPLICATION_ID.endsWith(".networkci"));assertTrue(NetworkTestPolicy.owned(NavigationPolicy.HOME));
        origin=args.getString("origin");String transport=args.getString("transport");prefix="formal-"+transport+"-";
        report.put("schema",1).put("sourceSHA",args.getString("sourceSHA")).put("api",android.os.Build.VERSION.SDK_INT).put("transport",transport).put("screenshots",false).put("realChatGPT",false);
        AtomicReference<NetworkSession> off=new AtomicReference<>();main(()->off.set(BrowserMemory.get(context).network()));until("default system readiness",()->off.get().ready(),15);
        assertEquals(NetworkRoute.State.SYSTEM,off.get().routes.snapshot().state);assertFalse(off.get().routes.coreInitialized());assertFalse(NetworkBenchmarkTest.goMapped(context));
        report.put("disabledCoreUnloaded",true);main(()->BrowserMemory.get(context).clear());seed();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(current->{activity=current;memory=BrowserMemory.get(context);session=memory.network();});until("restored selected route before pages",()->session.ready(),35);assertEquals("A",session.routes.snapshot().node.name);
            page("A1",false);page("A2",true);page("B1",false);page("C1",false);popup();
            assertEquals(3,new java.util.HashSet<>(profileNames.values()).size());
            upload("A","A1","A");
            for(String account:accounts.keySet()){asset(account,account.equals("A")?"A1":account+"1","asset-A-"+account,"A");export(account,account.equals("A")?"A1":account+"1","doc-A-"+account,"A");}
            MainActivity previous=activity;NetworkSession same=session;scenario.recreate();scenario.onActivity(current->{assertNotSame(previous,current);activity=current;assertSame(same,BrowserMemory.get(context).network());});
            for(String tab:tabs.keySet())preserved(tab);
            route(1);for(String tab:tabs.keySet())preserved(tab);
            upload("B","B1","B");
            for(String account:accounts.keySet()){asset(account,account.equals("A")?"A1":account+"1","asset-B-"+account,"B");export(account,account.equals("A")?"A1":account+"1","doc-B-"+account,"B");}
            select("B1");WebView web=view("B1");long generation=session.routes.snapshot().generation;Networkcore.stop();until("stopped core must fail closed",()->session.routes.snapshot().state==NetworkRoute.State.FAILED,6);assertFalse(session.ready());
            AtomicReference<Boolean> blocked=new AtomicReference<>(true);main(()->{for(WebView held:memory.views.values())if(!held.getSettings().getBlockNetworkLoads())blocked.set(false);});assertTrue(blocked.get());
            js(web,"window.__blocked='pending';fetch("+JSONObject.quote(path("failed-must-not-arrive","echo"))+").then(()=>window.__blocked='unexpected').catch(()=>window.__blocked='error');true");await(web,"window.__blocked","\"error\"");
            assertEquals(generation,session.routes.snapshot().generation);for(String tab:tabs.keySet())preserved(tab);
            CountDownLatch retry=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();main(()->session.retry((success,message)->{ok.set(success);retry.countDown();}));assertTrue(retry.await(35,TimeUnit.SECONDS));assertTrue(ok.get());
            for(String tab:tabs.keySet())preserved(tab);
            report.put("profileNames",new JSONObject(profileNames)).put("liveDocuments",5).put("systemPicker","ActivityMonitor result stub; actual system UI not yet verified").put("recreatedSameViews",true).put("selectedRouteRestoredBeforeLoad",true).put("failedCoreBlockedAllViews",true).put("failureDidNotReload",true).put("retryDidNotReload",true);
            save(true);
        }catch(Exception|AssertionError failure){report.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());save(false);throw failure;}
    }
    private JSONObject gate(String op,boolean release)throws Exception{
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://10.0.2.2:18080/transfer-control/"+op+(release?"/release":"/state")).openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(3000);connection.setReadTimeout(5000);if(release){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(0);connection.getOutputStream().close();}
        try{if(connection.getResponseCode()==404)return null;assertEquals(200,connection.getResponseCode());java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.io.InputStream input=connection.getInputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);}return new JSONObject(bytes.toString("UTF-8"));}finally{connection.disconnect();}
    }
    private NetworkSession.FileJob accountJob(String label)throws Exception{
        AtomicReference<NetworkSession.FileJob> found=new AtomicReference<>();
        main(()->{try{java.lang.reflect.Field field=NetworkSession.class.getDeclaredField("jobs");field.setAccessible(true);for(Object value:((Map<?,?>)field.get(session)).values()){NetworkSession.FileJob job=(NetworkSession.FileJob)value;if(job.account.equals(accounts.get(label)))found.set(job);}}catch(Exception e){throw new AssertionError(e);}});assertNotNull(found.get());return found.get();
    }
    private File partial(String label,String op)throws Exception{
        NetworkSession.FileJob job=accountJob(label);File file=new File(NetworkDownload.directory(context),job.id+".part");until("real received prefix: "+op,()->file.isFile()&&file.length()>=4096&&file.length()<262144,15);JSONObject status=gate(op,false);assertNotNull(status);assertTrue(status.getBoolean("prefix"));assertFalse(status.getBoolean("released"));assertEquals(label,status.getString("syntheticAccount"));return file;
    }
    private byte[] documentBytes(Uri uri)throws Exception{try(java.io.InputStream input=context.getContentResolver().openInputStream(uri)){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){out.write(b,0,n);assertTrue(out.size()<=1024*1024);}return out.toByteArray();}}
    private java.util.Set<String> documentIds()throws Exception{
        java.util.Set<String> ids=new java.util.HashSet<>();Uri uri=DocumentsContract.buildChildDocumentsUri(BuildConfig.APPLICATION_ID+".network-docs","root");try(android.database.Cursor cursor=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){assertNotNull(cursor);while(cursor.moveToNext())ids.add(cursor.getString(0));}return ids;
    }
    private Instrumentation.ActivityMonitor interceptSave(Uri uri)throws Exception{
        IntentFilter filter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");return InstrumentationRegistry.getInstrumentation().addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)),true);
    }
    private void choose(String label,String address){main(()->((DownloadHelper)invoke(activity,"downloads",new Class<?>[]{})).choose(viewOnMain(label+"1"),address,"owned-ci","attachment; filename=\"owned.json\"","application/json"));}
    private List<android.view.accessibility.AccessibilityNodeInfo> systemDocumentNodes(){
        android.app.UiAutomation auto=InstrumentationRegistry.getInstrumentation().getUiAutomation();android.accessibilityservice.AccessibilityServiceInfo info=auto.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(info);
        List<android.view.accessibility.AccessibilityNodeInfo> out=new ArrayList<>();List<android.view.accessibility.AccessibilityNodeInfo> roots=new ArrayList<>();android.view.accessibility.AccessibilityNodeInfo active=auto.getRootInActiveWindow();if(active!=null)roots.add(active);for(android.view.accessibility.AccessibilityWindowInfo window:auto.getWindows()){android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);}
        for(android.view.accessibility.AccessibilityNodeInfo root:roots){String pkg=String.valueOf(root.getPackageName());if(!pkg.equals("com.android.documentsui")&&!pkg.equals("com.google.android.documentsui"))continue;List<android.view.accessibility.AccessibilityNodeInfo> queue=new ArrayList<>();queue.add(root);for(int i=0;i<queue.size();i++){android.view.accessibility.AccessibilityNodeInfo node=queue.get(i);if(node.isVisibleToUser())out.add(node);for(int n=0;n<node.getChildCount();n++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(n);if(child!=null)queue.add(child);}}break;}return out;
    }
    private boolean click(android.view.accessibility.AccessibilityNodeInfo node){for(int n=0;n<5&&node!=null;n++,node=node.getParent())if(node.isEnabled()&&node.isClickable()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;return false;}
    private void clickSave()throws Exception{
        long end=SystemClock.elapsedRealtime()+20000;boolean drawer=false,owned=false;
        while(SystemClock.elapsedRealtime()<end){List<android.view.accessibility.AccessibilityNodeInfo> nodes=systemDocumentNodes();
            if(!drawer){for(android.view.accessibility.AccessibilityNodeInfo node:nodes){String description=String.valueOf(node.getContentDescription());if(description.equalsIgnoreCase("Show roots")||description.equalsIgnoreCase("Open navigation drawer")||description.contains("显示根目录")){if(click(node)){drawer=true;break;}}}}
            for(android.view.accessibility.AccessibilityNodeInfo node:nodes)if("Owned CI documents".contentEquals(node.getText()==null?"":node.getText())){if(drawer){if(click(node)){owned=true;break;}}else owned=true;}
            if(owned)break;Thread.sleep(100);
        }
        assertTrue("Select the owned synthetic provider in actual DocumentsUI",owned);Thread.sleep(150);
        while(SystemClock.elapsedRealtime()<end){for(android.view.accessibility.AccessibilityNodeInfo node:systemDocumentNodes()){String id=node.getViewIdResourceName(),text=String.valueOf(node.getText());if((id!=null&&id.endsWith(":id/action_menu_save"))||text.equalsIgnoreCase("SAVE")||text.equals("保存"))if(click(node))return;}Thread.sleep(100);}
        throw new AssertionError("Actual system save action unavailable; no result stub used");
    }
    private void echo(String name,String route)throws Exception{
        select("B1");WebView web=view("B1");js(web,"window.__system=null;fetch("+JSONObject.quote(path(name,"echo"))+",{cache:'no-store'}).then(r=>r.json()).then(v=>window.__system=v);true");long end=SystemClock.elapsedRealtime()+20000;JSONObject body=null;do{String value=js(web,"window.__system");if(!"null".equals(value)){body=new JSONObject(value);break;}Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);assertNotNull(body);assertEquals(route,body.getString("route"));assertEquals("B",body.getString("account"));request(path(name,"echo"),route,"B");
    }
    void fileSafety()throws Exception{
        assertTrue(BuildConfig.NETWORK_TEST);assertTrue(BuildConfig.APPLICATION_ID.endsWith(".networkci"));origin=args.getString("origin");String transport=args.getString("transport");prefix="formal-files-"+transport+"-";reportFile="formal-network-files.json";
        report.put("schema",1).put("sourceSHA",args.getString("sourceSHA")).put("api",android.os.Build.VERSION.SDK_INT).put("transport",transport).put("screenshots",false).put("realChatGPT",false);seed();NetworkSystemProxy policy=null;
        String aOp=transport+"-https-formal-clear-a",bOp=transport+"-https-formal-live-b",cancelOp=transport+"-https-formal-cancel-doc",faultOp=transport+"-https-formal-publish-fault",closeOp=transport+"-https-formal-owner-close";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(current->{activity=current;memory=BrowserMemory.get(context);session=memory.network();});until("file safety boot route",()->session.ready(),35);page("A1",false);page("B1",false);page("C1",false);
            asset("A","A1","protected-A","A");AssetLedger aLedger=new AssetLedger(context.getFilesDir(),accounts.get("A"));JSONObject protectedA=aLedger.list().get(0);byte[] aBytes=Files.readAllBytes(aLedger.verifiedFile(protectedA.getString("id")).toPath());
            select("A1");main(()->memory.assetDownloads.start(viewOnMain("A1"),accounts.get("A"),"A",origin+"/transfer/"+aOp+"/download","owned-ci","attachment; filename=\"clear.bin\"","application/octet-stream"));File aPart=partial("A",aOp);NetworkSession.FileJob aJob=accountJob("A");
            select("B1");main(()->memory.assetDownloads.start(viewOnMain("B1"),accounts.get("B"),"B",origin+"/transfer/"+bOp+"/download","owned-ci","attachment; filename=\"live.bin\"","application/octet-stream"));File bPart=partial("B",bOp);assertEquals(2,session.routes.activeTransfers());
            select("C1");String oldStorage=WorkspaceFiles.read(context.getFilesDir()).find(accounts.get("A")).storageId;long clearAt=SystemClock.elapsedRealtime();main(()->invoke(activity,"finishAccountChange",new Class<?>[]{String.class,boolean.class},accounts.get("A"),false));until("A download terminated while B remains",()->aJob.finished.get()&&session.routes.activeTransfers()==1,10);assertFalse(aPart.exists());assertTrue(bPart.exists());assertFalse(aJob.valid());assertArrayEquals(aBytes,Files.readAllBytes(aLedger.verifiedFile(protectedA.getString("id")).toPath()));
            assertNotEquals(oldStorage,WorkspaceFiles.read(context.getFilesDir()).find(accounts.get("A")).storageId);preserved("B1");preserved("C1");
            report.put("accountClear",new JSONObject().put("elapsedMillis",SystemClock.elapsedRealtime()-clearAt).put("oldStorage",oldStorage).put("assetBytesRetained",true).put("otherAccountStillTransferring",true).put("oldCallbackInvalid",true));
            java.util.Set<String> beforeDocs=documentIds();Instrumentation.ActivityMonitor never=InstrumentationRegistry.getInstrumentation().addMonitor(new IntentFilter(Intent.ACTION_CREATE_DOCUMENT),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
            try{choose("C",origin+"/transfer/"+cancelOp+"/download");File cPart=partial("C",cancelOp);long at=SystemClock.elapsedRealtime();main(()->memory.downloads.cancelSelection());until("cancel document download released actual lease",()->session.routes.activeTransfers()==1,10);assertFalse(cPart.exists());assertEquals(0,never.getHits());assertEquals(beforeDocs,documentIds());report.put("cancelledDocumentMillis",SystemClock.elapsedRealtime()-at);}finally{InstrumentationRegistry.getInstrumentation().removeMonitor(never);}
            AssetDownloads owner=new AssetDownloads(context);select("C1");main(()->owner.start(viewOnMain("C1"),accounts.get("C"),"C",origin+"/transfer/"+closeOp+"/download","owned-ci","attachment; filename=\"closed.bin\"","application/octet-stream"));File ownerPart=partial("C",closeOp);long closeAt=SystemClock.elapsedRealtime();main(owner::close);until("closed owner terminated its transfer only",()->session.routes.activeTransfers()==1,10);assertFalse(ownerPart.exists());assertTrue(bPart.exists());String rejected=transport+"-https-formal-closed-reject";main(()->owner.start(viewOnMain("C1"),accounts.get("C"),"C",origin+"/transfer/"+rejected+"/download","owned-ci",null,"application/octet-stream"));assertNull(gate(rejected,false));report.put("closedOwner",new JSONObject().put("elapsedMillis",SystemClock.elapsedRealtime()-closeAt).put("partialRemoved",true).put("otherTransferPreserved",true).put("newRequestRejected",true));gate(closeOp,true);
            gate(aOp,true);gate(cancelOp,true);gate(bOp,true);until("B completed normally",()->!session.busy(),20);AssetLedger bLedger=new AssetLedger(context.getFilesDir(),accounts.get("B"));JSONObject bFile=bLedger.list().get(0);assertEquals(262144,bLedger.verifiedFile(bFile.getString("id")).length());byte[] completeB=new byte[262144];java.util.Arrays.fill(completeB,(byte)'D');assertArrayEquals(completeB,Files.readAllBytes(bLedger.verifiedFile(bFile.getString("id")).toPath()));
            Uri parent=DocumentsContract.buildDocumentUri(BuildConfig.APPLICATION_ID+".network-docs","root");
            // Real DocumentsUI creates and returns a destination, no ActivityMonitor.
            java.util.Set<String> beforeReal=documentIds();choose("C",origin+path("actual-system-save","download"));clickSave();until("real system document saved",()->!session.busy(),30);java.util.Set<String> created=documentIds();created.removeAll(beforeReal);assertEquals(1,created.size());Uri actual=DocumentsContract.buildDocumentUri(BuildConfig.APPLICATION_ID+".network-docs",created.iterator().next());JSONObject actualBody=new JSONObject(new String(documentBytes(actual),StandardCharsets.UTF_8));assertEquals("C",actualBody.getString("account"));assertEquals("A",actualBody.getString("route"));request(path("actual-system-save","download"),"A","C");report.put("realSystemPicker",true);
            byte[] sentinel=documentBytes(actual);Instrumentation.ActivityMonitor old=interceptSave(actual);try{choose("C",origin+path("refuse-existing-document","download"));until("existing destination refused",()->!session.busy(),30);assertEquals(1,old.getHits());assertArrayEquals(sentinel,documentBytes(actual));request(path("refuse-existing-document","download"),"A","C");}finally{InstrumentationRegistry.getInstrumentation().removeMonitor(old);}
            java.util.Set<String> beforeDenied=documentIds();Uri denied=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/json","owned-denied.json");assertNotNull(denied);context.getContentResolver().call(Uri.parse("content://"+BuildConfig.APPLICATION_ID+".network-docs"),"fixture-fault","open-fail",null);
            Instrumentation.ActivityMonitor deny=interceptSave(denied);try{choose("C",origin+path("write-permission-denied","download"));until("write permission denied terminal",()->!session.busy(),30);assertEquals(1,deny.getHits());assertEquals(beforeDenied,documentIds());assertArrayEquals(sentinel,documentBytes(actual));request(path("write-permission-denied","download"),"A","C");report.put("writePermissionDenied",true);}finally{InstrumentationRegistry.getInstrumentation().removeMonitor(deny);}
            // A provider really accepts 4096 bytes, then closes its pipe with an error.
            java.util.Set<String> beforeFault=documentIds();Uri broken=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/octet-stream","owned-fault.bin");assertNotNull(broken);context.getContentResolver().call(Uri.parse("content://"+BuildConfig.APPLICATION_ID+".network-docs"),"fixture-fault","mid-write",null);
            Instrumentation.ActivityMonitor failure=interceptSave(broken);try{choose("C",origin+"/transfer/"+faultOp+"/download");partial("C",faultOp);gate(faultOp,true);until("publisher failure terminal",()->!session.busy(),30);assertEquals(1,failure.getHits());assertEquals(beforeFault,documentIds());Bundle stats=context.getContentResolver().call(Uri.parse("content://"+BuildConfig.APPLICATION_ID+".network-docs"),"fixture-stats",null,null);assertEquals(4096,stats.getLong("bytes"));assertEquals(2,stats.getInt("faults"));assertArrayEquals(sentinel,documentBytes(actual));report.put("publisherFailure",new JSONObject().put("actualPrefixBytes",stats.getLong("bytes")).put("newPartialRemoved",true).put("existingDocumentPreserved",true));}finally{InstrumentationRegistry.getInstrumentation().removeMonitor(failure);}
            policy=new NetworkSystemProxy(context);
            for(String mode:new String[]{"http","pac"}){policy.configure(mode);CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();main(()->session.useSystem((success,message)->{ok.set(success);done.countDown();}));assertTrue(done.await(35,TimeUnit.SECONDS));assertTrue(ok.get());String systemRoute=mode.equals("http")?"S":"P";
                echo(mode+"-system-web",systemRoute);asset("B","B1",mode+"-system-asset",systemRoute);export("C","C1",mode+"-system-doc",systemRoute);
                route(0);echo(mode+"-app-A-web","A");asset("B","B1",mode+"-app-A-asset","A");export("C","C1",mode+"-app-A-doc","A");
                route(1);echo(mode+"-app-B-web","B");asset("B","B1",mode+"-app-B-asset","B");export("C","C1",mode+"-app-B-doc","B");
                CountDownLatch clear=new CountDownLatch(1);main(()->session.useSystem((success,message)->{assertTrue(success);clear.countDown();}));assertTrue(clear.await(35,TimeUnit.SECONDS));echo(mode+"-restored-web",systemRoute);asset("B","B1",mode+"-restored-asset",systemRoute);export("C","C1",mode+"-restored-doc",systemRoute);preserved("B1");preserved("C1");
            }
            route(0);
            IntentFilter pendingFilter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);pendingFilter.addCategory(Intent.CATEGORY_OPENABLE);pendingFilter.addDataType("*/*");Instrumentation.ActivityMonitor delayed=InstrumentationRegistry.getInstrumentation().addMonitor(pendingFilter,null,true);
            try{choose("C",origin+path("delayed-save-result","download"));until("new document selection pending",()->delayed.getHits()==1,30);assertTrue(session.busy());long sameGeneration=session.routes.snapshot().generation;AtomicReference<Boolean> changed=new AtomicReference<>();main(()->session.select(1,(ok,message)->changed.set(ok)));assertEquals(Boolean.FALSE,changed.get());assertEquals(sameGeneration,session.routes.snapshot().generation);main(()->session.invalidateAccount(accounts.get("C")));until("old selection invalidated",()->!session.busy(),10);main(()->memory.downloads.onResult(Activity.RESULT_OK,new Intent().setData(actual).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)));assertArrayEquals(sentinel,documentBytes(actual));request(path("delayed-save-result","download"),"A","C");report.put("lateSelectionIgnored",true).put("routeChangeBlockedWhileSelecting",true);}finally{InstrumentationRegistry.getInstrumentation().removeMonitor(delayed);}
            HandoffRuntime runtime=HandoffRuntime.get(context);java.lang.reflect.Field controls=HandoffRuntime.class.getDeclaredField("controls");controls.setAccessible(true);String fakeControl=WorkspaceState.id();@SuppressWarnings("unchecked") Map<String,java.util.concurrent.atomic.AtomicBoolean> active=(Map<String,java.util.concurrent.atomic.AtomicBoolean>)controls.get(runtime);synchronized(runtime){active.put(fakeControl,new java.util.concurrent.atomic.AtomicBoolean(true));}
            try{AtomicReference<Boolean> accepted=new AtomicReference<>();main(()->session.select(1,(ok,message)->accepted.set(ok)));assertEquals(Boolean.FALSE,accepted.get());report.put("syntheticAutomationGuard",true);}finally{synchronized(runtime){active.remove(fakeControl);}}
            preserved("B1");preserved("C1");report.put("gateFinal",new JSONArray().put(gate(aOp,false)).put(gate(bOp,false)).put(gate(cancelOp,false)).put(gate(faultOp,false)).put(gate(closeOp,false)));report.put("accountStorage",new JSONObject().put("B",WorkspaceFiles.read(context.getFilesDir()).find(accounts.get("B")).storageId).put("C",WorkspaceFiles.read(context.getFilesDir()).find(accounts.get("C")).storageId));report.put("accounts",new JSONObject(accounts));save(true);
        }catch(Exception|AssertionError failure){report.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());save(false);throw failure;}
        finally{for(String op:new String[]{aOp,bOp,cancelOp,faultOp,closeOp})try{if(gate(op,false)!=null)gate(op,true);}catch(Exception ignored){}try{if(policy!=null)policy.close();}finally{if(policy!=null)report.put("systemPolicy",policy.evidence);save(report.optBoolean("complete",false)&&!report.has("failure")&&policy!=null&&policy.evidence.optBoolean("restored")&&policy.evidence.optBoolean("roleRemoved"));}}
    }

}
