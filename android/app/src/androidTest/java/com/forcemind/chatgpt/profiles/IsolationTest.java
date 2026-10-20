package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IsolationTest {
    private void ui(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private String js(WebView web, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        ui(() -> web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("JavaScript callback timeout", done.await(15, TimeUnit.SECONDS)); return result.get();
    }
    private WebView fixture(String profile) throws Exception {
        AtomicReference<WebView> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        ui(() -> {
            WebView web = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext()); WebViewCompat.setProfile(web, profile);
            web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream("<!doctype html><title>Local isolation fixture - not ChatGPT</title>".getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView v, String url) { done.countDown(); }
            }); result.set(web); web.loadUrl("https://chatgpt.com/__profiles_fixture__");
        });
        assertTrue("Fixture load timeout", done.await(20, TimeUnit.SECONDS)); return result.get();
    }
    private void await(WebView web, String expression, String expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do { if (expected.equals(js(web, expression))) return; Thread.sleep(50); } while (System.nanoTime() < end);
        fail("Fixture condition failed: " + expression);
    }
    @Test public void profileCookiesLocalStorageIndexedDbAndCacheAreIsolated() throws Exception {
        AtomicReference<Boolean> supported = new AtomicReference<>(); ui(() -> supported.set(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)));
        assertTrue("Device WebView must support MULTI_PROFILE for isolation acceptance", supported.get());
        String first = "test-" + UUID.randomUUID(), second = "test-" + UUID.randomUUID();
        WebView a = fixture(first), b = fixture(second), shared = fixture(first);
        try {
            js(a, "document.cookie='profile=A;Path=/;Secure;SameSite=Lax';localStorage.setItem('profile','A');window.done=false;(async()=>{let db=await new Promise((ok,fail)=>{let r=indexedDB.open('isolation',1);r.onupgradeneeded=()=>r.result.createObjectStore('data');r.onsuccess=()=>ok(r.result);r.onerror=fail});await new Promise(ok=>{let t=db.transaction('data','readwrite');t.objectStore('data').put('A','profile');t.oncomplete=ok});db.close();let c=await caches.open('isolation');await c.put('/marker',new Response('A'));window.done=true})()");
            await(a, "window.done", "true");
            assertEquals("\"A\"", js(shared, "localStorage.getItem('profile')")); assertTrue(js(shared, "document.cookie").contains("profile=A"));
            assertEquals("null", js(b, "localStorage.getItem('profile')")); assertFalse(js(b, "document.cookie").contains("profile=A"));
            js(b, "window.done=false;(async()=>{let list=await indexedDB.databases();let cache=await caches.has('isolation');window.result=JSON.stringify({db:list.some(d=>d.name==='isolation'),cache});window.done=true})()");
            await(b, "window.done", "true");
            String payload = new org.json.JSONTokener(js(b, "window.result")).nextValue().toString();
            JSONObject separate = new JSONObject(payload); assertFalse(separate.getBoolean("db")); assertFalse(separate.getBoolean("cache"));
            ui(a::destroy); a = null;
            WebView reopened = fixture(first);
            try { assertEquals("\"A\"", js(reopened, "localStorage.getItem('profile')")); } finally { ui(reopened::destroy); }
        } finally { if (a != null) { WebView finalA = a; ui(finalA::destroy); } ui(b::destroy); ui(shared::destroy); }
    }
    @Test public void revokedProfilesCannotReturnThroughBackupRecovery() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = Files.createTempDirectory(context.getCacheDir().toPath(), "state-test-").toFile();
        StateStore store = new StateStore(dir); WorkspaceState initial = store.load();
        String old = initial.current().storageId; initial.addAccount("工作", "", WorkspaceState.COLORS[1]); store.save(initial);
        String other = initial.current().storageId;
        store.revoke(old); WorkspaceState cleared = initial.copy(); cleared.clearAccount(cleared.accounts.get(0)); store.save(cleared);
        // Corrupt the current file so recovery uses the pre-clear backup.
        Files.write(new File(dir, "workspace.json").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        WorkspaceState recovered = store.load(); assertNotEquals(old, recovered.accounts.get(0).storageId);
        assertEquals(other, recovered.current().storageId); assertTrue(recovered.pendingDeletes.contains(old));
        assertTrue(java.util.Arrays.stream(dir.list()).anyMatch(name -> name.startsWith("workspace.corrupt-")));
    }
}
