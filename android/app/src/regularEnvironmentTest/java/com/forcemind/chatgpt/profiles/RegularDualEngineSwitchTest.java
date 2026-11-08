package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final JSONArray menuTransitions=new JSONArray();
    private final JSONArray nativeClicks=new JSONArray();
    private MainActivity activity;
    private String legacyId,storageId,tabId,url,legacyName,environmentName,expectedGeckoDraft;
    private JSONObject nativeScrollReceipt,nativeScrollSnapshot;
    private JSONObject nativeScrollPreparation;
    private String nativeScrollImeDump="";
    private static final String SCROLL_IME_FILE="regular-dual-engine-scroll-ime.txt";
    private String nativeScrollOutput="",nativeScrollUid="";
    private String phase="setup";
    private String lastMenuImeDump="";
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
    private JSONObject workspaceSelection()throws Exception{
        WorkspaceState state=new StateStore(RegularEnvironmentUpgradeTest.context().getFilesDir()).load();WorkspaceState.Account current=state.current();
        return new JSONObject().put("activeAccount",state.activeAccount).put("activeTab",current.activeTab).put("accountName",current.name).put("storageId",current.storageId).put("engine",current.engine);
    }
    private void semanticTree(AccessibilityNodeInfo node,StringBuilder text,int depth){
        if(node==null){text.append("no active accessibility root\n");return;}if(depth>30||text.length()>65536)return;
        Rect box=new Rect();node.getBoundsInScreen(box);
        text.append("depth=").append(depth).append(" window=").append(node.getWindowId()).append(" package=").append(node.getPackageName()).append(" class=").append(node.getClassName())
            .append(" text=").append(node.getText()).append(" description=").append(node.getContentDescription()).append(" visible=").append(node.isVisibleToUser()).append(" enabled=").append(node.isEnabled()).append(" bounds=").append(box).append('\n');
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);try{semanticTree(child,text,depth+1);}finally{if(child!=null)child.recycle();}}
    }
    private String semanticHierarchy(){
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();StringBuilder text=new StringBuilder();
        try{if(root!=null)text.append("activeRootRefresh=").append(root.refresh()).append('\n');semanticTree(root,text,0);return text.toString();}finally{if(root!=null)root.recycle();}
    }
    private JSONArray bounds(Rect box){return new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom);}
    private void rejectNative(JSONObject receipt,String reason)throws Exception{receipt.put("lastRejection",reason).put("lastRejectionAt",System.currentTimeMillis());}
    private JSONObject nativeGeometry(String label,JSONObject receipt)throws Exception{
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow(),target=null;AccessibilityWindowInfo window=null;
        receipt.put("windowInfo",JSONObject.NULL).put("observedRootWindowId",JSONObject.NULL).put("observedTargetWindowId",JSONObject.NULL).put("observedRootBounds",JSONObject.NULL).put("observedTargetBounds",JSONObject.NULL);
        try{
            if(root==null){rejectNative(receipt,"active-root-missing");return null;}
            // WindowInfo is a diagnostic observation only. The authoritative active
            // UI root and its refreshed target below determine action readiness.
            try{window=root.getWindow();if(window!=null){Rect area=new Rect();window.getBoundsInScreen(area);receipt.put("windowInfo",new JSONObject().put("id",window.getId()).put("active",window.isActive()).put("focused",window.isFocused()).put("bounds",bounds(area)));}}
            catch(Exception observationError){receipt.put("windowInfoError",observationError.toString());}
            if(!root.refresh()){rejectNative(receipt,"active-root-refresh-failed");return null;}
            receipt.put("observedRootWindowId",root.getWindowId());
            if(!RegularEnvironmentUpgradeTest.PACKAGE.contentEquals(root.getPackageName()==null?"":root.getPackageName())){rejectNative(receipt,"active-root-package-mismatch");return null;}
            target=find(root,label);if(target==null){rejectNative(receipt,"target-label-missing");return null;}
            if(!target.refresh()){rejectNative(receipt,"target-refresh-failed");return null;}
            receipt.put("observedTargetWindowId",target.getWindowId());
            if(!label.contentEquals(target.getText()==null?"":target.getText())&&!label.contentEquals(target.getContentDescription()==null?"":target.getContentDescription())){rejectNative(receipt,"refreshed-target-label-changed");return null;}
            if(!target.isVisibleToUser()){rejectNative(receipt,"target-not-visible");return null;}
            if(!target.isEnabled()){rejectNative(receipt,"target-disabled");return null;}
            if(!RegularEnvironmentUpgradeTest.PACKAGE.contentEquals(target.getPackageName()==null?"":target.getPackageName())){rejectNative(receipt,"target-package-mismatch");return null;}
            if(target.getWindowId()!=root.getWindowId()){rejectNative(receipt,"target-active-root-window-id-mismatch");return null;}
            Rect rootBox=new Rect(),box=new Rect();root.getBoundsInScreen(rootBox);target.getBoundsInScreen(box);
            receipt.put("observedRootBounds",bounds(rootBox)).put("observedTargetBounds",bounds(box));
            if(rootBox.width()<=0||rootBox.height()<=0){rejectNative(receipt,"active-root-bounds-empty");return null;}
            if(box.width()<=0||box.height()<=0){rejectNative(receipt,"target-bounds-empty");return null;}
            if(!rootBox.contains(box)){rejectNative(receipt,"target-bounds-outside-active-root");return null;}
            return new JSONObject().put("windowId",root.getWindowId()).put("rootBounds",bounds(rootBox)).put("bounds",bounds(box));
        }finally{if(target!=null)target.recycle();if(window!=null)window.recycle();if(root!=null)root.recycle();}
    }
    private boolean menuImeHidden(JSONObject receipt,JSONObject transition)throws Exception{
        lastMenuImeDump=shell("dumpsys input_method");
        Matcher visibility=Pattern.compile("\\bmInputShown\\s*=\\s*(true|false)\\b").matcher(lastMenuImeDump);
        if(!visibility.find()){rejectNative(receipt,"current-system-ime-field-missing");throw new AssertionError("Current system IME visibility required");}
        boolean shown=Boolean.parseBoolean(visibility.group(1));
        if(visibility.find()){rejectNative(receipt,"current-system-ime-field-ambiguous");throw new AssertionError("Current system IME visibility must be unambiguous");}
        receipt.put("lastImeInputShown",shown);transition.put("lastImeInputShown",shown);
        if(shown){rejectNative(receipt,"current-system-ime-shown");return false;}
        receipt.put("imeHiddenObserved",true);transition.put("imeHiddenObserved",true);return true;
    }
    private boolean scrollImeHidden()throws Exception{
        try{return menuImeHidden(nativeScrollPreparation,nativeScrollPreparation);}
        finally{nativeScrollImeDump=lastMenuImeDump;}
    }
    private void saveScrollImeDump()throws Exception{
        if(nativeScrollPreparation==null||nativeScrollImeDump.isEmpty())return;
        byte[] original=nativeScrollImeDump.getBytes(StandardCharsets.UTF_8);
        Files.write(RegularEnvironmentUpgradeTest.file(SCROLL_IME_FILE).toPath(),original);
        nativeScrollPreparation.put("imeDumpFile",SCROLL_IME_FILE).put("imeDumpEncoding","UTF-8").put("imeDumpSHA256",RegularEnvironmentUpgradeTest.hash(original)).put("imeDumpBytes",original.length);
    }
    private void stableNativeTap(String label,JSONObject transition,boolean requireImeHidden)throws Exception{
        final long deadline=android.os.SystemClock.elapsedRealtime()+45000;
        JSONObject receipt=new JSONObject().put("label",label).put("startedAt",System.currentTimeMillis()).put("requireImeHidden",requireImeHidden).put("tapCount",0).put("samples",0).put("lastRejection","none");
        if(transition!=null)transition.put("nativeClickIndex",nativeClicks.length()).put("nativeClick",receipt).put("imeHiddenObserved",false).put("selectionTapCount",0).put("samples",0);
        nativeClicks.put(receipt);AtomicReference<String> previousSignature=new AtomicReference<>();AtomicReference<JSONObject> selected=new AtomicReference<>();AtomicInteger stable=new AtomicInteger();
        waitFor("stable refreshed native target "+label,()->{
            if(android.os.SystemClock.elapsedRealtime()>=deadline){receipt.put("deadlineExceeded",true);throw new AssertionError("Native click stays within the original 45s deadline: "+label);}
            JSONObject sample=null;
            if(!requireImeHidden||menuImeHidden(receipt,transition))sample=nativeGeometry(label,receipt);
            if(sample==null){stable.set(0);previousSignature.set(null);receipt.put("samples",0);if(transition!=null)transition.put("samples",0);return false;}
            String signature=sample.toString();int count=signature.equals(previousSignature.get())?stable.incrementAndGet():1;
            stable.set(count);previousSignature.set(signature);receipt.put("windowId",sample.getInt("windowId")).put("rootBounds",sample.getJSONArray("rootBounds")).put("bounds",sample.getJSONArray("bounds")).put("samples",count);
            if(transition!=null)transition.put("stableWindowId",sample.getInt("windowId")).put("rootBounds",sample.getJSONArray("rootBounds")).put("bounds",sample.getJSONArray("bounds")).put("samples",count);
            if(count<3){rejectNative(receipt,"awaiting-three-stable-geometry-samples");return false;}
            if(requireImeHidden&&!menuImeHidden(receipt,transition)){stable.set(0);previousSignature.set(null);receipt.put("samples",0);transition.put("samples",0);return false;}
            JSONObject last=nativeGeometry(label,receipt);
            if(last==null||!signature.equals(last.toString())){
                if(last!=null)rejectNative(receipt,"final-refreshed-geometry-changed");
                stable.set(0);previousSignature.set(null);receipt.put("samples",0);if(transition!=null)transition.put("samples",0);return false;
            }
            selected.set(last);receipt.put("finalRefreshMatched",true);return android.os.SystemClock.elapsedRealtime()<deadline;
        });
        if(android.os.SystemClock.elapsedRealtime()>=deadline){receipt.put("deadlineExceeded",true);throw new AssertionError("Single native tap stays within the original deadline: "+label);}
        JSONArray box=selected.get().getJSONArray("bounds");receipt.put("tapCount",1).put("tapStartedAt",System.currentTimeMillis());if(transition!=null)transition.put("selectionTapCount",1);
        shell("input tap "+((box.getInt(0)+box.getInt(2))/2)+" "+((box.getInt(1)+box.getInt(3))/2));receipt.put("tapReturnedAt",System.currentTimeMillis());
    }
    private void click(String label)throws Exception{stableNativeTap(label,null,false);}
    private void selectStableAccount(String label,JSONObject transition)throws Exception{stableNativeTap(label,transition,true);}
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
    private JSONObject readingGeometry(WebView web)throws Exception{
        int[] at=new int[2],size=new int[2];Rect visible=new Rect();AtomicReference<Boolean> ready=new AtomicReference<>(false);
        main(()->{web.getLocationOnScreen(at);size[0]=web.getWidth();size[1]=web.getHeight();ready.set(web.isShown()&&web.hasWindowFocus()&&web.isAttachedToWindow()&&!web.isLayoutRequested()&&size[0]>0&&size[1]>200&&web.getGlobalVisibleRect(visible)&&visible.equals(new Rect(at[0],at[1],at[0]+size[0],at[1]+size[1])));});
        nativeScrollPreparation.put("lastNativeBounds",new JSONArray().put(at[0]).put(at[1]).put(at[0]+size[0]).put(at[1]+size[1])).put("lastGlobalVisibleBounds",bounds(visible));
        if(!ready.get()){nativeScrollPreparation.put("lastRejection","native-viewport-not-fully-visible-or-layout-pending");return null;}
        AtomicReference<String> raw=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        main(()->web.evaluateJavascript("JSON.stringify((()=>{let r=document.querySelector('article').getBoundingClientRect(),c=document.getElementById('composer').getBoundingClientRect(),v=visualViewport;return {dpr:devicePixelRatio,innerWidth:innerWidth,innerHeight:innerHeight,visualWidth:v?v.width:innerWidth,visualHeight:v?v.height:innerHeight,visualOffsetLeft:v?v.offsetLeft:0,visualOffsetTop:v?v.offsetTop:0,visualScale:v?v.scale:1,readingBounds:[Math.max(0,r.left),Math.max(0,r.top),Math.min(innerWidth,r.right),Math.min(innerHeight,r.bottom,c.top)]};})())",value->{raw.set(value);done.countDown();}));
        assertTrue("Read-only reading viewport observation",done.await(5,TimeUnit.SECONDS));Object decoded=new JSONTokener(raw.get()).nextValue();
        if(decoded==JSONObject.NULL){nativeScrollPreparation.put("lastRejection","DOM-viewport-not-ready");return null;}
        JSONObject viewport=new JSONObject(String.valueOf(decoded));nativeScrollPreparation.put("lastDOMViewport",viewport);double dpr=viewport.getDouble("dpr");JSONArray reading=viewport.getJSONArray("readingBounds");
        if(!Double.isFinite(dpr)||dpr<=0||viewport.getDouble("innerWidth")<=0||viewport.getDouble("innerHeight")<=0||viewport.getDouble("visualWidth")<=0||viewport.getDouble("visualHeight")<=0){nativeScrollPreparation.put("lastRejection","DOM-viewport-size-invalid");return null;}
        int x=at[0]+size[0]/2,start=at[1]+size[1]/2,end=at[1]+size[1]/5;
        double cssX=(x-at[0])/dpr,cssStart=(start-at[1])/dpr,cssEnd=(end-at[1])/dpr;
        if(cssX<reading.getDouble(0)||cssX>=reading.getDouble(2)||cssStart<reading.getDouble(1)||cssStart>=reading.getDouble(3)||cssEnd<reading.getDouble(1)||cssEnd>=reading.getDouble(3)){
            nativeScrollPreparation.put("lastRejection","original-gesture-coordinates-outside-visible-reading-area");return null;
        }
        return new JSONObject().put("nativeBounds",new JSONArray().put(at[0]).put(at[1]).put(at[0]+size[0]).put(at[1]+size[1])).put("globalVisibleBounds",bounds(visible)).put("viewport",viewport).put("coords",new JSONArray().put(x).put(start).put(x).put(end).put(420));
    }
    private void nativeScroll(WebView web)throws Exception{
        final long preparationDeadline=android.os.SystemClock.elapsedRealtime()+45000;
        nativeScrollPreparation=new JSONObject().put("samples",0).put("imeHiddenObserved",false).put("gestureCount",0);
        AtomicReference<String> previousGeometry=new AtomicReference<>();AtomicReference<JSONObject> selectedGeometry=new AtomicReference<>();AtomicInteger preparedSamples=new AtomicInteger();
        waitFor("legacy WebView layout",()->{
            assertTrue("Scroll preparation stays within the original 45s deadline",android.os.SystemClock.elapsedRealtime()<preparationDeadline);
            boolean hidden=scrollImeHidden();
            JSONObject geometry=hidden?readingGeometry(web):null;
            if(geometry==null){preparedSamples.set(0);previousGeometry.set(null);nativeScrollPreparation.put("samples",0);return false;}
            nativeScrollPreparation.put("lastGeometry",geometry);String signature=geometry.toString();int count=signature.equals(previousGeometry.get())?preparedSamples.incrementAndGet():1;
            preparedSamples.set(count);previousGeometry.set(signature);nativeScrollPreparation.put("samples",count);
            if(count<3){nativeScrollPreparation.put("lastRejection","awaiting-three-stable-reading-viewport-samples");return false;}
            hidden=scrollImeHidden();JSONObject last=hidden?readingGeometry(web):null;
            if(last==null||!signature.equals(last.toString())){preparedSamples.set(0);previousGeometry.set(null);nativeScrollPreparation.put("samples",0).put("lastRejection","IME-or-reading-viewport-changed-at-final-refresh");return false;}
            selectedGeometry.set(last);nativeScrollPreparation.put("geometry",last).put("finalRefreshMatched",true).put("measuredAt",System.currentTimeMillis());return android.os.SystemClock.elapsedRealtime()<preparationDeadline;
        });
        JSONArray coords=selectedGeometry.get().getJSONArray("coords");int x=coords.getInt(0),start=coords.getInt(1),end=coords.getInt(3);
        nativeScrollSnapshot=snapshot(web);nativeScrollPreparation.put("beforeGestureSnapshot",nativeScrollSnapshot);
        if(android.os.Build.VERSION.SDK_INT==28){
            nativeScrollUid=shell("id -u").trim();
            assertTrue("Only root or shell may invoke the owned Android9 touch helper",nativeScrollUid.equals("0")||nativeScrollUid.equals("2000"));
            String command=(nativeScrollUid.equals("0")?"su 2000 ":"")+"env CLASSPATH=/data/local/tmp/studio-poc-input.jar app_process /system/bin com.forcemind.chatgpt.pocinput.TouchInput "+x+" "+start+" "+x+" "+end+" 420";
            nativeScrollPreparation.put("gestureCount",1).put("gestureStartedAt",System.currentTimeMillis());nativeScrollOutput=shell(command);
            JSONTokener tokens=new JSONTokener(nativeScrollOutput);Object decoded=tokens.nextValue();
            assertTrue("Touch helper must emit one JSON object only",decoded instanceof JSONObject&&tokens.nextClean()==0);
            nativeScrollReceipt=(JSONObject)decoded;
            assertEquals(2,nativeScrollReceipt.length());assertEquals("ci-android9-shell-touchscreen",nativeScrollReceipt.getString("provider"));
            JSONArray events=nativeScrollReceipt.getJSONArray("events");assertEquals(33,events.length());
            long down=events.getJSONObject(0).getLong("downTime"),previousTime=-1;assertTrue(down>=0);
            for(int index=0;index<events.length();index++){
                JSONObject event=events.getJSONObject(index);assertEquals(5,event.length());
                assertEquals(index==0?0:index==32?1:2,event.getInt("action"));
                assertEquals(1,event.getInt("toolType"));assertEquals(4098,event.getInt("source"));assertEquals(down,event.getLong("downTime"));
                long time=event.getLong("eventTime");assertTrue("Monotonic touchscreen event times",time>=down&&time>previousTime);previousTime=time;
            }
        }else{
            nativeScrollPreparation.put("gestureCount",1).put("gestureStartedAt",System.currentTimeMillis());shell("input swipe "+x+" "+start+" "+x+" "+end+" 420");
        }
        nativeScrollPreparation.put("gestureReturnedAt",System.currentTimeMillis());
        AtomicReference<Double> previous=new AtomicReference<>(-1.0);AtomicInteger stable=new AtomicInteger();
        waitFor("settled real native reading scroll",()->{nativeScrollSnapshot=snapshot(web);double value=nativeScrollSnapshot.optDouble("scroll");stable.set(value>100&&Math.abs(value-previous.get())<=1?stable.get()+1:0);previous.set(value);return stable.get()>=3;});
    }
    private void menu(String currentName,String targetName)throws Exception{
        JSONObject transition=new JSONObject().put("from",currentName).put("to",targetName).put("startedAt",System.currentTimeMillis()).put("workspaceBefore",workspaceSelection());menuTransitions.put(transition);
        phase="menu-open: "+currentName+" -> "+targetName;transition.put("stage","opening-menu");click("切换账号，当前："+currentName);
        phase="menu-select: "+currentName+" -> "+targetName;transition.put("stage","selecting-account");selectStableAccount(targetName,transition);transition.put("selectionTapReturnedAt",System.currentTimeMillis()).put("workspaceAfterTap",workspaceSelection());
        phase="menu-wait-header: "+targetName;transition.put("stage","waiting-target-header");waitFor("native account header "+targetName,()->visible("切换账号，当前："+targetName));transition.put("stage","target-header-visible").put("headerVisibleAt",System.currentTimeMillis());
    }
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
            phase="legacy-native-draft";nativeDraft(original);phase="legacy-native-scroll";nativeScroll(original);JSONObject before=snapshot(original);
            AtomicReference<String> profile=new AtomicReference<>(),cookies=new AtomicReference<>();AtomicReference<Boolean> cookieOk=new AtomicReference<>();CountDownLatch cookieDone=new CountDownLatch(1);
            main(()->WebViewCompat.getProfile(original).getCookieManager().setCookie(url,"owned_dual_auth=preserved;Path=/;Secure;HttpOnly;SameSite=Lax;Max-Age=86400",ok->{cookieOk.set(ok);cookieDone.countDown();}));assertTrue(cookieDone.await(10,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,cookieOk.get());
            main(()->{profile.set(WebViewCompat.getProfile(original).getName());WebViewCompat.getProfile(original).getCookieManager().flush();cookies.set(WebViewCompat.getProfile(original).getCookieManager().getCookie(url));});
            assertEquals(WorkspaceState.profile(storageId),profile.get());assertTrue(cookies.get().contains("owned_dual_auth=preserved"));assertTrue(cookies.get().contains("owned_dual_visible=preserved"));
            for(int index=0;index<2;index++){
                JSONObject round=new JSONObject().put("round",index+1).put("toGeckoStartedAt",System.currentTimeMillis()).put("stage","main-to-gecko");rounds.put(round);
                menu(legacyName,environmentName);phase="round-"+(index+1)+"-gecko-recovery";round.put("stage","gecko-recovery");geckoReady(index==0);round.put("geckoReadyAt",System.currentTimeMillis()).put("expectedGeckoDraftSHA256",RegularEnvironmentUpgradeTest.hash(expectedGeckoDraft.getBytes(StandardCharsets.UTF_8)));
                round.put("stage","gecko-to-main");menu(environmentName,legacyName);phase="round-"+(index+1)+"-assert-legacy";round.put("stage","asserting-legacy");assertLegacy(original,before,profile.get(),cookies.get());
                round.put("stage","complete").put("mainReturnedAt",System.currentTimeMillis()).put("sameMainActivity",true).put("sameWebViewObject",true).put("sameDocument",true).put("sameDraftAndModel",true).put("sameScroll",true).put("sameProfileAndLogin",true);
            }
            RegularEnvironmentUpgradeTest.verifyNative(golden,true);assertEquals(expectedHash,RegularEnvironmentUpgradeTest.hash(RegularEnvironmentUpgradeTest.file(RegularEnvironmentUpgradeTest.EXPECTED)));
            saveScrollImeDump();
            JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("api",api).put("package",RegularEnvironmentUpgradeTest.PACKAGE).put("versionCode",RegularEnvironmentUpgradeTest.NEW_CODE).put("signerSHA256",RegularEnvironmentUpgradeTest.signer())
                .put("scope","separate-synthetic-legacy-Main-account; original-alpha22-golden-account-unopened").put("newInstrumentationMayColdStartGecko",true).put("geckoNoReloadRequiresExternalServerProof",true)
                .put("originalBaselineAccountUnopened",true).put("originalBaselineAndTwoCipherRecordsUnchanged",true).put("originalExpectedSHA256",expectedHash)
                .put("syntheticLegacyAccountId",legacyId).put("syntheticLegacyStorageId",storageId).put("syntheticLegacyTabId",tabId).put("syntheticLegacyProfile",profile.get()).put("document",before.getString("document"))
                .put("legacyDraftSHA256",RegularEnvironmentUpgradeTest.hash(MAIN_DRAFT.getBytes(StandardCharsets.UTF_8))).put("legacyScroll",before.getDouble("scroll")).put("legacyPageLoads",loads.get()).put("unexpectedLegacyRequests",unexpected.get()).put("roundTrips",rounds).put("menuTransitions",menuTransitions).put("nativeClicks",nativeClicks)
                .put("nativeScrollReceipt",nativeScrollReceipt==null?JSONObject.NULL:nativeScrollReceipt)
                .put("nativeScrollPreparation",nativeScrollPreparation)
                .put("nativeAccountMenusUsed",true).put("trustedNativeDraftInput",true).put("nativeReadingScroll",true).put("screenshots",false).put("realServicesAccessed",false).put("productionSignatureVerified",false);
            write("regular-dual-engine-switch.json",proof);Bundle output=new Bundle();output.putString("regularDualEngineSwitchProof",proof.toString());instrumentation.addResults(output);
        }catch(Exception|AssertionError failure){
            try{saveScrollImeDump();}catch(Exception dumpError){failure.addSuppressed(dumpError);}
            try{
                java.io.File error=RegularEnvironmentUpgradeTest.file("regular-dual-engine-switch-first-error.txt");
                if(!error.exists()){
                    JSONObject evidence=new JSONObject().put("phase",phase).put("partialRoundTrips",rounds).put("menuTransitions",menuTransitions).put("partialNativeClicks",nativeClicks).put("lastMenuImeDump",lastMenuImeDump).put("nativeScrollUid",nativeScrollUid).put("nativeScrollOutput",nativeScrollOutput).put("nativeScrollReceipt",nativeScrollReceipt==null?JSONObject.NULL:nativeScrollReceipt).put("nativeScrollPreparation",nativeScrollPreparation==null?JSONObject.NULL:nativeScrollPreparation).put("lastScrollSnapshot",nativeScrollSnapshot==null?JSONObject.NULL:nativeScrollSnapshot);
                    try{evidence.put("currentWorkspace",workspaceSelection());}catch(Exception stateError){evidence.put("currentWorkspaceError",stateError.toString());}
                    try{evidence.put("uiHierarchy",semanticHierarchy());}catch(Exception treeError){evidence.put("uiHierarchyError",treeError.toString());}
                    try{if(activity!=null&&current()!=null)evidence.put("failureSnapshot",snapshot(current()));}catch(Exception snapshotError){evidence.put("failureSnapshotError",snapshotError.toString());}
                    Files.write(error.toPath(),(android.util.Log.getStackTraceString(failure)+"\n"+evidence.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                }
            }catch(Exception logError){failure.addSuppressed(logError);}throw failure;
        }finally{if(activity!=null){main(activity::finish);instrumentation.waitForIdleSync();}MainActivity.handoffFixture=null;}
    }
}
