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
    private static final String ORIGIN="https://chatgpt.com/c/owned-ui-";
    private interface Check { boolean ready()throws Exception; }
    private void main(Runnable run){instrumentation.runOnMainSync(run);}
    private void waitFor(String label,Check check)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+30000;
        while(!check.ready()){if(android.os.SystemClock.elapsedRealtime()>deadline)throw new AssertionError("Timed out: "+label);Thread.sleep(150);}
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
    private void type(String draft)throws Exception{
        WebView view=web();tapWeb("prompt-textarea");waitFor("real composer focus",()->snapshot(view).getBoolean("focused"));
        AtomicReference<InputConnection> connection=new AtomicReference<>();waitFor("native text connection",()->{main(()->{EditorInfo editor=new EditorInfo();InputConnection input=view.onCreateInputConnection(editor);connection.set((editor.inputType&15)==1?input:null);});return connection.get()!=null;});
        main(()->assertTrue(connection.get().commitText(draft,1)));waitFor("trusted DOM and model text",()->{JSONObject value=snapshot(view);return draft.equals(value.getString("draft"))&&draft.equals(value.getString("model"))&&value.getInt("trusted")>0;});
        main(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0));
    }
    private WebResourceResponse response(String account,android.webkit.WebResourceRequest request){
        String url=request.getUrl().toString();String page=null;
        if(url.equals(ORIGIN+"one")){firstLoads.incrementAndGet();page="旧页一";}else if(url.equals(ORIGIN+"two")){secondLoads.incrementAndGet();page="旧页二";}else if(url.equals(ORIGIN+"next")){nextLoads.incrementAndGet();page="后续页";}
        if(page==null){if(!url.endsWith("/favicon.ico"))unexpected.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        String body="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>"+page+"</title><main><article data-message-author-role='assistant' data-message-id='owned-message'>仅合成网页，无真实服务</article><p><a id='next' href='"+ORIGIN+"next'>进入下一页</a></p><textarea id='prompt-textarea' rows='3' style='width:90%;font-size:18px' aria-label='合成旧网页编辑器'></textarea></main><script>window.ownedDocument=crypto.randomUUID();window.ownedModel='';window.ownedTrusted=0;if(!localStorage.getItem('owned-ui'))localStorage.setItem('owned-ui','preserved');if(!document.cookie.includes('owned-ui='))document.cookie='owned-ui=preserved;Path=/;Secure;SameSite=Lax';document.getElementById('prompt-textarea').addEventListener('input',e=>{window.ownedModel=e.target.value;if(e.isTrusted)window.ownedTrusted++;});</script>";
        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    private void assertDraft(String expected)throws Exception{waitFor("protected legacy draft",()->{JSONObject v=snapshot(web());return expected.equals(v.getString("draft"))&&expected.equals(v.getString("model"));});JSONObject v=snapshot(web());assertTrue(v.getString("cookie").contains("owned-ui=preserved"));assertEquals("preserved",v.getString("storage"));}
    @Test public void realHeaderAndNavigationPreserveLegacyPage()throws Exception{
        android.content.Context context=instrumentation.getTargetContext();Class<?> config=Class.forName("com.forcemind.chatgpt.profiles.BuildConfig",false,context.getClassLoader());assertTrue(config.getField("ENVIRONMENT_TEST").getBoolean(null));
        int api=EnvironmentUpgradeSeedTest.verifyIdentity(EnvironmentUpgradeSeedTest.NEW_CODE);AtomicReference<String> first=new AtomicReference<>(),second=new AtomicReference<>();
        new StateStore(context.getFilesDir()).update(state->{WorkspaceState.Account account=state.addAccount("网页测试","",WorkspaceState.COLORS[0]);WorkspaceState.Tab one=account.current();one.url=ORIGIN+"one";one.title="旧页一";one.customTitle=true;first.set(one.id);WorkspaceState.Tab two=state.addTab(account);two.url=ORIGIN+"two";two.title="旧页二";two.customTitle=true;second.set(two.id);account.activeTab=one.id;state.activeAccount=account.id;});
        MainActivity.handoffFixture=this::response;
        try{
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));loaded("one");
            click("更多功能");AccessibilityNodeInfo back=control("后退"),forward=control("前进");assertFalse(back.isEnabled());assertFalse(forward.isEnabled());back.recycle();forward.recycle();click("关闭弹层");
            String original=snapshot(web()).getString("document");String draft="OwnedLegacyDraft";type(draft);
            click("切换标签页：旧页二");loaded("two");click("切换标签页：旧页一");loaded("one");assertEquals(original,snapshot(web()).getString("document"));assertEquals(1,firstLoads.get());assertEquals(1,secondLoads.get());assertDraft(draft);
            tapWeb("next");loaded("next");click("更多功能");click("后退");loaded("one");assertDraft(draft);click("更多功能");click("前进");loaded("next");click("更多功能");click("后退");loaded("one");assertDraft(draft);
            String before=snapshot(web()).getString("document");click("刷新页面");waitFor("normal refresh new document",()->!before.equals(snapshot(web()).getString("document")));loaded("one");assertDraft(draft);
            String normal=snapshot(web()).getString("document");click("更多功能");click("强制刷新");waitFor("force refresh new document",()->!normal.equals(snapshot(web()).getString("document")));loaded("one");assertDraft(draft);
            assertEquals(0,unexpected.get());JSONObject proof=new JSONObject().put("schema",1).put("complete",true).put("api",api).put("engine","webview").put("nativeHeaderTitleSwitch",true).put("switchNoReload",true).put("disabledEmptyHistory",true).put("backForward",true).put("normalAndForceRefresh",true).put("draftCookieStoragePreserved",true).put("allRequestsIntercepted",true).put("unexpectedRequests",unexpected.get()).put("screenshots",false).put("realServicesAccessed",false);
            Bundle output=new Bundle();output.putString("browserChromeProof",proof.toString());instrumentation.addResults(output);
        }finally{if(activity!=null)main(activity::finish);MainActivity.handoffFixture=null;}
    }
}
