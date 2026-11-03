package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Compiled unchanged against old/new clients. All page bytes are offline fixtures, never ChatGPT. */
@RunWith(AndroidJUnit4.class)
public final class NetworkBenchmarkTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private final JSONArray phases=new JSONArray();
    private MainActivity activity;
    private final List<WebView> pages=new ArrayList<>();
    private final List<String> identities=new ArrayList<>();
    private int seconds;
    private final JSONArray heartbeats=new JSONArray();
    private Object network;
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private void write(String name,JSONObject value)throws Exception{File target=new File(context.getFilesDir(),name),tmp=new File(target+".tmp");Files.write(tmp.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));assertTrue(tmp.renameTo(target));}
    private void ack(String token)throws Exception{long end=SystemClock.elapsedRealtime()+(seconds+120L)*1000;File file=new File(context.getFilesDir(),"resource-ack");while(SystemClock.elapsedRealtime()<end){if(file.isFile()&&token.equals(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)))return;Thread.sleep(250);}throw new AssertionError("Missing full host window ack: "+token);}
    private String js(WebView web,String code)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> out=new AtomicReference<>();main(()->web.evaluateJavascript(code,value->{out.set(value);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return out.get();}
    private Object invoke(String method,Class<?>[] types,Object... values){try{Method call=MainActivity.class.getDeclaredMethod(method,types);call.setAccessible(true);return call.invoke(activity,values);}catch(Exception failure){throw new AssertionError(method,failure);}}
    private void configure(String mode)throws Exception{
        if(mode.equals("baseline")){try{Class.forName("com.forcemind.chatgpt.profiles.NetworkSession");fail("Baseline must be actual pre-integration code");}catch(ClassNotFoundException expected){}return;}
        if(mode.equals("system"))return;
        Class<?> node=Class.forName("com.forcemind.chatgpt.profiles.NetworkNode"),dataType=Class.forName("com.forcemind.chatgpt.profiles.NetworkSettingsStore$Data"),storeType=Class.forName("com.forcemind.chatgpt.profiles.NetworkSettingsStore");Object data=dataType.getConstructor().newInstance();@SuppressWarnings("unchecked") List<Object> nodes=(List<Object>)dataType.getField("nodes").get(data);nodes.add(node.getMethod("parse",String.class,String.class).invoke(null,args.getString("nodeA"),"A"));dataType.getField("enabled").setBoolean(data,true);dataType.getField("selected").setInt(data,0);storeType.getMethod("write",dataType).invoke(storeType.getConstructor(Context.class).newInstance(context),data);
    }
    private void stopToSystem()throws Exception{
        Class<?> listenerType=Class.forName("com.forcemind.chatgpt.profiles.NetworkRoute$Listener");CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();Object callback=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},(proxy,method,values)->{if(method.getName().equals("complete")){ok.set((Boolean)values[0]);done.countDown();}return null;});Method use=network.getClass().getDeclaredMethod("useSystem",listenerType);use.setAccessible(true);main(()->{try{use.invoke(network,callback);}catch(Exception failure){throw new AssertionError(failure);}});assertTrue(done.await(35,TimeUnit.SECONDS));assertTrue(ok.get());
    }
    private void phase(String name,boolean stream,boolean background)throws Exception{
        Thread.sleep(5000);write("resource-phase.json",new JSONObject().put("phase",name).put("state","ready").put("pid",android.os.Process.myPid()).put("transport","raw").put("windowSeconds",seconds).put("mode",args.getString("mode")));ack(name+":start");long started=SystemClock.elapsedRealtime();
        if(stream)js(pages.get(pages.size()-1),"window.__chunks=0;window.__streamDone=false;window.__stream=setInterval(()=>{let n=++window.__chunks;document.getElementById('answer').append(document.createTextNode('Synthetic chunk '+n+' '));if(n===600){clearInterval(window.__stream);window.__streamDone=true;}},40);true");
        if(background){for(WebView page:pages)js(page,"window.__backgroundTicks=0;setInterval(()=>++window.__backgroundTicks,1000);true");assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));}
        if(background){
            long deadline=SystemClock.elapsedRealtime()+(seconds+120L)*1000,next=SystemClock.elapsedRealtime();File control=new File(context.getFilesDir(),"resource-ack");
            while(SystemClock.elapsedRealtime()<deadline){
                if(control.isFile()&&(name+":done").equals(new String(Files.readAllBytes(control.toPath()),StandardCharsets.UTF_8)))break;
                if(SystemClock.elapsedRealtime()>=next){JSONArray ticks=new JSONArray();for(WebView page:pages)ticks.put(js(page,"window.__backgroundTicks"));heartbeats.put(new JSONObject().put("elapsedMillis",SystemClock.elapsedRealtime()-started).put("ticks",ticks));write("resource-background-heartbeats.json",new JSONObject().put("samples",heartbeats));next=SystemClock.elapsedRealtime()+30000;}
                Thread.sleep(500);
            }
        }
        ack(name+":done");assertTrue("full window",SystemClock.elapsedRealtime()-started>=(seconds-2L)*1000);
        if(background){try(android.os.ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("am start -n "+context.getPackageName()+"/com.forcemind.chatgpt.profiles.MainActivity");java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[512];while(input.read(buffer)!=-1){}}Thread.sleep(500);}
        for(int i=0;i<pages.size();i++){assertEquals(identities.get(i),js(pages.get(i),"window.__identity"));assertEquals("\"Synthetic unchanged draft\"",js(pages.get(i),"document.getElementById('prompt-textarea').value"));}
        if(stream){assertEquals("600",js(pages.get(pages.size()-1),"window.__chunks"));assertEquals("true",js(pages.get(pages.size()-1),"window.__streamDone"));}
        phases.put(new JSONObject().put("phase",name).put("durationMillis",SystemClock.elapsedRealtime()-started).put("chunks",stream?600:0).put("liveDocuments",4).put("background",background).put("noReload",true));write("resource-result.json",new JSONObject().put("complete",false).put("phases",phases));
    }
    @Test public void syntheticThreeProfileResourceMatrix()throws Exception{
        String mode=args.getString("mode");assertTrue(java.util.Arrays.asList("baseline","system","enabled","warm-system","background").contains(mode));assertEquals(mode.equals("baseline")?"com.forcemind.chatgpt.profiles.debug":"com.forcemind.chatgpt.profiles.networkci",context.getPackageName());assertEquals("1",args.getString("resourceSampler"));seconds=Integer.parseInt(args.getString("resourceWindowSeconds"));assertEquals(mode.equals("background")?1800:60,seconds);configure(mode);
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account first=state.current();state.addTab(first);state.addAccount("Synthetic B","",WorkspaceState.COLORS[1]);state.addAccount("Synthetic C","",WorkspaceState.COLORS[2]);
        for(WorkspaceState.Account a:state.accounts)for(WorkspaceState.Tab t:a.tabs)t.url="https://chatgpt.com/c/synthetic-cpu-"+t.id;
        new StateStore(context.getFilesDir()).save(state);
        String html="<!doctype html><meta name='viewport' content='width=device-width'><title>Owned CPU fixture, not ChatGPT</title><textarea id='prompt-textarea'>Synthetic unchanged draft</textarea><div data-message-author-role='assistant' id='answer'>Synthetic answer</div><iframe src='about:blank' style='width:80px;height:32px'></iframe><script>window.__identity='owned-'+Date.now()+'-'+Math.random();</script>";
        // The visible iframe also makes the old client's existing pictureSafe return skip.
        MainActivity.handoffFixture=(account,request)->new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        long launched=SystemClock.elapsedRealtime();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(current->activity=current);
            for(WorkspaceState.Account account:state.accounts)for(WorkspaceState.Tab tab:account.tabs){main(()->{assertTrue((Boolean)invoke("change",new Class<?>[]{Consumer.class},(Consumer<WorkspaceState>)(s->{s.activeAccount=account.id;s.find(account.id).activeTab=tab.id;})));invoke("showCurrent",new Class<?>[]{});});long end=SystemClock.elapsedRealtime()+35000;AtomicReference<WebView> web=new AtomicReference<>();String mark=null;do{main(()->web.set(activity.recoveryCurrentWeb()));if(web.get()!=null){mark=js(web.get(),"window.__identity");if(mark!=null&&mark.startsWith("\"owned-"))break;}Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);assertNotNull(mark);assertTrue(mark.startsWith("\"owned-"));pages.add(web.get());identities.add(mark);}
            assertEquals(4,pages.size());
            long readyMillis=SystemClock.elapsedRealtime()-launched;
            boolean goLoaded=new String(Files.readAllBytes(new File("/proc/self/maps").toPath()),StandardCharsets.UTF_8).contains("libgojni");assertEquals(!mode.equals("baseline")&&!mode.equals("system"),goLoaded);
            if(!mode.equals("baseline")){Method get=BrowserMemory.class.getDeclaredMethod("network");get.setAccessible(true);network=get.invoke(BrowserMemory.get(context));}
            if(mode.equals("warm-system"))stopToSystem();
            if(mode.equals("background")){main(()->KeepaliveService.enable(context));long end=SystemClock.elapsedRealtime()+10000;while(!KeepaliveService.running()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue(KeepaliveService.running());phase("background",false,true);}
            else{phase("idle",false,false);phase("stream",true,false);}
            write("resource-result.json",new JSONObject().put("complete",true).put("sourceSHA",args.getString("sourceSHA")).put("mode",mode).put("transport","raw").put("goLoaded",goLoaded).put("startupMillis",readyMillis).put("phases",phases).put("backgroundHeartbeats",heartbeats).put("screenshots",false).put("realChatGPTRequests",0).put("workload","three profiles, four offline documents, one 600-chunk DOM stream; no network stream measurement"));
        }finally{main(()->KeepaliveService.disable(context));MainActivity.handoffFixture=null;}
    }
}
