package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A separate CI legacy account exercises real menus; the alpha.22 golden account stays unopened. */
@RunWith(AndroidJUnit4.class)
public final class RegularDualEngineSwitchTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger loads=new AtomicInteger(),unexpected=new AtomicInteger();
    private final JSONArray rounds=new JSONArray();
    private MainActivity activity;
    private String legacyId,storageId,tabId,url,legacyName,environmentName,expectedGeckoDraft;
    private static final String MAIN_DRAFT="OwnedDualMainDraft";
    private interface Check { boolean ready()throws Exception; }
    private void main(Runnable action){instrumentation.runOnMainSync(action);}
    private JSONObject read(String name)throws Exception{return new JSONObject(new String(Files.readAllBytes(RegularEnvironmentUpgradeTest.file(name).toPath()),StandardCharsets.UTF_8));}
    private void write(String name,JSONObject value)throws Exception{Files.write(RegularEnvironmentUpgradeTest.file(name).toPath(),(value.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
    private void waitFor(String label,Check check)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+45000;
        while(!check.ready()){if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new AssertionError("Timed out: "+label);Thread.sleep(150);}
    }
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor output=instrumentation.getUiAutomation().executeShellCommand(command);
            java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(output);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            return bytes.toString("UTF-8");
        }
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String label){
        if(root==null)return null;
        if(root.isVisibleToUser()&&(label.contentEquals(root.getText()==null?"":root.getText())||label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())))return AccessibilityNodeInfo.obtain(root);
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo child=root.getChild(i);AccessibilityNodeInfo found=find(child,label);if(child!=null)child.recycle();if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo node(String label){
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
        try{return find(root,label);}finally{if(root!=null)root.recycle();}
    }
    private boolean visible(String label){AccessibilityNodeInfo value=node(label);if(value==null)return false;value.recycle();return true;}
    private void click(String label)throws Exception{
        AtomicReference<AccessibilityNodeInfo> selected=new AtomicReference<>();
        waitFor("enabled native control "+label,()->{AccessibilityNodeInfo value=node(label);if(value==null)return false;if(!value.isEnabled()){value.recycle();return false;}selected.set(value);return true;});
        AccessibilityNodeInfo value=selected.get();try{Rect box=new Rect();value.getBoundsInScreen(box);assertTrue(box.width()>0&&box.height()>0);shell("input tap "+box.centerX()+" "+box.centerY());}finally{value.recycle();}
    }
    private JSONObject snapshot(WebView view)throws Exception{
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        main(()->view.evaluateJavascript("JSON.stringify({document:window.ownedDualDocument||'',draft:document.querySelector('#prompt-textarea')?.value||'',model:window.ownedDualModel||'',trusted:window.ownedDualTrusted||0,focused:document.activeElement===document.querySelector('#prompt-textarea'),scroll:scrollY,cookie:document.cookie,storage:localStorage.getItem('owned-dual-login')})",value->{result.set(value);done.countDown();}));
        assertTrue("Read-only document snapshot",done.await(5,TimeUnit.SECONDS));Object decoded=new JSONTokener(result.get()).nextValue();return decoded==JSONObject.NULL?new JSONObject():new JSONObject(String.valueOf(decoded));
    }
    private WebView current(){AtomicReference<WebView> value=new AtomicReference<>();main(()->value.set(activity.recoveryCurrentWeb()));return value.get();}
    private WebResourceResponse response(String account,WebResourceRequest request){
        String address=request.getUrl().toString();
        if(!legacyId.equals(account)||!url.equals(address)){
            if(!address.endsWith("/favicon.ico"))unexpected.incrementAndGet();
            return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
        }
        loads.incrementAndGet();
        String html="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>Owned dual legacy page</title><link rel='icon' href='data:,'><style>body{margin:0;padding:14px 14px 140px}article{height:6000px;background:linear-gradient(#edf3ff,#fff)}#composer{position:fixed;bottom:0;left:0;right:0;padding:12px;background:white}textarea{box-sizing:border-box;width:100%;height:76px;font:18px sans-serif}</style><main><article data-message-author-role='assistant' data-message-id='owned-dual-message'>Owned offline dual-engine fixture</article><div id='composer'><textarea id='prompt-textarea' aria-label='合成双引擎旧页编辑器'></textarea></div></main><script>window.ownedDualDocument=crypto.randomUUID();window.ownedDualModel='';window.ownedDualTrusted=0;if(!localStorage.getItem('owned-dual-login'))localStorage.setItem('owned-dual-login','preserved');if(!document.cookie.includes('owned_dual_visible='))document.cookie='owned_dual_visible=preserved;Path=/;Secure;SameSite=Lax';document.getElementById('prompt-textarea').addEventListener('input',e=>{window.ownedDualModel=e.target.value;if(e.isTrusted)window.ownedDualTrusted++;});</script>";
        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }
    private void nativeDraft(WebView web)throws Exception{
        AtomicReference<String> rectangle=new AtomicReference<>();CountDownLatch measured=new CountDownLatch(1);int[] at=new int[2];
        main(()->{web.getLocationOnScreen(at);web.evaluateJavascript("JSON.stringify((()=>{let r=document.getElementById('prompt-textarea').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,dpr:devicePixelRatio};})())",value->{rectangle.set(value);measured.countDown();});});
        assertTrue(measured.await(5,TimeUnit.SECONDS));JSONObject point=new JSONObject(String.valueOf(new JSONTokener(rectangle.get()).nextValue()));
        shell("input tap "+(at[0]+Math.round(point.getDouble("x")*point.getDouble("dpr")))+" "+(at[1]+Math.round(point.getDouble("y")*point.getDouble("dpr"))));
        waitFor("actual legacy editor focus",()->snapshot(web).optBoolean("focused"));
        AtomicReference<InputConnection> connection=new AtomicReference<>();
        waitFor("existing native text input connection",()->{main(()->{EditorInfo info=new EditorInfo();InputConnection input=web.onCreateInputConnection(info);connection.set((info.inputType&15)==1?input:null);});return connection.get()!=null;});
        main(()->assertTrue(connection.get().commitText(MAIN_DRAFT,1)));
        waitFor("trusted legacy DOM and controlled model",()->{JSONObject value=snapshot(web);return MAIN_DRAFT.equals(value.optString("draft"))&&MAIN_DRAFT.equals(value.optString("model"))&&value.optInt("trusted")>0;});
        main(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(web.getWindowToken(),0));
    }
    private void nativeScroll(WebView web)throws Exception{
        waitFor("legacy WebView layout",()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(web.isShown()&&web.hasWindowFocus()&&!web.isLayoutRequested()&&web.getWidth()>0&&web.getHeight()>200));return ready.get();});
        int[] at=new int[2],size=new int[2];main(()->{web.getLocationOnScreen(at);size[0]=web.getWidth();size[1]=web.getHeight();});
        int x=at[0]+size[0]/2;
        shell("input swipe "+x+" "+(at[1]+size[1]/2)+" "+x+" "+(at[1]+size[1]/5)+" 420");
        AtomicReference<Double> previous=new AtomicReference<>(-1.0);AtomicInteger stable=new AtomicInteger();
        waitFor("settled real native reading scroll",()->{double value=snapshot(web).optDouble("scroll");stable.set(value>100&&Math.abs(value-previous.get())<=1?stable.get()+1:0);previous.set(value);return stable.get()>=3;});
    }
    private void menu(String currentName,String targetName)throws Exception{click("切换账号，当前："+currentName);click(targetName);waitFor("native account header "+targetName,()->visible("切换账号，当前："+targetName));}
    private void geckoReady(boolean initial)throws Exception{
        if(initial){click("继续页面");click("恢复本地草稿");}
        waitFor("restored Gecko draft visible in the actual editor",()->visible(expectedGeckoDraft));
        WorkspaceState state=new StateStore(RegularEnvironmentUpgradeTest.context().getFilesDir()).load();
        JSONObject a=read("environment-acceptance.json").getJSONObject("accounts").getJSONObject("A");
        assertEquals(a.getString("id"),state.activeAccount);assertEquals(a.getJSONArray("tabs").getJSONObject(0).getString("id"),state.current().activeTab);
    }
    private void assertLegacy(WebView original,JSONObject before,String profile,String cookies)throws Exception{
        waitFor("same MainActivity resumed with its original legacy WebView",()->{AtomicReference<Boolean> same=new AtomicReference<>(false);main(()->same.set(activity.hasWindowFocus()&&!activity.isDestroyed()&&activity.recoveryCurrentWeb()==original));return same.get();});
        assertSame(original,current());JSONObject after=snapshot(original);
        assertEquals(before.getString("document"),after.getString("document"));assertEquals(MAIN_DRAFT,after.getString("draft"));assertEquals(MAIN_DRAFT,after.getString("model"));
        assertEquals(before.getDouble("scroll"),after.getDouble("scroll"),2);assertEquals("preserved",after.getString("storage"));assertEquals(before.getString("cookie"),after.getString("cookie"));
        AtomicReference<String> actualProfile=new AtomicReference<>(),actualCookies=new AtomicReference<>();
        main(()->{actualProfile.set(WebViewCompat.getProfile(original).getName());actualCookies.set(WebViewCompat.getProfile(original).getCookieManager().getCookie(url));});
        assertEquals(profile,actualProfile.get());assertEquals(cookies,actualCookies.get());assertEquals(1,loads.get());assertEquals(0,unexpected.get());
        WorkspaceState.Account account=new StateStore(RegularEnvironmentUpgradeTest.context().getFilesDir()).load().find(legacyId);assertFalse(account.isEnvironment());assertEquals(storageId,account.storageId);assertEquals(tabId,account.activeTab);
    }
    @Test public void realMainWebViewSurvivesEnvironmentAccountRoundTrips()throws Exception{
        int api=RegularEnvironmentUpgradeTest.identity(RegularEnvironmentUpgradeTest.NEW_CODE);
        assertTrue(BuildConfig.ENVIRONMENT_CLIENT&&BuildConfig.ENVIRONMENT_TEST&&!BuildConfig.ENVIRONMENT_NETWORK_TEST);
        assertTrue((RegularEnvironmentUpgradeTest.context().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_TEST_ONLY)!=0);
        expectedGeckoDraft=RegularEnvironmentUpgradeTest.args().getString("expectedDraft","");assertTrue("Explicit synthetic Gecko draft required",expectedGeckoDraft.matches("[A-Za-z0-9-]{1,128}"));
        assertNull("No concurrent fixture ownership",MainActivity.handoffFixture);
        JSONObject golden=RegularEnvironmentUpgradeTest.expected();String expectedHash=RegularEnvironmentUpgradeTest.hash(RegularEnvironmentUpgradeTest.file(RegularEnvironmentUpgradeTest.EXPECTED));RegularEnvironmentUpgradeTest.verifyNative(golden,true);
        JSONObject prepared=read("environment-acceptance.json").getJSONObject("accounts").getJSONObject("A");
        StateStore store=new StateStore(RegularEnvironmentUpgradeTest.context().getFilesDir());WorkspaceState existing=store.load();WorkspaceState.Account environment=existing.find(prepared.getString("id"));
        assertTrue(environment.isEnvironment());assertEquals(prepared.getString("storageId"),environment.storageId);assertEquals(prepared.getJSONArray("tabs").getJSONObject(0).getString("id"),environment.activeTab);environmentName=environment.name;
        legacyName="Owned dual engine legacy "+WorkspaceState.id().substring(0,8);
        WorkspaceState setup=store.update(state->{WorkspaceState.Account added=state.addAccount(legacyName,"",WorkspaceState.COLORS[0]);legacyId=added.id;storageId=added.storageId;tabId=added.current().id;url="https://chatgpt.com/c/owned-dual-"+tabId;added.current().url=url;added.current().title="Owned dual legacy page";added.current().customTitle=true;state.activeAccount=added.id;});
        assertFalse(setup.current().isEnvironment());assertNotEquals(golden.getString("accountId"),legacyId);assertNotEquals(golden.getString("storageId"),storageId);
        android.app.UiAutomation automation=instrumentation.getUiAutomation();android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();assertNotNull(service);service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
        MainActivity.handoffFixture=this::response;
        try{
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(RegularEnvironmentUpgradeTest.context(),MainActivity.class).putExtra(EnvironmentLauncher.ACCOUNT_ID,legacyId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor("first intercepted Main document",()->current()!=null&&!snapshot(current()).optString("document").isEmpty());WebView original=current();
            nativeDraft(original);nativeScroll(original);JSONObject before=snapshot(original);
            AtomicReference<String> profile=new AtomicReference<>(),cookies=new AtomicReference<>();AtomicReference<Boolean> cookieOk=new AtomicReference<>();CountDownLatch cookieDone=new CountDownLatch(1);
            main(()->WebViewCompat.getProfile(original).getCookieManager().setCookie(url,"owned_dual_auth=preserved;Path=/;Secure;HttpOnly;SameSite=Lax;Max-Age=86400",ok->{cookieOk.set(ok);cookieDone.countDown();}));assertTrue(cookieDone.await(10,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,cookieOk.get());
            main(()->{profile.set(WebViewCompat.getProfile(original).getName());WebViewCompat.getProfile(original).getCookieManager().flush();cookies.set(WebViewCompat.getProfile(original).getCookieManager().getCookie(url));});
            assertEquals(WorkspaceState.profile(storageId),profile.get());assertTrue(cookies.get().contains("owned_dual_auth=preserved"));assertTrue(cookies.get().contains("owned_dual_visible=preserved"));
            for(int index=0;index<2;index++){
                JSONObject round=new JSONObject().put("round",index+1).put("toGeckoStartedAt",System.currentTimeMillis());
                menu(legacyName,environmentName);geckoReady(index==0);round.put("geckoReadyAt",System.currentTimeMillis()).put("expectedGeckoDraftSHA256",RegularEnvironmentUpgradeTest.hash(expectedGeckoDraft.getBytes(StandardCharsets.UTF_8)));
                menu(environmentName,legacyName);assertLegacy(original,before,profile.get(),cookies.get());
                round.put("mainReturnedAt",System.currentTimeMillis()).put("sameMainActivity",true).put("sameWebViewObject",true).put("sameDocument",true).put("sameDraftAndModel",true).put("sameScroll",true).put("sameProfileAndLogin",true);rounds.put(round);
            }
            RegularEnvironmentUpgradeTest.verifyNative(golden,true);assertEquals(expectedHash,RegularEnvironmentUpgradeTest.hash(RegularEnvironmentUpgradeTest.file(RegularEnvironmentUpgradeTest.EXPECTED)));
            JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("api",api).put("package",RegularEnvironmentUpgradeTest.PACKAGE).put("versionCode",RegularEnvironmentUpgradeTest.NEW_CODE).put("signerSHA256",RegularEnvironmentUpgradeTest.signer())
                .put("scope","separate-synthetic-legacy-Main-account; original-alpha22-golden-account-unopened").put("newInstrumentationMayColdStartGecko",true).put("geckoNoReloadRequiresExternalServerProof",true)
                .put("originalBaselineAccountUnopened",true).put("originalBaselineAndTwoCipherRecordsUnchanged",true).put("originalExpectedSHA256",expectedHash)
                .put("syntheticLegacyAccountId",legacyId).put("syntheticLegacyStorageId",storageId).put("syntheticLegacyTabId",tabId).put("syntheticLegacyProfile",profile.get()).put("document",before.getString("document"))
                .put("legacyDraftSHA256",RegularEnvironmentUpgradeTest.hash(MAIN_DRAFT.getBytes(StandardCharsets.UTF_8))).put("legacyScroll",before.getDouble("scroll")).put("legacyPageLoads",loads.get()).put("unexpectedLegacyRequests",unexpected.get()).put("roundTrips",rounds)
                .put("nativeAccountMenusUsed",true).put("trustedNativeDraftInput",true).put("nativeReadingScroll",true).put("screenshots",false).put("realServicesAccessed",false).put("productionSignatureVerified",false);
            write("regular-dual-engine-switch.json",proof);Bundle output=new Bundle();output.putString("regularDualEngineSwitchProof",proof.toString());instrumentation.addResults(output);
        }catch(Exception|AssertionError failure){
            try{java.io.File error=RegularEnvironmentUpgradeTest.file("regular-dual-engine-switch-first-error.txt");if(!error.exists())Files.write(error.toPath(),android.util.Log.getStackTraceString(failure).getBytes(StandardCharsets.UTF_8));}catch(Exception logError){failure.addSuppressed(logError);}throw failure;
        }finally{if(activity!=null){main(activity::finish);instrumentation.waitForIdleSync();}MainActivity.handoffFixture=null;}
    }
}
