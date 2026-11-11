package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native chrome and retained legacy WebViews. Every request is locally intercepted. */
@RunWith(AndroidJUnit4.class)
public final class BrowserChromeNavigationTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger firstLoads=new AtomicInteger(),secondLoads=new AtomicInteger(),nextLoads=new AtomicInteger(),unexpected=new AtomicInteger();
    private MainActivity activity;
    private String phase="setup";
    private final org.json.JSONArray phases=new org.json.JSONArray();
    private String timingCase="";
    private JSONObject timingProof;
    private PageRecoveryManager.EnvironmentTiming timingProbe;
    private static final String ORIGIN="https://chatgpt.com/c/owned-ui-";
    private interface Check { boolean ready()throws Exception; }
    private void main(Runnable run){instrumentation.runOnMainSync(run);}
    private String evidenceName(String suffix){return "browser-chrome"+(timingCase.isEmpty()?"":"-"+timingCase)+suffix;}
    private JSONObject nativeEvidence()throws Exception{
        AtomicReference<JSONObject> value=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        main(()->{try{value.set(activity.environmentNavigationEvidence());}catch(Exception failure){error.set(failure);}});if(error.get()!=null)throw error.get();return value.get();
    }
    private int timingEvents(String name)throws Exception{
        org.json.JSONArray events=nativeEvidence().getJSONArray("events");int count=0;for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(e.getLong("elapsedMillis")>=timingProbe.enteredMillis&&e.getString("event").equals(name))count++;}return count;
    }
    private long timingEventTime(String name)throws Exception{org.json.JSONArray events=nativeEvidence().getJSONArray("events");for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(e.getLong("elapsedMillis")>=timingProbe.enteredMillis&&e.getString("event").equals(name))return e.getLong("elapsedMillis");}return -1;}
    private void writeTimingProof()throws Exception{if(timingProof!=null){if(timingProbe!=null&&!timingProbe.readError.isEmpty())timingProof.put("readErrorClass",timingProbe.readError);java.nio.file.Files.write(EnvironmentUpgradeSeedTest.file(evidenceName("-timing.json")).toPath(),timingProof.toString().getBytes(StandardCharsets.UTF_8));}}
    private void timingReady()throws Exception{
        // New timing-case setup only; original page/navigation assertions keep their own deadlines.
        waitFor("timing fixture has a live idle capture owner",()->{
            JSONObject n=nativeEvidence(),r=n.getJSONObject("recovery");
            return n.getBoolean("ready")&&n.getBoolean("resumed")&&!n.getBoolean("pending")&&!n.getBoolean("nativeBusy")&&!n.getBoolean("loading")&&n.getInt("progress")==100
                &&r.getBoolean("valid")&&r.getBoolean("installed")&&r.getBoolean("liveReady")&&r.getBoolean("currentUrlMatches")
                &&!r.getBoolean("loading")&&!r.getBoolean("restoring")&&!r.getBoolean("restoreCall")&&!r.getBoolean("inflight")&&!r.getBoolean("blocked")
                &&!r.getBoolean("waitingOffline")&&!r.getBoolean("preview")&&!r.getBoolean("failed")&&!r.getBoolean("authenticationRequired");
        });
    }
    private void armTiming()throws Exception{timingReady();main(()->timingProbe=activity.environmentNavigationTiming(timingCase));}
    private void savedTimingDraft(String expected)throws Exception{
        assertTrue("Real capture save returned",timingProbe.saveFinished.await(1500,TimeUnit.MILLISECONDS));assertTrue("Real fixture revision reached captured record",timingProbe.fixtureRevisionCaptured);assertTrue("Real encrypted save succeeded",timingProbe.saveSucceeded);assertTrue("Real encrypted write, not a deduplicated no-op",timingProbe.saveChanged);
        AtomicReference<Boolean> saved=new AtomicReference<>();CountDownLatch read=new CountDownLatch(1);
        main(()->activity.environmentNavigationReadSaved(timingProbe,expected,value->{saved.set(value);read.countDown();}));
        assertTrue("Real encrypted record read returned",read.await(5,TimeUnit.SECONDS));assertEquals("Real encrypted draft round trip",Boolean.TRUE,saved.get());
        long held=timingProbe.releasedMillis-timingProbe.enteredMillis;assertTrue("IO gate remains below the original 2s capture budget",held>=0&&held<2000);
        timingProof.put("fixtureRevisionApplied",true).put("realEncryptedSave",true).put("realSaveChanged",true).put("realEncryptedRead",true).put("oldEarlyReleaseApplied",timingProbe.oldReleaseApplied).put("gateStartedMillis",timingProbe.enteredMillis).put("gateHeldMillis",held).put("saveCompletedMillis",timingProbe.savedMillis).put("captureTicket",timingProbe.captureTicket).put("serial",timingProbe.serial);
    }
    private void latePollDuringSave(String draft)throws Exception{
        try{
            assertTrue("Navigation capture reached real IO",timingProbe.saveEntered.await(1250,TimeUnit.MILLISECONDS));assertFalse("Real save is still gated",timingProbe.saveSucceeded);
            boolean baseline=timingCase.equals("baseline-late-poll");JSONObject n=nativeEvidence(),before=n.getJSONObject("recovery");assertEquals(!baseline,before.getBoolean("inflight"));assertTrue(n.getBoolean("pending"));assertFalse(n.getBoolean("nativeBusy"));assertEquals(timingProbe.serial,before.getLong("serial"));assertEquals(timingProbe.captureTicket,before.getLong("captureTicket"));
            main(()->activity.environmentNavigationPoll(timingProbe));assertTrue("Original poll main-thread round returned",timingProbe.pollReturned.await(500,TimeUnit.MILLISECONDS));
            long after=nativeEvidence().getJSONObject("recovery").getLong("captureTicket");assertEquals("Only the old release permits a competing real poll capture",timingProbe.captureTicket+(baseline?1:0),after);
            assertEquals(0,timingEvents("action-approved"));assertEquals(0,timingEvents("page-start:one"));
            timingProof.put("realPollWhileSavePending",true).put("noNavigationBeforeSave",true).put("pollAdvancedTicket",baseline).put("ticketAfterPoll",after);
        }finally{timingProbe.releaseSave.countDown();}
        savedTimingDraft(draft);writeTimingProof();
    }
    private void earlyPollRefusesBack(String draft)throws Exception{
        timingReady();type(draft);String document=snapshot(web()).getString("document");click("更多功能");armTiming();
        try{
            main(()->activity.environmentNavigationPoll(timingProbe));assertTrue("Original poll main-thread round returned",timingProbe.pollReturned.await(500,TimeUnit.MILLISECONDS));
            assertTrue("Original poll reached real save IO",timingProbe.saveEntered.await(1250,TimeUnit.MILLISECONDS));assertFalse(timingProbe.saveSucceeded);
            JSONObject n=nativeEvidence(),r=n.getJSONObject("recovery");assertTrue(r.getBoolean("inflight"));assertFalse(n.getBoolean("pending"));assertFalse(n.getBoolean("nativeBusy"));assertEquals(timingProbe.serial,r.getLong("serial"));assertEquals(timingProbe.captureTicket,r.getLong("captureTicket"));click("后退");
            waitFor("back safely refused while poll save is pending",()->timingEvents("recovery-cancelled")==1);
            assertFalse("Refusal occurs before save finishes",timingProbe.saveSucceeded);assertEquals(0,timingEvents("action-approved"));
            assertEquals("The refused back cannot begin navigation",0,timingEvents("page-start:one"));
            timingProof.put("realPollWhileSavePending",true).put("noNavigationBeforeSave",true).put("refusalBeforeSave",true);
        }finally{timingProbe.releaseSave.countDown();}
        savedTimingDraft(draft);assertEquals(document,snapshot(web()).getString("document"));loaded("next");assertDraft(draft);assertEquals(0,unexpected.get());
        assertEquals(1,timingEvents("recovery-cancelled"));assertEquals(0,timingEvents("action-approved"));long refused=timingEventTime("recovery-cancelled");assertTrue(refused<=timingProbe.savedMillis);
        timingProof.put("complete",true).put("expectedFailure",false).put("safeRefusal",true).put("refusedMillis",refused).put("documentUnchanged",true).put("draftCookieStoragePreserved",true).put("screenshots",false).put("realServicesAccessed",false);writeTimingProof();
    }
    private void phase(String value)throws Exception{
        phase=value;phases.put(new JSONObject().put("phase",value).put("elapsedMillis",android.os.SystemClock.elapsedRealtime()));
        android.util.Log.i("BrowserChromeAcceptance","phase="+value);
    }
    private void saveNavigationEvidence(String failure){
        try{
            AtomicReference<JSONObject> nativeState=new AtomicReference<>();AtomicReference<String> nativeStateReadError=new AtomicReference<>();
            main(()->{try{nativeState.set(activity.environmentNavigationEvidence());}catch(Exception error){nativeStateReadError.set(error.getClass().getSimpleName());}});
            JSONObject evidence=new JSONObject().put("schema",1).put("phase",phase).put("failure",failure).put("phases",phases)
                .put("firstLoads",firstLoads.get()).put("secondLoads",secondLoads.get()).put("nextLoads",nextLoads.get()).put("unexpectedRequests",unexpected.get());
            if(nativeState.get()!=null)evidence.put("native",nativeState.get());
            if(nativeStateReadError.get()!=null)evidence.put("nativeStateReadError",nativeStateReadError.get());
            java.nio.file.Files.write(EnvironmentUpgradeSeedTest.file(evidenceName("-navigation.json")).toPath(),evidence.toString().getBytes(StandardCharsets.UTF_8));
            // Observe a transitioning/opaque document without letting cookie access suppress all evidence.
            AtomicReference<String> raw=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
            main(()->activity.recoveryCurrentWeb().evaluateJavascript("JSON.stringify((function(){var s={page:'other',readyState:document.readyState,ownedDocument:!!window.ownedDocument,modelLength:(window.ownedModel||'').length,trusted:window.ownedTrusted||0};for(var p of ['one','two','next'])if(location.href==='https://chatgpt.com/c/owned-ui-'+p)s.page=p;var e=document.querySelector('#prompt-textarea');s.editor=!!e;s.draftLength=e?(e.value||'').length:0;s.draftMatchesModel=!!e&&e.value===(window.ownedModel||'');s.focused=!!e&&document.activeElement===e;try{s.cookiePreserved=document.cookie.indexOf('owned-ui=preserved')>=0;}catch(e){s.cookieError=e.name;}try{s.storagePreserved=localStorage.getItem('owned-ui')==='preserved';}catch(e){s.storageError=e.name;}return s;})())",value->{raw.set(value);done.countDown();}));
            if(done.await(5,TimeUnit.SECONDS)&&raw.get()!=null){Object decoded=new JSONTokener(raw.get()).nextValue();if(decoded!=JSONObject.NULL)evidence.put("document",new JSONObject(String.valueOf(decoded)));}
            java.nio.file.Files.write(EnvironmentUpgradeSeedTest.file(evidenceName("-navigation.json")).toPath(),evidence.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception error){android.util.Log.e("BrowserChromeAcceptance","Cannot preserve navigation state",error);}
    }
    private void waitFor(String label,Check check)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+30000;
        while(!check.ready()){if(android.os.SystemClock.elapsedRealtime()>deadline){saveNativeTree();throw new AssertionError("Timed out: "+label);}Thread.sleep(150);}
    }
    private void saveNativeTree(){
        try{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();StringBuilder text=new StringBuilder();nativeTree(root,text,0);if(root!=null)root.recycle();java.nio.file.Files.write(EnvironmentUpgradeSeedTest.file(evidenceName("-native-tree.txt")).toPath(),text.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception error){android.util.Log.e("BrowserChromeAcceptance","Cannot preserve native tree",error);}
    }
    private void nativeTree(AccessibilityNodeInfo node,StringBuilder text,int depth){
        if(node==null){text.append("no active accessibility root\n");return;}if(depth>30||text.length()>65536)return;
        Rect box=new Rect();node.getBoundsInScreen(box);text.append(node.getPackageName()).append(' ').append(node.getClassName()).append(" text=").append(node.getText()).append(" description=").append(node.getContentDescription()).append(" visible=").append(node.isVisibleToUser()).append(" enabled=").append(node.isEnabled()).append(' ').append(box).append('\n');
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);nativeTree(child,text,depth+1);if(child!=null)child.recycle();}
    }
    private WebView web(){AtomicReference<WebView> result=new AtomicReference<>();main(()->result.set(activity.recoveryCurrentWeb()));assertNotNull(result.get());return result.get();}
    private JSONObject snapshot(WebView view)throws Exception{
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch latch=new CountDownLatch(1);
        main(()->view.evaluateJavascript("JSON.stringify({url:location.href,document:window.ownedDocument||'',draft:document.querySelector('#prompt-textarea')?.value||'',model:window.ownedModel||'',trusted:window.ownedTrusted||0,focused:document.activeElement===document.querySelector('#prompt-textarea'),cookie:document.cookie,storage:localStorage.getItem('owned-ui')})",value->{result.set(value);latch.countDown();}));
        assertTrue("Read-only browser observation",latch.await(5,TimeUnit.SECONDS));Object decoded=new JSONTokener(result.get()).nextValue();return decoded==JSONObject.NULL?new JSONObject():new JSONObject(String.valueOf(decoded));
    }
    private void loaded(String suffix)throws Exception{waitFor("owned page "+suffix,()->{JSONObject s=snapshot(web());return (ORIGIN+suffix).equals(s.optString("url"))&&!s.optString("document").isEmpty();});}
    private void shell(String command)throws Exception{
        try(ParcelFileDescriptor output=instrumentation.getUiAutomation().executeShellCommand(command);java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(output)){byte[] bytes=new byte[1024];while(input.read(bytes)>=0){/* Drain the bounded native input command. */}}
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String label){
        if(root==null)return null;
        if(root.isVisibleToUser()&&(label.contentEquals(root.getText()==null?"":root.getText())||label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())))return AccessibilityNodeInfo.obtain(root);
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo child=root.getChild(i);AccessibilityNodeInfo found=find(child,label);if(child!=null)child.recycle();if(found!=null)return found;}return null;
    }
    private AccessibilityNodeInfo control(String label)throws Exception{return control(label,false);}
    private AccessibilityNodeInfo control(String label,boolean enabled)throws Exception{
        AtomicReference<AccessibilityNodeInfo> result=new AtomicReference<>();waitFor("native control "+label,()->{AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();AccessibilityNodeInfo found=find(root,label);if(root!=null)root.recycle();if(found!=null&&enabled&&!found.isEnabled()){found.recycle();found=null;}result.set(found);return found!=null;});return result.get();
    }
    private void click(String label)throws Exception{AccessibilityNodeInfo node=control(label,true);try{assertTrue("Enabled native control "+label,node.isEnabled());Rect box=new Rect();node.getBoundsInScreen(box);assertTrue(box.width()>0&&box.height()>0);shell("input tap "+box.centerX()+" "+box.centerY());}finally{node.recycle();}}
    private void tapWeb(String id)throws Exception{
        WebView view=web();AtomicReference<String> result=new AtomicReference<>();CountDownLatch latch=new CountDownLatch(1);int[] location=new int[2];
        main(()->{view.getLocationOnScreen(location);view.evaluateJavascript("JSON.stringify((()=>{let n=document.getElementById("+JSONObject.quote(id)+"),r=n.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,dpr:devicePixelRatio};})())",value->{result.set(value);latch.countDown();});});
        assertTrue(latch.await(5,TimeUnit.SECONDS));JSONObject point=new JSONObject(String.valueOf(new JSONTokener(result.get()).nextValue()));
        shell("input tap "+(location[0]+Math.round(point.getDouble("x")*point.getDouble("dpr")))+" "+(location[1]+Math.round(point.getDouble("y")*point.getDouble("dpr"))));
    }
    private void waitUntil(String label,Check check,long deadline)throws Exception{
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(check.ready()&&android.os.SystemClock.elapsedRealtime()<deadline)return;
            long remaining=deadline-android.os.SystemClock.elapsedRealtime();if(remaining>0)Thread.sleep(Math.min(150,remaining));
        }
        saveNativeTree();throw new AssertionError("Timed out: "+label);
    }
    private void nativeEditors(AccessibilityNodeInfo node,AccessibilityNodeInfo host,boolean inside,Rect viewport,java.util.List<Rect> matches){
        if(node==null)return;inside=inside||node.equals(host);
        if(inside&&EnvironmentUpgradeSeedTest.PACKAGE.contentEquals(node.getPackageName()==null?"":node.getPackageName())
                &&"android.widget.EditText".contentEquals(node.getClassName()==null?"":node.getClassName())&&node.isVisibleToUser()&&node.isEnabled()){
            Rect box=new Rect();node.getBoundsInScreen(box);if(box.width()>0&&box.height()>0&&viewport.contains(box))matches.add(box);
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);try{nativeEditors(child,host,inside,viewport,matches);}finally{if(child!=null)child.recycle();}}
    }
    private void focusApi35(WebView view)throws Exception{
        final long deadline=android.os.SystemClock.elapsedRealtime()+30000;AtomicReference<Rect> target=new AtomicReference<>();
        waitUntil("API35 focused window and unique native composer layout",()->{
            AtomicReference<AccessibilityNodeInfo> host=new AtomicReference<>();Rect viewport=new Rect();
            main(()->{if(activity.hasWindowFocus()&&activity.recoveryCurrentWeb()==view&&view.hasWindowFocus()&&view.isAttachedToWindow()
                    &&view.isLaidOut()&&!view.isLayoutRequested()&&view.isShown()&&view.isEnabled()&&view.getAlpha()>=.99f
                    &&view.getWidth()>0&&view.getHeight()>0&&view.getGlobalVisibleRect(viewport)
                    &&viewport.width()==view.getWidth()&&viewport.height()==view.getHeight())host.set(view.createAccessibilityNodeInfo());});
            if(host.get()==null)return false;
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();java.util.ArrayList<Rect> matches=new java.util.ArrayList<>();
            try{if(root==null||root.getWindowId()!=host.get().getWindowId())return false;nativeEditors(root,host.get(),false,viewport,matches);}
            finally{if(root!=null)root.recycle();host.get().recycle();}
            assertTrue("Only one visible native EditText in the current WebView",matches.size()<=1);if(matches.isEmpty())return false;
            AtomicReference<Boolean> unchanged=new AtomicReference<>(false);main(()->{Rect latest=new Rect();unchanged.set(activity.hasWindowFocus()&&activity.recoveryCurrentWeb()==view&&view.hasWindowFocus()
                    &&!view.isLayoutRequested()&&view.getGlobalVisibleRect(latest)&&latest.equals(viewport));});
            if(!unchanged.get())return false;target.set(new Rect(matches.get(0)));return true;
        },deadline);
        assertTrue("Native tap must remain inside the shared focus deadline",android.os.SystemClock.elapsedRealtime()<deadline);
        Rect box=target.get();shell("input tap "+box.centerX()+" "+box.centerY());
        waitUntil("real composer focus",()->{
            long remaining=deadline-android.os.SystemClock.elapsedRealtime();if(remaining<=0)return false;
            AtomicReference<String> observed=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
            main(()->view.evaluateJavascript("JSON.stringify({focused:document.activeElement===document.querySelector('#prompt-textarea')})",raw->{observed.set(raw);done.countDown();}));
            remaining=deadline-android.os.SystemClock.elapsedRealtime();if(remaining<=0||!done.await(Math.min(5000,remaining),TimeUnit.MILLISECONDS))return false;
            Object decoded=new JSONTokener(observed.get()).nextValue();return decoded!=JSONObject.NULL&&new JSONObject(String.valueOf(decoded)).getBoolean("focused");
        },deadline);
    }
    private void type(String draft)throws Exception{
        WebView view=web();if(android.os.Build.VERSION.SDK_INT==35)focusApi35(view);else{tapWeb("prompt-textarea");waitFor("real composer focus",()->snapshot(view).getBoolean("focused"));}
        AtomicReference<InputConnection> connection=new AtomicReference<>();waitFor("native text connection",()->{main(()->{EditorInfo editor=new EditorInfo();InputConnection input=view.onCreateInputConnection(editor);connection.set((editor.inputType&15)==1?input:null);});return connection.get()!=null;});
        if(!timingCase.isEmpty()){int length=snapshot(view).optString("draft").length();main(()->assertTrue(connection.get().setSelection(0,length)));}
        main(()->assertTrue(connection.get().commitText(draft,1)));waitFor("trusted DOM and model text",()->{JSONObject value=snapshot(view);return draft.equals(value.getString("draft"))&&draft.equals(value.getString("model"))&&value.getInt("trusted")>0;});
        main(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0));
    }
    private WebResourceResponse response(String account,android.webkit.WebResourceRequest request){
        String url=request.getUrl().toString();String page=null;
        if(url.equals(ORIGIN+"one")){firstLoads.incrementAndGet();page="旧页一";}else if(url.equals(ORIGIN+"two")){secondLoads.incrementAndGet();page="旧页二";}else if(url.equals(ORIGIN+"next")){nextLoads.incrementAndGet();page="后续页";}
        if(page==null){if(!url.endsWith("/favicon.ico"))unexpected.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        String body="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>"+page+"</title><main><article data-message-author-role='assistant' data-message-id='owned-message'>仅合成网页，无真实服务</article><p><a id='next' href='"+ORIGIN+"next'>进入下一页</a></p><textarea id='prompt-textarea' rows='3' style='width:90%;font-size:18px' aria-label='合成旧网页编辑器'></textarea></main><script>window.ownedDocument=crypto.randomUUID();window.ownedModel='';window.ownedTrusted=0;if(!localStorage.getItem('owned-ui'))localStorage.setItem('owned-ui','preserved');if(!document.cookie.includes('owned-ui='))document.cookie='owned-ui=preserved;Path=/;Secure;SameSite=Lax';document.getElementById('prompt-textarea').addEventListener('input',e=>{window.ownedModel=e.target.value;if(e.isTrusted)window.ownedTrusted++;});</script>";
        if(!timingCase.isEmpty())body+="<script>window.ownedTimingCapture=function(revision){document.querySelector(\"[data-message-id=owned-message]\").textContent=revision;};</script>";
        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    private void assertDraft(String expected)throws Exception{
        waitFor("protected legacy draft",()->{JSONObject v=snapshot(web());return expected.equals(v.getString("draft"))&&expected.equals(v.getString("model"));});
        JSONObject v=snapshot(web());assertTrue(v.getString("cookie").contains("owned-ui=preserved"));assertEquals("preserved",v.getString("storage"));
    }
    @Test public void realHeaderAndNavigationPreserveLegacyPage()throws Exception{
        android.content.Context context=instrumentation.getTargetContext();Class<?> config=Class.forName("com.forcemind.chatgpt.profiles.BuildConfig",false,context.getClassLoader());assertTrue(config.getField("ENVIRONMENT_TEST").getBoolean(null));
        timingCase=InstrumentationRegistry.getArguments().getString("navigationTimingCase","");
        assertTrue("Explicit owned timing case",timingCase.isEmpty()||java.util.Arrays.asList("baseline-late-poll","fixed-late-poll","fixed-early-poll").contains(timingCase));
        if(!timingCase.isEmpty())timingProof=new JSONObject().put("schema",1).put("case",timingCase).put("complete",false).put("counterfactual",timingCase.equals("baseline-late-poll")?"old-early-release-line-only":"none");
        int api=EnvironmentUpgradeSeedTest.verifyIdentity(EnvironmentUpgradeSeedTest.NEW_CODE);AtomicReference<String> first=new AtomicReference<>(),second=new AtomicReference<>();
        // Connect before the first window event. Android9 may not replay an already
        // displayed window to a lazily connected accessibility automation service.
        android.app.UiAutomation automation=instrumentation.getUiAutomation();android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();assertNotNull(service);service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(service);
        JSONObject priorFixture=timingCase.isEmpty()?null:new JSONObject(new String(java.nio.file.Files.readAllBytes(EnvironmentUpgradeSeedTest.file("browser-chrome-profile.json").toPath()),StandardCharsets.UTF_8));
        WorkspaceState prepared=new StateStore(context.getFilesDir()).update(state->{WorkspaceState.Account account;
            if(priorFixture==null)account=state.addAccount("网页测试","",WorkspaceState.COLORS[0]);
            else{account=state.find(priorFixture.optString("accountId"));assertEquals("网页测试",account.name);assertFalse(account.isEnvironment());assertEquals(priorFixture.optString("storageId"),account.storageId);assertEquals(2,account.tabs.size());}
            WorkspaceState.Tab one=account.tabs.get(0);one.url=ORIGIN+"one";one.title="旧页一";one.customTitle=true;first.set(one.id);
            WorkspaceState.Tab two=priorFixture==null?state.addTab(account):account.tabs.get(1);two.url=ORIGIN+"two";two.title="旧页二";two.customTitle=true;second.set(two.id);account.activeTab=one.id;state.activeAccount=account.id;});
        WorkspaceState.Account created=prepared.current();JSONObject profile=new JSONObject().put("schema",1).put("accountId",created.id).put("storageId",created.storageId).put("profile",WorkspaceState.profile(created.storageId));
        java.nio.file.Files.write(EnvironmentUpgradeSeedTest.file("browser-chrome-profile.json").toPath(),profile.toString().getBytes(StandardCharsets.UTF_8));
        MainActivity.handoffFixture=this::response;
        try{
            phase("initial-load-one");activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));loaded("one");
            if(!timingCase.isEmpty())waitFor("declared timing fixture restoration ready",()->{JSONObject r=nativeEvidence().getJSONObject("recovery");return r.getBoolean("liveReady")&&!r.getBoolean("restoring")&&!r.getBoolean("inflight");});
            click("更多功能");AccessibilityNodeInfo back=control("后退"),forward=control("前进");assertFalse(back.isEnabled());assertFalse(forward.isEnabled());back.recycle();forward.recycle();click("关闭弹层");
            String original=snapshot(web()).getString("document");String draft="OwnedLegacyDraft";type(draft);
            phase("switch-two");click("查看标签页：旧页一，2 个");click("标签页：旧页二，账号：网页测试");loaded("two");
            phase("switch-one");click("查看标签页：旧页二，2 个");click("标签页：旧页一，账号：网页测试");loaded("one");assertEquals(original,snapshot(web()).getString("document"));assertEquals(1,firstLoads.get());assertEquals(1,secondLoads.get());assertDraft(draft);
            phase("link-next");tapWeb("next");loaded("next");
            if(timingCase.equals("fixed-early-poll")){phase("early-poll-back-refusal");earlyPollRefusesBack("OwnedTimingDraft");phase("complete");saveNavigationEvidence("none");return;}
            phase("first-back-one");click("更多功能");click("后退");loaded("one");assertDraft(draft);
            phase("forward-next");click("更多功能");click("前进");loaded("next");
            phase("second-back-one");click("更多功能");click("后退");loaded("one");assertDraft(draft);
            phase("normal-refresh");String before=snapshot(web()).getString("document");click("刷新页面");waitFor("normal refresh new document",()->!before.equals(snapshot(web()).getString("document")));loaded("one");assertDraft(draft);
            phase("force-refresh");String normal=snapshot(web()).getString("document");if(!timingCase.isEmpty())armTiming();click("更多功能");click("强制刷新");if(timingProbe!=null)latePollDuringSave(draft);waitFor("force refresh new document",()->!normal.equals(snapshot(web()).getString("document")));loaded("one");assertDraft(draft);
            assertEquals(0,unexpected.get());JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("api",api).put("engine","webview").put("nativeHeaderTitleSwitch",true).put("switchNoReload",true).put("disabledEmptyHistory",true).put("backForward",true).put("normalAndForceRefresh",true).put("draftCookieStoragePreserved",true).put("allRequestsIntercepted",true).put("unexpectedRequests",unexpected.get()).put("screenshots",false).put("realServicesAccessed",false);
            JSONObject observed=EnvironmentUpgradeSeedTest.uiRegistryBarrier(EnvironmentUpgradeSeedTest.expected());proof.put("profileRegistryPersisted",true).put("registryBarrierMillis",observed.getLong("registryBarrierMillis")).put("firstObservedProfileNames",observed.get("firstObservedProfileNames")).put("profileRegistryBarrier",observed);
            Bundle output=new Bundle();output.putString("browserChromeProof",proof.toString());instrumentation.addResults(output);
            if(timingProof!=null){assertEquals(0,timingEvents("recovery-cancelled"));assertEquals(1,timingEvents("action-approved"));assertTrue(timingEvents("page-start:one")>=1);long approved=timingEventTime("action-approved"),started=timingEventTime("page-start:one");assertTrue("Real save precedes approval",approved>=timingProbe.savedMillis);assertTrue("Approval precedes navigation",started>=approved);timingProof.put("complete",true).put("expectedFailure",false).put("originalNavigationAssertions",true).put("savedBeforeNavigation",true).put("actionApprovedMillis",approved).put("pageStartedMillis",started);writeTimingProof();}
            phase("complete");saveNavigationEvidence("none");
        }catch(AssertionError|Exception failure){
            if(timingProof!=null)try{
                if(timingCase.equals("baseline-late-poll")&&failure instanceof AssertionError&&"Timed out: force refresh new document".equals(failure.getMessage())){
                    int cancelled=timingEvents("recovery-cancelled"),approved=timingEvents("action-approved"),started=timingEvents("page-start:one");
                    timingProof.put("recoveryCancelCount",cancelled).put("actionApprovedCount",approved).put("pageStartCount",started);
                    if(cancelled==1&&approved==0&&started==0)timingProof.put("complete",true).put("expectedFailure",true).put("originalNewDocumentAssertionFailed",true);
                }
                timingProof.put("failedPhase",phase).put("failureClass",failure.getClass().getSimpleName());writeTimingProof();
            }catch(Exception evidenceError){android.util.Log.e("BrowserChromeAcceptance","Cannot preserve timing verdict",evidenceError);}
            if(activity!=null)saveNavigationEvidence(failure instanceof AssertionError?"assertion":"exception");throw failure;
        }finally{if(timingProbe!=null)timingProbe.releaseSave.countDown();if(activity!=null)main(activity::finish);MainActivity.handoffFixture=null;}
    }
}
