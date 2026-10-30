package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** Owned CI pages only. No screenshots, account data, foreground service, or timer keepalive. */
@RunWith(AndroidJUnit4.class)
public final class NetworkStabilityInstrumentedTest {
    private final Bundle args = InstrumentationRegistry.getArguments();
    private final JSONArray expectedRequests = new JSONArray();
    private final JSONArray checkpoints = new JSONArray();
    private final List<WebView> tabs = new ArrayList<>();
    private Context context;
    private String origin;
    private RouteManager routes;
    private MainActivity activity;
    private long backgroundMillis;
    private void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private void write(String name, JSONObject value) throws Exception {
        File temp = new File(context.getFilesDir(), name + ".tmp");
        Files.write(temp.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue("atomic evidence publication", temp.renameTo(new File(context.getFilesDir(), name)));
    }
    private void checkpoint(String name) throws Exception {
        checkpoints.put(new JSONObject().put("name", name).put("elapsedRealtime", SystemClock.elapsedRealtime()));
        write("stability-progress.json", new JSONObject().put("checkpoints", checkpoints).put("complete", false));
    }
    private void host(String phase) throws Exception {
        write("stability-phase.json", new JSONObject().put("phase", phase).put("pid", android.os.Process.myPid())
                .put("elapsedRealtime", SystemClock.elapsedRealtime()));
        File ack = new File(context.getFilesDir(), "stability-ack");
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ack.exists() && phase.equals(new String(Files.readAllBytes(ack.toPath()), StandardCharsets.UTF_8))) return;
            Thread.sleep(250);
        }
        fail("Host did not acknowledge " + phase);
    }
    private void expect(String path, String method, String route) throws Exception {
        expectedRequests.put(new JSONObject().put("path", path).put("method", method).put("route", route));
    }
    private void route(VlessNode node) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        main(() -> routes.switchTo(node, (ok, message) -> { if (!ok) failure.set(message); done.countDown(); }));
        assertTrue("route callback", done.await(35, TimeUnit.SECONDS)); assertNull(failure.get());
        assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state);
    }
    private String js(WebView web, String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        main(() -> web.evaluateJavascript(code, result -> { value.set(result); done.countDown(); }));
        assertTrue("JS callback", done.await(15, TimeUnit.SECONDS)); return value.get();
    }
    private void await(WebView web, String expression, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 25000; String value;
        do { value = js(web, expression); if (expected.equals(value)) return; Thread.sleep(100); }
        while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(expected, value);
    }
    private void select(int index) {
        main(() -> { for (int i = 0; i < tabs.size(); i++) tabs.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE); });
    }
    private void preserved() throws Exception {
        for (int i = 0; i < tabs.size(); i++) {
            select(i);
            assertEquals(JSONObject.quote("draft-" + i), js(tabs.get(i), "document.getElementById('draft').value"));
            assertEquals(JSONObject.quote("identity-" + i), js(tabs.get(i), "window.__identity"));
            assertEquals("\"A\"", js(tabs.get(i), "document.title"));
            assertEquals("1", js(tabs.get(i), "window.__submitAttempts"));
        }
    }
    private void webUpload(WebView web, String id, String route) throws Exception {
        String path = "/stability/" + id + "/upload";
        js(web, "window.__result='pending';fetch(" + JSONObject.quote(origin + path)
                + ",{method:'POST',body:" + JSONObject.quote(id) + ",cache:'no-store'}).then(async r=>{window.__result=r.headers.get('X-Fixture-Route')+':'+await r.text()}).catch(()=>window.__result='error');true");
        await(web, "window.__result", JSONObject.quote(route + ":" + id)); expect(path, "POST", route);
    }
    private void nativeFile(String id, String route) throws Exception {
        String path = "/stability/" + id + "/download";
        CountDownLatch done = new CountDownLatch(1); AtomicReference<File> file = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        main(() -> activity.downloadsForTests().download(origin + path, "owned-stability-ci", (output, message) -> { file.set(output); error.set(message); done.countDown(); }));
        assertTrue("download completed", done.await(30, TimeUnit.SECONDS)); assertNull(error.get()); assertNotNull(file.get());
        assertArrayEquals("network-poc-fixture\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.get().toPath()));
        assertTrue(file.get().delete()); expect(path, "GET", route);
    }
    private void nativeUpload(String id, String route) throws Exception {
        String path = "/stability/" + id + "/upload";
        byte[] body = id.getBytes(StandardCharsets.UTF_8);
        NativeDownloads.Response response = activity.downloadsForTests().request(origin + path, "POST", body);
        assertEquals(route, response.route); assertArrayEquals(body, response.bytes); expect(path, "POST", route);
    }
    private void failedRequests(String id) throws Exception {
        try {
            activity.downloadsForTests().request(origin + "/stability/" + id + "-native/upload", "POST", id.getBytes(StandardCharsets.UTF_8));
            fail("unavailable proxy unexpectedly delivered native POST");
        } catch (java.io.IOException expected) { }
        WebView web = tabs.get(0);
        js(web, "window.__failure='pending';(()=>{const c=new AbortController();const timer=setTimeout(()=>c.abort(),8000);fetch("
                + JSONObject.quote(origin + "/stability/" + id + "-web/upload")
                + ",{method:'POST',body:'must-not-arrive',signal:c.signal,cache:'no-store'}).then(()=>window.__failure='unexpected-response').catch(()=>window.__failure='error').finally(()=>clearTimeout(timer))})();true");
        await(web, "window.__failure", "\"error\"");
        assertEquals("native transfer lease released", 0, routes.activeTransfers());
    }
    private boolean listenerAlive(int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); return true; }
        catch (Exception absent) { return false; }
    }
    @Test public void syntheticMultiTabBackgroundAndFailureRecovery() throws Exception {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertEquals("http://10.0.2.2:18080/page", BuildConfig.NETWORK_HOME_URL);
        assertEquals("1800", args.getString("backgroundSeconds"));
        origin = args.getString("fixtureUrl"); assertEquals("https://network-fixture.test:18480", origin);
        String security = args.getString("expectedSecurity"); assertTrue("tls".equals(security) || "reality".equals(security));
        VlessNode a = VlessNode.parse(args.getString("nodeA"), "A"), b = VlessNode.parse(args.getString("nodeB"), "B");
        assertEquals(security, a.security); assertEquals(security, b.security);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new NodeStore(context).write(new NodeStore.Data());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> reference = new AtomicReference<>(); scenario.onActivity(reference::set); activity = reference.get(); routes = activity.routeManagerForTests();
            assertTrue(RouteManager.supportsWebViewProxy());
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (routes.snapshot().state == RouteManager.State.CONNECTING && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertEquals(RouteManager.State.DIRECT, routes.snapshot().state);
            // Complete initial WebView/network startup before probing the core.
            await(activity.webViewForTests(), "document.title", "\"direct\"");
            assertEquals("direct", activity.downloadsForTests().request("https://10.0.2.2:18480/echo", "GET", null).route);
            route(a);
            main(() -> {
                tabs.add(activity.webViewForTests());
                ViewGroup parent = (ViewGroup) tabs.get(0).getParent();
                for (int i = 1; i < 3; i++) {
                    WebView tab = new WebView(activity); tab.getSettings().setJavaScriptEnabled(true); tab.getSettings().setDomStorageEnabled(true);
                    tab.getSettings().setAllowFileAccess(false); tab.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                    tab.setWebViewClient(new WebViewClient()); tab.setVisibility(View.GONE); parent.addView(tab, new android.widget.LinearLayout.LayoutParams(-1, 0, 1)); tabs.add(tab);
                }
            });
            try {
                for (int i = 0; i < 3; i++) {
                    select(i); WebView tab = tabs.get(i); String path = "/stability/tab-" + i + "/page";
                    main(() -> tab.loadUrl(origin + path)); await(tab, "document.title", "\"A\"");
                    await(tab, "document.readyState", "\"complete\""); expect(path, "GET", "A");
                    js(tab, "document.getElementById('draft').value='draft-" + i + "';window.__identity='identity-" + i + "';window.__submitAttempts=1;true");
                }
                route(b);
                for (int cycle = 0; cycle < 3; cycle++) preserved();
                for (int i = 0; i < 3; i++) { select(i); webUpload(tabs.get(i), "web-tab-" + i, "B"); }
                nativeUpload("before-background", "B"); nativeFile("before-background", "B");
                checkpoint("three-tabs-retained-route-B");
                // Host presses Home and verifies ActivityManager before acknowledging.
                host("background-start"); assertEquals(Lifecycle.State.CREATED, scenario.getState());
                long started = SystemClock.elapsedRealtime();
                while (SystemClock.elapsedRealtime() - started < 1800000L) {
                    Thread.sleep(Math.max(1, Math.min(10000, 1800000L - (SystemClock.elapsedRealtime() - started))));
                    assertEquals("activity remains stopped", Lifecycle.State.CREATED, scenario.getState());
                    assertEquals(0, routes.activeTransfers());
                }
                backgroundMillis = SystemClock.elapsedRealtime() - started; assertTrue(backgroundMillis >= 1800000L);
                host("background-end");
                assertEquals(Lifecycle.State.RESUMED, scenario.getState());
                scenario.onActivity(current -> assertSame("same Activity after returning from Home", activity, current));
                assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state); preserved();
                nativeUpload("after-background", "B"); nativeFile("after-background", "B"); webUpload(tabs.get(0), "web-after-background", "B");
                checkpoint("thirty-minute-background-restored");
                host("outage-start"); failedRequests("outage"); preserved();
                host("outage-end"); route(b); preserved();
                nativeUpload("after-outage", "B"); nativeFile("after-outage", "B"); webUpload(tabs.get(0), "web-after-outage", "B");
                checkpoint("network-recovered-explicitly-no-replay");
                int stoppedPort = routes.snapshot().port;
                Networkcore.stop(); assertFalse("core listener really stopped", listenerAlive(stoppedPort));
                failedRequests("core-stopped"); preserved(); Thread.sleep(3000);
                assertFalse("no automatic core restart", listenerAlive(stoppedPort));
                String stateAfterStop = routes.snapshot().state.name();
                route(a); preserved();
                nativeUpload("after-core-restart", "A"); nativeFile("after-core-restart", "A"); webUpload(tabs.get(0), "web-after-core-restart", "A");
                checkpoint("core-recovered-explicitly-no-replay");
                write("stability-result.json", new JSONObject().put("complete", true).put("security", security)
                        .put("pid", android.os.Process.myPid()).put("tabCount", 3).put("backgroundMillis", backgroundMillis)
                        .put("coreStopInjection", "unexpected binding stop outside RouteManager; not process crash")
                        .put("stateAfterExternalCoreStop", stateAfterStop).put("automaticRestart", false)
                        .put("expectedRequests", expectedRequests).put("checkpoints", checkpoints));
                host("complete"); System.out.println("NETWORK_STABILITY_RESULT complete=true tabs=3 backgroundMillis=" + backgroundMillis + " security=" + security);
            } finally {
                main(() -> { for (int i = 1; i < tabs.size(); i++) { WebView tab = tabs.get(i); ((ViewGroup) tab.getParent()).removeView(tab); tab.destroy(); } });
            }
        }
    }
}
