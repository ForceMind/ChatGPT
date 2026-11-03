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
    private ActivityScenario<MainActivity> scenario;
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
    static boolean goMapped(Context context)throws Exception{
        String maps=new String(Files.readAllBytes(new File("/proc/self/maps").toPath()),StandardCharsets.UTF_8);
        if(maps.contains("libgojni.so"))return true;
        File apk=new File(context.getApplicationInfo().sourceDir);
        try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(apk,"r")){
            int count=(int)Math.min(input.length(),65557);byte[] tail=new byte[count];input.seek(input.length()-count);input.readFully(tail);java.nio.ByteBuffer end=java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN);int eocd=-1;
            for(int i=count-22;i>=0;i--)if(end.getInt(i)==0x06054b50){eocd=i;break;}assertTrue("APK ZIP directory",eocd>=0);
            long central=Integer.toUnsignedLong(end.getInt(eocd+16));int entries=Short.toUnsignedInt(end.getShort(eocd+10));input.seek(central);
            for(int i=0;i<entries;i++){
                byte[] header=new byte[46];input.readFully(header);java.nio.ByteBuffer h=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(0x02014b50,h.getInt(0));int nameLen=Short.toUnsignedInt(h.getShort(28)),extra=Short.toUnsignedInt(h.getShort(30)),comment=Short.toUnsignedInt(h.getShort(32));byte[] name=new byte[nameLen];input.readFully(name);String member=new String(name,StandardCharsets.UTF_8);long next=input.getFilePointer()+extra+comment;
                if(member.startsWith("lib/")&&member.endsWith("/libgojni.so")&&h.getShort(10)==0){
                    long local=Integer.toUnsignedLong(h.getInt(42)),size=Integer.toUnsignedLong(h.getInt(24));input.seek(local);byte[] localHeader=new byte[30];input.readFully(localHeader);java.nio.ByteBuffer l=java.nio.ByteBuffer.wrap(localHeader).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(0x04034b50,l.getInt(0));long start=local+30+Short.toUnsignedInt(l.getShort(26))+Short.toUnsignedInt(l.getShort(28));
                    for(String row:maps.split("\\n")){String[] fields=row.trim().split("\\s+",6);if(fields.length==6&&fields[1].contains("x")&&fields[5].startsWith(apk.getPath())){long offset=Long.parseLong(fields[2],16);if(offset>=start&&offset<start+size)return true;}}
                }
                input.seek(next);
            }
        }
        return false;
    }
    private void configure(String mode)throws Exception{
        if(mode.equals("baseline")){try{Class.forName("com.forcemind.chatgpt.profiles.NetworkSession");fail("Baseline must be actual pre-integration code");}catch(ClassNotFoundException expected){}return;}
        if(mode.equals("system"))return;
        Class<?> node=Class.forName("com.forcemind.chatgpt.profiles.NetworkNode"),dataType=Class.forName("com.forcemind.chatgpt.profiles.NetworkSettingsStore$Data"),storeType=Class.forName("com.forcemind.chatgpt.profiles.NetworkSettingsStore");Object data=dataType.getConstructor().newInstance();@SuppressWarnings("unchecked") List<Object> nodes=(List<Object>)dataType.getField("nodes").get(data);nodes.add(node.getMethod("parse",String.class,String.class).invoke(null,args.getString("nodeA"),"A"));dataType.getField("enabled").setBoolean(data,true);dataType.getField("selected").setInt(data,0);storeType.getMethod("write",dataType).invoke(storeType.getConstructor(Context.class).newInstance(context),data);
    }
    private void stopToSystem()throws Exception{
        Class<?> listenerType=Class.forName("com.forcemind.chatgpt.profiles.NetworkRoute$Listener");CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> ok=new AtomicReference<>();Object callback=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},(proxy,method,values)->{if(method.getName().equals("complete")){ok.set((Boolean)values[0]);done.countDown();}return null;});Method use=network.getClass().getDeclaredMethod("useSystem",listenerType);use.setAccessible(true);main(()->{try{use.invoke(network,callback);}catch(Exception failure){throw new AssertionError(failure);}});assertTrue(done.await(35,TimeUnit.SECONDS));assertTrue(ok.get());
    }
    private void phase(String name,boolean stream,boolean background)throws Exception{
        Thread.sleep(5000);long backgroundAt=0;
        if(background){for(WebView page:pages)js(page,"window.__backgroundTicks=0;setInterval(()=>++window.__backgroundTicks,1000);true");assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));AtomicReference<String> stopped=new AtomicReference<>();long limit=SystemClock.elapsedRealtime()+10000;do{main(()->stopped.set(activity.getLifecycle().getCurrentState().name()));if("CREATED".equals(stopped.get()))break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<limit);assertEquals("CREATED",stopped.get());assertTrue(KeepaliveService.running());backgroundAt=SystemClock.elapsedRealtime();}
        write("resource-phase.json",new JSONObject().put("phase",name).put("state","ready").put("pid",android.os.Process.myPid()).put("transport","raw").put("windowSeconds",seconds).put("mode",args.getString("mode")).put("background",background));ack(name+":start");long started=SystemClock.elapsedRealtime();
        if(stream)js(pages.get(pages.size()-1),"window.__chunks=0;window.__streamDone=false;window.__stream=setInterval(()=>{let n=++window.__chunks;document.getElementById('answer').append(document.createTextNode('Synthetic chunk '+n+' '));if(n===600){clearInterval(window.__stream);window.__streamDone=true;}},40);true");
        if(background){
            long deadline=SystemClock.elapsedRealtime()+(seconds+120L)*1000,next=SystemClock.elapsedRealtime();File control=new File(context.getFilesDir(),"resource-ack");
            while(SystemClock.elapsedRealtime()<deadline){
                if(control.isFile()&&(name+":done").equals(new String(Files.readAllBytes(control.toPath()),StandardCharsets.UTF_8)))break;
                if(SystemClock.elapsedRealtime()>=next){JSONArray ticks=new JSONArray();for(WebView page:pages)ticks.put(js(page,"window.__backgroundTicks"));AtomicReference<String> state=new AtomicReference<>();main(()->state.set(activity.getLifecycle().getCurrentState().name()));assertEquals("CREATED",state.get());assertTrue(KeepaliveService.running());heartbeats.put(new JSONObject().put("elapsedMillis",SystemClock.elapsedRealtime()-started).put("ticks",ticks).put("lifecycle",state.get()).put("backgroundServiceRunning",KeepaliveService.running()));write("resource-background-heartbeats.json",new JSONObject().put("samples",heartbeats));next=SystemClock.elapsedRealtime()+30000;}
                Thread.sleep(500);
            }
        }
        ack(name+":done");assertTrue("full window",SystemClock.elapsedRealtime()-started>=(seconds-2L)*1000);
        long held=background?SystemClock.elapsedRealtime()-backgroundAt:0;if(background)assertTrue("actual stopped-state hold must be full 1800 seconds",held>=seconds*1000L);
        if(background){
            main(()->((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(activity.getTaskId(),0));
            AtomicReference<Boolean> focused=new AtomicReference<>(false);long limit=SystemClock.elapsedRealtime()+10000;
            do{main(()->focused.set(activity.getLifecycle().getCurrentState()==androidx.lifecycle.Lifecycle.State.RESUMED&&activity.hasWindowFocus()));if(focused.get())break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<limit);assertTrue("same managed Activity actually resumed in foreground",focused.get());
        }
        for(int i=0;i<pages.size();i++){assertEquals(identities.get(i),js(pages.get(i),"window.__identity"));assertEquals("\"Synthetic unchanged draft\"",js(pages.get(i),"document.getElementById('prompt-textarea').value"));}
        if(stream){assertEquals("600",js(pages.get(pages.size()-1),"window.__chunks"));assertEquals("true",js(pages.get(pages.size()-1),"window.__streamDone"));}
        phases.put(new JSONObject().put("phase",name).put("durationMillis",SystemClock.elapsedRealtime()-started).put("chunks",stream?600:0).put("liveDocuments",4).put("background",background).put("actualBackgroundMillis",held).put("noReload",true));write("resource-result.json",new JSONObject().put("complete",false).put("phases",phases));
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
        scenario=ActivityScenario.launch(MainActivity.class);scenario.onActivity(current->activity=current);Throwable primary=null;
        try{
            for(WorkspaceState.Account account:state.accounts)for(WorkspaceState.Tab tab:account.tabs){main(()->{assertTrue((Boolean)invoke("change",new Class<?>[]{Consumer.class},(Consumer<WorkspaceState>)(s->{s.activeAccount=account.id;s.find(account.id).activeTab=tab.id;})));invoke("showCurrent",new Class<?>[]{});});long end=SystemClock.elapsedRealtime()+35000;AtomicReference<WebView> web=new AtomicReference<>();String mark=null;do{main(()->web.set(activity.recoveryCurrentWeb()));if(web.get()!=null){mark=js(web.get(),"window.__identity");if(mark!=null&&mark.startsWith("\"owned-"))break;}Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);assertNotNull(mark);assertTrue(mark.startsWith("\"owned-"));pages.add(web.get());identities.add(mark);}
            assertEquals(4,pages.size());
            long readyMillis=SystemClock.elapsedRealtime()-launched;
            boolean goLoaded=goMapped(context);JSONObject loadProof=new JSONObject().put("mode",mode).put("mapped",goLoaded).put("apk",context.getApplicationInfo().sourceDir);Files.write(new File(context.getFilesDir(),"resource-native-maps.txt").toPath(),Files.readAllBytes(new File("/proc/self/maps").toPath()));
            if(!mode.equals("baseline")){Method get=BrowserMemory.class.getDeclaredMethod("network");get.setAccessible(true);network=get.invoke(BrowserMemory.get(context));java.lang.reflect.Field field=network.getClass().getDeclaredField("routes");field.setAccessible(true);Object routes=field.get(network);boolean initialized=(Boolean)routes.getClass().getMethod("coreInitialized").invoke(routes);Object snapshot=routes.getClass().getMethod("snapshot").invoke(routes);loadProof.put("coreInitialized",initialized).put("routeState",String.valueOf(snapshot.getClass().getField("state").get(snapshot)));}
            write("resource-load-evidence.json",loadProof);if(!mode.equals("baseline"))assertEquals(!mode.equals("system"),loadProof.getBoolean("coreInitialized"));assertEquals("actual executable JNI mapping, including APK-direct load",!mode.equals("baseline")&&!mode.equals("system"),goLoaded);
            if(mode.equals("warm-system"))stopToSystem();
            if(mode.equals("background")){main(()->KeepaliveService.enable(context));long end=SystemClock.elapsedRealtime()+10000;while(!KeepaliveService.running()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);assertTrue(KeepaliveService.running());
                assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));AtomicReference<Boolean> back=new AtomicReference<>(false);long until=SystemClock.elapsedRealtime()+10000;do{main(()->back.set(activity.getLifecycle().getCurrentState()==androidx.lifecycle.Lifecycle.State.CREATED));if(back.get())break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<until);assertTrue("preflight real Home stopped",back.get());
                main(()->((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(activity.getTaskId(),0));until=SystemClock.elapsedRealtime()+10000;AtomicReference<Boolean> front=new AtomicReference<>(false);do{main(()->front.set(activity.getLifecycle().getCurrentState()==androidx.lifecycle.Lifecycle.State.RESUMED&&activity.hasWindowFocus()));if(front.get())break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<until);JSONObject taskProof=new JSONObject().put("taskId",activity.getTaskId()).put("resumedAndFocused",front.get());AtomicReference<String> state=new AtomicReference<>();AtomicReference<Boolean> focus=new AtomicReference<>();main(()->{state.set(activity.getLifecycle().getCurrentState().name());focus.set(activity.hasWindowFocus());});taskProof.put("lifecycle",state.get()).put("windowFocus",focus.get());android.view.accessibility.AccessibilityNodeInfo active=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(active!=null){taskProof.put("activePackage",String.valueOf(active.getPackageName()));JSONArray labels=new JSONArray();java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayList<>();nodes.add(active);for(int i=0;i<nodes.size()&&i<160;i++){android.view.accessibility.AccessibilityNodeInfo n=nodes.get(i);if(n.getText()!=null)labels.put(n.getText().toString());for(int j=0;j<n.getChildCount();j++){android.view.accessibility.AccessibilityNodeInfo child=n.getChild(j);if(child!=null)nodes.add(child);}}taskProof.put("visibleLabels",labels);}write("resource-task-preflight.json",taskProof);assertTrue("preflight same task foreground: "+taskProof,front.get());scenario.onActivity(current->assertSame(activity,current));
                phase("background",false,true);}
            else{phase("idle",false,false);phase("stream",true,false);}
            write("resource-result.json",new JSONObject().put("complete",true).put("sourceSHA",args.getString("sourceSHA")).put("mode",mode).put("transport","raw").put("goLoaded",goLoaded).put("startupMillis",readyMillis).put("phases",phases).put("backgroundHeartbeats",heartbeats).put("screenshots",false).put("realChatGPTRequests",0).put("workload","three profiles, four offline documents, one 600-chunk DOM stream; no network stream measurement"));
        }catch(Exception|AssertionError failure){primary=failure;throw failure;}finally{
            try{
            main(()->KeepaliveService.disable(context));AtomicReference<Boolean> stopped=new AtomicReference<>(false);long limit=SystemClock.elapsedRealtime()+10000;
            do{main(()->stopped.set(!KeepaliveService.running()));if(stopped.get())break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<limit);assertTrue("test service actually stopped",stopped.get());
            scenario.close();AtomicReference<Boolean> destroyed=new AtomicReference<>(false);limit=SystemClock.elapsedRealtime()+10000;
            do{main(()->destroyed.set(activity==null||activity.isDestroyed()));if(destroyed.get())break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<limit);assertTrue("test Activity actually destroyed",destroyed.get());
            }catch(Exception|AssertionError cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}finally{MainActivity.handoffFixture=null;}
        }
    }
}
