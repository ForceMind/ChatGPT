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
    private final JSONArray files=new JSONArray(),requests=new JSONArray();
    private MainActivity activity;
    private BrowserMemory memory;
    private NetworkSession session;
    private String origin,prefix;
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
    private void asset(String account,String tab,String name,String expectedRoute)throws Exception{
        AssetLedger ledger=new AssetLedger(context.getFilesDir(),accounts.get(account));int before=ledger.list().size();select(tab);
        main(()->{if(memory.assetDownloads==null)memory.assetDownloads=new AssetDownloads(context);memory.assetDownloads.start(viewOnMain(tab),accounts.get(account),"Synthetic "+account,origin+path(name,"download"),"CI-owned","attachment; filename=\""+name+".json\"","application/json");});
        select("C1");until("file task terminal",()->!session.busy(),30);List<JSONObject> after=ledger.list();assertEquals(before+1,after.size());JSONObject entry=after.get(0);assertEquals("ready",entry.getString("status"));
        JSONObject body=new JSONObject(new String(Files.readAllBytes(ledger.verifiedFile(entry.getString("id")).toPath()),StandardCharsets.UTF_8));assertEquals(expectedRoute,body.getString("route"));assertEquals(account,body.getString("account"));
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
        select("A1");WebView parent=view("A1");JSONObject rect=new JSONObject(js(parent,"(()=>{let r=document.getElementById('popup').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,dpr:devicePixelRatio}})()"));
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
    private void save(boolean complete)throws Exception{report.put("complete",complete).put("files",files).put("expectedRequests",requests);Files.write(new File(context.getFilesDir(),"formal-network-result.json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));}
    @Test public void formalPagesAndAssetsRetainTheirAccountAndRoute()throws Exception{
        assertTrue(BuildConfig.NETWORK_TEST);assertTrue(BuildConfig.APPLICATION_ID.endsWith(".networkci"));assertTrue(NetworkTestPolicy.owned(NavigationPolicy.HOME));
        origin=args.getString("origin");String transport=args.getString("transport");prefix="formal-"+transport+"-";
        report.put("schema",1).put("sourceSHA",args.getString("sourceSHA")).put("api",android.os.Build.VERSION.SDK_INT).put("transport",transport).put("screenshots",false).put("realChatGPT",false);
        AtomicReference<NetworkSession> off=new AtomicReference<>();main(()->off.set(BrowserMemory.get(context).network()));until("default system readiness",()->off.get().ready(),15);
        assertEquals(NetworkRoute.State.SYSTEM,off.get().routes.snapshot().state);assertFalse(off.get().routes.coreInitialized());assertFalse(new String(Files.readAllBytes(new File("/proc/self/maps").toPath()),StandardCharsets.UTF_8).contains("libgojni"));
        report.put("disabledCoreUnloaded",true);main(()->BrowserMemory.get(context).clear());seed();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(current->{activity=current;memory=BrowserMemory.get(context);session=memory.network();});until("restored selected route before pages",()->session.ready(),35);assertEquals("A",session.routes.snapshot().node.name);
            page("A1",false);page("A2",true);page("B1",false);page("C1",false);popup();
            assertEquals(3,new java.util.HashSet<>(profileNames.values()).size());
            for(String account:accounts.keySet()){asset(account,account.equals("A")?"A1":account+"1","asset-A-"+account,"A");export(account,account.equals("A")?"A1":account+"1","doc-A-"+account,"A");}
            MainActivity previous=activity;NetworkSession same=session;scenario.recreate();scenario.onActivity(current->{assertNotSame(previous,current);activity=current;assertSame(same,BrowserMemory.get(context).network());});
            for(String tab:tabs.keySet())preserved(tab);
            route(1);for(String tab:tabs.keySet())preserved(tab);
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
}
