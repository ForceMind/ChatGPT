package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Text recovery uses the existing debug offline fixture; every webpage response is intercepted. */
@RunWith(AndroidJUnit4.class)
public final class NetworkRestartTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private JSONObject read(String name)throws Exception{return new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),name).toPath()),StandardCharsets.UTF_8));}
    private String js(WebView web,String code)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> out=new AtomicReference<>();main(()->web.evaluateJavascript(code,value->{out.set(value);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return out.get();}
    @Test public void coldProcessRestoresSelectedRouteAndEncryptedDraft()throws Exception{
        assertTrue(BuildConfig.NETWORK_TEST);Bundle args=InstrumentationRegistry.getArguments();String phase=args.getString("phase");assertTrue("seed".equals(phase)||"verify".equals(phase));
        JSONObject result=new JSONObject().put("schema",1).put("complete",false).put("sourceSHA",args.getString("sourceSHA")).put("phase",phase).put("pid",android.os.Process.myPid()).put("screenshots",false).put("realChatGPT",false).put("fixturePage","logical ChatGPT origin served by offline interceptor; no real service requests");
        if("seed".equals(phase)){
            WorkspaceState state=WorkspaceFiles.read(context.getFilesDir());WorkspaceState.Account account=state.current();String url="https://chatgpt.com/c/process-restart-fixture",draft="Synthetic encrypted process recovery draft";
            account.current().url=url;new StateStore(context.getFilesDir()).save(state);
            PageRecoveryStore store=new PageRecoveryStore(context.getFilesDir(),DevicePayloadKeys.recoveryCipher(context));
            store.save(new JSONObject().put("schema",1).put("accountId",account.id).put("storageId",account.storageId).put("tabId",account.activeTab).put("url",url).put("draft",draft).put("scroll",0).put("ratio",0).put("capturedAt",123456789),store.generation(account.id,account.storageId));
            CountDownLatch done=new CountDownLatch(1);main(()->ProfileStore.getInstance().getOrCreateProfile(WorkspaceState.profile(account.storageId)).getCookieManager().setCookie("https://chatgpt.com/","ci_restart_marker=synthetic-persisted; Path=/; Secure; SameSite=Lax",ok->{assertTrue(ok);ProfileStore.getInstance().getProfile(WorkspaceState.profile(account.storageId)).getCookieManager().flush();done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));
            JSONObject config=new JSONObject().put("token",WorkspaceState.id()).put("accountId",account.id).put("storageId",account.storageId).put("tabId",account.activeTab).put("phase","verify");Files.write(new File(context.getFilesDir(),"process-restart-fixture.json").toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject safety=read("formal-network-files.json");result.put("account",account.id).put("storage",account.storageId).put("tab",account.activeTab).put("draft",draft).put("clearedStorage",safety.getJSONObject("accountClear").getString("oldStorage")).put("route",new NetworkSettingsStore(context).read().selected).put("complete",true);
        }else{
            JSONObject seed=read("network-restart-seed.json");assertNotEquals(seed.getInt("pid"),android.os.Process.myPid());assertNotNull("offline interceptor must be installed before the Activity",MainActivity.handoffFixture);
            AtomicReference<MainActivity> activity=new AtomicReference<>();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(activity::set);BrowserMemory memory=BrowserMemory.get(context);long end=SystemClock.elapsedRealtime()+35000;WebView web=null;
                do{AtomicReference<WebView> found=new AtomicReference<>();main(()->found.set(activity.get().recoveryCurrentWeb()));web=found.get();if(web!=null&&memory.network().ready())break;Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);assertNotNull(web);assertTrue(memory.network().ready());assertEquals(NetworkRoute.State.CONNECTED,memory.network().routes.snapshot().state);assertEquals(seed.getInt("route"),memory.network().selected());
                String value=null;do{value=js(web,"document.querySelector('#prompt-textarea')?document.querySelector('#prompt-textarea').value:null");if(JSONObject.quote(seed.getString("draft")).equals(value))break;Thread.sleep(75);}while(SystemClock.elapsedRealtime()<end);assertEquals(JSONObject.quote(seed.getString("draft")),value);
                AtomicReference<String> cookie=new AtomicReference<>(),profile=new AtomicReference<>();WebView selected=web;main(()->{profile.set(WebViewCompat.getProfile(selected).getName());cookie.set(WebViewCompat.getProfile(selected).getCookieManager().getCookie("https://chatgpt.com/"));assertFalse(ProfileStore.getInstance().getAllProfileNames().contains(WorkspaceState.profile(seed.optString("clearedStorage"))));});assertEquals(WorkspaceState.profile(seed.getString("storage")),profile.get());assertTrue(cookie.get().contains("ci_restart_marker=synthetic-persisted"));
                JSONObject stored=memory.recovery.store.load(seed.getString("account"),seed.getString("storage"),seed.getString("tab"),"https://chatgpt.com/c/process-restart-fixture");assertNotNull(stored);assertEquals(seed.getString("draft"),stored.getString("draft"));
                result.put("oldPid",seed.getInt("pid")).put("newDocument",true).put("sameRamDocumentClaim",false).put("draftActualDOMMatches",true).put("cookiePreserved",true).put("profilePreserved",true).put("deferredClearedProfileRemoved",true).put("selectedRouteRestored",true).put("complete",true);
            }
        }
        Files.write(new File(context.getFilesDir(),"network-restart-"+phase+".json").toPath(),result.toString().getBytes(StandardCharsets.UTF_8));System.out.println("NETWORK_PROCESS_RESTORE "+result);
    }
}
