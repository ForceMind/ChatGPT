package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** CI-only synthetic services. Never receives user node credentials or navigates the real product. */
@RunWith(AndroidJUnit4.class)
public final class NetworkPocInstrumentedTest {
    private static final byte[] FILE = "network-poc-fixture\n".getBytes(StandardCharsets.UTF_8);
    private final Bundle args = InstrumentationRegistry.getArguments();
    @Test public void controlledInFlightTransfersFailClosedWithoutReplay() throws Exception {
        new TransferFaultCases(args).run();
    }
    private String fixture() { return args.getString("fixtureUrl", "http://network-fixture.test:18080"); }
    private VlessNode node(String suffix) {
        VlessNode result = VlessNode.parse(args.getString("node" + suffix, "vless://00000000-0000-4000-8000-000000000001@10.0.2.2:" + ("A".equals(suffix) ? "18443" : "18444") + "?encryption=none&type=tcp&security=none"), suffix);
        assertEquals("fixture protocol must match requested test", args.getString("expectedSecurity", "none"), result.security);
        return result;
    }
    private void main(Runnable runnable) { InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable); }
    private void switchRoute(RouteManager routes, VlessNode node) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        main(() -> routes.switchTo(node, (success, message) -> { if (!success) failure.set(message); done.countDown(); }));
        assertTrue("route callback", done.await(35, TimeUnit.SECONDS)); assertNull(failure.get());
        assertEquals(node == null ? RouteManager.State.DIRECT : RouteManager.State.CONNECTED, routes.snapshot().state);
    }
    private String js(MainActivity activity, String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        main(() -> activity.webViewForTests().evaluateJavascript(code, result -> { value.set(result); done.countDown(); }));
        assertTrue(done.await(10, TimeUnit.SECONDS)); return value.get();
    }
    private void awaitJs(MainActivity activity, String expression, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); String actual;
        do { actual = js(activity, expression); if (expected.equals(actual)) return; Thread.sleep(150); } while (System.nanoTime() < deadline);
        if (BuildConfig.APPLICATION_ID.endsWith(".ci")) try {
            System.out.println("NETWORK_POC_JS_TIMEOUT " + js(activity, "JSON.stringify({url:location.href.slice(0,256),title:document.title.slice(0,120),ready:document.readyState,body:(document.body?.innerText||'').slice(0,200)})"));
        } catch (Exception diagnosticFailure) { System.out.println("NETWORK_POC_JS_TIMEOUT diagnostic=" + diagnosticFailure.getClass().getSimpleName()); }
        assertEquals(expected, actual);
    }
    @Test public void encryptedStoreRoundTripAndTamperDoesNotOverwrite() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NodeStore store = new NodeStore(context); NodeStore.Data original = store.read();
        try {
            NodeStore.Data data = new NodeStore.Data(); data.nodes.add(node("A")); data.selected = 0; store.write(data);
            java.io.File file = new java.io.File(context.getNoBackupFilesDir(), "network-nodes.enc");
            byte[] encrypted = java.nio.file.Files.readAllBytes(file.toPath());
            assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains(data.nodes.get(0).id));
            assertEquals("A", store.read().nodes.get(0).name); assertEquals(0, store.read().selected);
            java.io.File backup = new java.io.File(file.getPath() + ".bak");
            assertTrue(file.renameTo(backup)); assertEquals("A", store.read().nodes.get(0).name);
            assertTrue(file.exists()); assertFalse(backup.exists());
            encrypted[encrypted.length - 1] ^= 1; java.nio.file.Files.write(file.toPath(), encrypted);
            try { store.read(); fail("tampered ciphertext accepted"); } catch (javax.crypto.AEADBadTagException expected) { }
            assertArrayEquals(encrypted, java.nio.file.Files.readAllBytes(file.toPath()));
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci"));
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                AtomicReference<MainActivity> ref = new AtomicReference<>(); scenario.onActivity(ref::set); MainActivity activity = ref.get();
                assertEquals(RouteManager.State.CONNECTING, activity.routeManagerForTests().snapshot().state);
                main(() -> { assertNull(activity.webViewForTests().getUrl()); assertTrue(activity.webViewForTests().getSettings().getBlockNetworkLoads()); });
                // An explicit direct choice unlocks navigation without overwriting unreadable credentials.
                main(() -> activity.changeRoute(null, -1));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (activity.routeManagerForTests().snapshot().state == RouteManager.State.CONNECTING && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals(RouteManager.State.DIRECT, activity.routeManagerForTests().snapshot().state);
                main(() -> { assertFalse(activity.webViewForTests().getSettings().getBlockNetworkLoads()); assertEquals(BuildConfig.NETWORK_HOME_URL, activity.webViewForTests().getUrl()); });
                assertArrayEquals(encrypted, java.nio.file.Files.readAllBytes(file.toPath()));
            }
        } finally { store.write(original); }
    }
    @Test public void syntheticRoutingFilesUploadAndWebViewPreservation() throws Exception {
        assertTrue("This test requires the isolated CI flavor", BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertFalse(BuildConfig.NETWORK_HOME_URL.contains("chatgpt.com"));
        // Avoid restoring a previous test selection. This is the CI app's own encrypted store only.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new NodeStore(context).write(new NodeStore.Data());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> ref = new AtomicReference<>(); scenario.onActivity(ref::set); MainActivity activity = ref.get();
            RouteManager routes = activity.routeManagerForTests();
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (routes.snapshot().state == RouteManager.State.CONNECTING && System.nanoTime() < waitUntil) Thread.sleep(50);
            assertEquals(RouteManager.State.DIRECT, routes.snapshot().state);
            if (android.os.Build.VERSION.SDK_INT >= 35 || "true".equals(args.getString("requireWebViewProxy")))
                assertTrue("Full WebView proxy verification required", RouteManager.supportsWebViewProxy());
            if (!RouteManager.supportsWebViewProxy()) {
                CountDownLatch rejected = new CountDownLatch(1);
                main(() -> routes.switchTo(node("A"), (success, message) -> { assertFalse(success); rejected.countDown(); }));
                assertTrue(rejected.await(5, TimeUnit.SECONDS)); assertEquals(RouteManager.State.DIRECT, routes.snapshot().state);
                nativeCoreOnly(node("A"), "A"); nativeCoreOnly(node("B"), "B");
                System.out.println("NETWORK_POC_RESULT native=A,B WebView=UNSUPPORTED_EXPLICITLY_REJECTED"); return;
            }
            switchRoute(routes, node("A"));
            assertEquals("A", activity.downloadsForTests().request(fixture() + "/echo", "GET", null).route);
            assertArrayEquals(FILE, activity.downloadsForTests().request(fixture() + "/download", "GET", null).bytes);
            assertNativeDownload(activity);
            for (String key : new String[]{"invalidNode", "invalidNode2"}) {
              String invalid = args.getString(key);
              if (invalid != null) {
                CountDownLatch denied = new CountDownLatch(1);
                main(() -> routes.switchTo(VlessNode.parse(invalid, "invalid-identity"), (success, message) -> { assertFalse("invalid secure identity accepted", success); denied.countDown(); }));
                assertTrue("secure identity rejection", denied.await(35, TimeUnit.SECONDS));
                assertEquals(RouteManager.State.FAILED, routes.snapshot().state);
                try { activity.downloadsForTests().request(fixture() + "/echo", "GET", null); fail("secure failure fell back"); }
                catch (IllegalStateException expected) { }
                switchRoute(routes, node("A"));
              }
            }
            byte[] upload = "synthetic-upload-A".getBytes(StandardCharsets.UTF_8);
            NativeDownloads.Response sent = activity.downloadsForTests().request(fixture() + "/upload", "POST", upload);
            assertEquals("A", sent.route); assertArrayEquals(upload, sent.bytes);
            main(() -> activity.webViewForTests().loadUrl(fixture() + "/page")); awaitJs(activity, "document.title", "\"A\"");
            js(activity, "document.getElementById('draft').value='keep-draft';window.__sent=1;true");
            RouteManager.Snapshot held = routes.beginTransfer(); assertEquals("A", held.node.name);
            CountDownLatch blocked = new CountDownLatch(1);
            main(() -> routes.switchTo(node("B"), (success, message) -> { assertFalse(success); blocked.countDown(); }));
            assertTrue(blocked.await(5, TimeUnit.SECONDS)); assertEquals("A", routes.snapshot().node.name); routes.endTransfer();
            switchRoute(routes, node("B"));
            assertEquals("\"A\"", js(activity, "document.title")); assertEquals("\"keep-draft\"", js(activity, "document.getElementById('draft').value"));
            assertEquals("1", js(activity, "window.__sent"));
            js(activity, "fetch('/echo').then(r=>{window.__route=r.headers.get('X-Fixture-Route')});true"); awaitJs(activity, "window.__route", "\"B\"");
            js(activity, "fetch('/upload',{method:'POST',body:'web-upload-B'}).then(async r=>{window.__uploadRoute=r.headers.get('X-Fixture-Route');window.__upload=await r.text()});true");
            awaitJs(activity, "window.__upload", "\"web-upload-B\""); assertEquals("\"B\"", js(activity, "window.__uploadRoute"));
            js(activity, "(()=>{const form=new FormData();form.append('file',new File(['owned-file-bytes'],'ci.txt',{type:'text/plain'}));fetch('/upload',{method:'POST',body:form}).then(async r=>{const body=await r.text();window.__fileUploadOk=r.headers.get('X-Fixture-Route')==='B'&&body.includes('filename=\"ci.txt\"')&&body.includes('owned-file-bytes')})})();true");
            awaitJs(activity, "window.__fileUploadOk", "true");
            assertEquals("B", activity.downloadsForTests().request(fixture() + "/echo", "GET", null).route);
            assertArrayEquals(FILE, activity.downloadsForTests().request(fixture() + "/download", "GET", null).bytes);
            assertNativeDownload(activity);
            // A failed candidate must never become direct or replay the existing document.
            VlessNode unavailable = VlessNode.parse("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:1?type=tcp", "bad");
            CountDownLatch failed = new CountDownLatch(1);
            main(() -> routes.switchTo(unavailable, (success, message) -> { assertFalse(success); failed.countDown(); }));
            assertTrue(failed.await(35, TimeUnit.SECONDS)); assertEquals(RouteManager.State.FAILED, routes.snapshot().state);
            try { activity.downloadsForTests().request(fixture() + "/echo", "GET", null); fail("failed route fell back"); } catch (IllegalStateException expected) { }
            assertEquals("\"keep-draft\"", js(activity, "document.getElementById('draft').value")); assertEquals("1", js(activity, "window.__sent"));
            switchRoute(routes, null); assertEquals(Proxy.NO_PROXY, routes.snapshot().proxy());
            String directFixture = args.getString("directFixtureUrl", "http://10.0.2.2:18080");
            assertEquals("direct", activity.downloadsForTests().request(directFixture + "/echo", "GET", null).route);
            main(() -> activity.webViewForTests().loadUrl(directFixture + "/page")); awaitJs(activity, "document.title", "\"direct\"");
            System.out.println("NETWORK_POC_RESULT native=A,B WebView=A,B draft=PRESERVED upload=B failure=FAIL_CLOSED direct=EXPLICIT security=" + args.getString("expectedSecurity", "none") + " origin=" + fixture());
        }
    }
    private void assertNativeDownload(MainActivity activity) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<java.io.File> output = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        main(() -> activity.downloadsForTests().download(fixture() + "/download", "network-poc-ci", (file, message) -> { output.set(file); error.set(message); done.countDown(); }));
        assertTrue(done.await(30, TimeUnit.SECONDS)); assertNull(error.get()); assertNotNull(output.get());
        assertArrayEquals(FILE, java.nio.file.Files.readAllBytes(output.get().toPath())); assertTrue(output.get().delete());
    }
    private void waitState(RouteManager routes, RouteManager.State expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 40000;
        while (routes.snapshot().state != expected && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertEquals("production route state", expected, routes.snapshot().state);
    }
    private JSONObject document(MainActivity activity) throws Exception {
        return new JSONObject(js(activity, "({url:location.href,identity:window.__healthIdentity,draft:document.getElementById('draft').value,fetchStarts:window.__healthFetchStarts,submissions:window.__healthSubmissions})"));
    }
    private JSONObject preservedDocument(MainActivity activity, JSONObject original, int fetchStarts) throws Exception {
        JSONObject actual = document(activity);
        for (String key : new String[]{"url", "identity", "draft", "submissions"}) assertEquals("retained " + key, original.get(key), actual.get(key));
        assertEquals("no automatic WebView replay", fetchStarts, actual.getInt("fetchStarts"));
        return actual;
    }
    private JSONObject observation(MainActivity activity) throws Exception {
        AtomicReference<String> status = new AtomicReference<>(); AtomicReference<Boolean> blocked = new AtomicReference<>();
        main(() -> { status.set(activity.networkStatusForTests()); blocked.set(activity.webViewForTests().getSettings().getBlockNetworkLoads()); });
        RouteManager routes = activity.routeManagerForTests(); RouteManager.Snapshot snapshot = routes.snapshot();
        return new JSONObject().put("state", snapshot.state.name()).put("generation", snapshot.generation)
                .put("port", snapshot.port).put("coreRunning", Networkcore.isRunning()).put("visibleStatus", status.get())
                .put("webNetworkBlocked", blocked.get()).put("error", routes.error());
    }
    private JSONObject failedObservation(MainActivity activity) throws Exception {
        JSONObject actual = observation(activity);
        assertEquals("FAILED", actual.getString("state")); assertFalse(actual.getBoolean("coreRunning"));
        assertTrue(actual.getBoolean("webNetworkBlocked")); assertFalse(actual.getString("visibleStatus").contains("已连接"));
        assertTrue("visible failure state", actual.getString("visibleStatus").contains("失败"));
        String error = actual.getString("error");
        assertTrue("manual recovery guidance", error.contains("手动") && (error.contains("重新连接") || error.contains("重连")) && error.contains("直连"));
        return actual;
    }
    private void systemCommand(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024]; while (output.read(buffer) >= 0) { }
        }
    }
    private void lifecycle(ActivityScenario<MainActivity> scenario, Lifecycle.State expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (scenario.getState() != expected && SystemClock.elapsedRealtime() < deadline) Thread.sleep(75);
        assertEquals("actual system lifecycle", expected, scenario.getState());
    }
    private boolean listenerAlive(int port) {
        try (java.net.Socket socket = new java.net.Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); return true; }
        catch (java.io.IOException absent) { return false; }
    }
    @Test public void unexpectedCoreStopFailsClosedAndRequiresExplicitRecovery() throws Exception {
        assertTrue("isolated synthetic package only", BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertEquals("http://10.0.2.2:18080/page", BuildConfig.NETWORK_HOME_URL);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NodeStore.Data data = new NodeStore.Data(); data.nodes.add(node("A")); data.nodes.add(node("B")); data.selected = -1;
        new NodeStore(context).write(data);
        JSONObject evidence = new JSONObject().put("schema", 1).put("security", args.getString("expectedSecurity", "none"))
                .put("origin", fixture()).put("test", "unexpectedCoreStopFailsClosedAndRequiresExplicitRecovery");
        JSONArray expectedRequests = new JSONArray(); JSONObject documents = new JSONObject();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> reference = new AtomicReference<>(); scenario.onActivity(reference::set);
            MainActivity activity = reference.get(); RouteManager routes = activity.routeManagerForTests();
            waitState(routes, RouteManager.State.DIRECT); assertTrue(RouteManager.supportsWebViewProxy());
            awaitJs(activity, "document.title", "\"direct\"");
            main(() -> activity.changeRoute(node("A"), 0)); waitState(routes, RouteManager.State.CONNECTED);
            assertTrue("real core is running", Networkcore.isRunning());
            assertEquals("A", activity.downloadsForTests().request(fixture() + "/stability/core-health-before/echo", "GET", null).route);
            expectedRequests.put(new JSONObject().put("path", "/stability/core-health-before/echo").put("method", "GET").put("route", "A"));
            main(() -> activity.webViewForTests().loadUrl(fixture() + "/stability/core-health/page"));
            awaitJs(activity, "document.title", "\"A\""); awaitJs(activity, "document.readyState", "\"complete\"");
            expectedRequests.put(new JSONObject().put("path", "/stability/core-health/page").put("method", "GET").put("route", "A"));
            js(activity, "document.getElementById('draft').value='core-health-unsent-draft';window.__healthIdentity='owned-'+Date.now()+'-'+Math.random();window.__healthSubmissions=0;window.__healthFetchStarts=0;document.addEventListener('submit',()=>window.__healthSubmissions++);const originalFetch=window.fetch;window.fetch=function(...args){window.__healthFetchStarts++;return originalFetch.apply(this,args)};true");
            JSONObject original = document(activity); documents.put("before", original);
            JSONObject before = observation(activity); assertEquals("CONNECTED", before.getString("state"));
            assertTrue(before.getString("visibleStatus").contains("已连接")); assertFalse(before.getBoolean("webNetworkBlocked"));
            evidence.put("before", before);
            long stoppedAt = SystemClock.elapsedRealtime(); int stoppedPort = routes.snapshot().port;
            Networkcore.stop(); assertFalse("actual Go binding stopped", Networkcore.isRunning());
            assertFalse("actual local listener stopped", listenerAlive(stoppedPort));
            // No requests or recheckCoreState test calls occur before automatic FAILED.
            waitState(routes, RouteManager.State.FAILED);
            long detectedAt = SystemClock.elapsedRealtime();
            evidence.put("automaticFailure", failedObservation(activity)).put("externalStopAtMillis", stoppedAt)
                    .put("failureDetectedAtMillis", detectedAt).put("detectionMillis", detectedAt - stoppedAt);
            documents.put("afterAutomaticFailure", preservedDocument(activity, original, 0));
            evidence.put("failedRequestsStartedAtMillis", SystemClock.elapsedRealtime());
            String nativeFailure = null;
            try { activity.downloadsForTests().request(fixture() + "/stability/core-health-failed-native/upload", "POST", FILE); fail("core failure fell back to a native route"); }
            catch (IllegalStateException expected) { nativeFailure = expected.getClass().getSimpleName(); }
            js(activity, "window.__healthBlocked='pending';fetch('/stability/core-health-failed-web/upload',{method:'POST',body:'must-not-arrive',cache:'no-store'}).then(()=>window.__healthBlocked='unexpected-response').catch(()=>window.__healthBlocked='error');true");
            awaitJs(activity, "window.__healthBlocked", "\"error\"");
            assertEquals(0, routes.activeTransfers());
            long failedGeneration = routes.snapshot().generation;
            for (int i = 0; i < 30; i++) { Thread.sleep(100); assertFalse(Networkcore.isRunning()); assertEquals(RouteManager.State.FAILED, routes.snapshot().state); assertEquals(failedGeneration, routes.snapshot().generation); }
            documents.put("afterBlockedRequests", preservedDocument(activity, original, 1));
            evidence.put("nativeFailure", nativeFailure).put("webFailure", new org.json.JSONTokener(js(activity, "window.__healthBlocked")).nextValue())
                    .put("activeTransfersAfterFailure", routes.activeTransfers());
            main(() -> activity.changeRoute(node("A"), 0)); waitState(routes, RouteManager.State.CONNECTED);
            assertTrue(Networkcore.isRunning()); assertEquals("\"error\"", js(activity, "window.__healthBlocked"));
            documents.put("afterExplicitReconnect", preservedDocument(activity, original, 1));
            evidence.put("reconnected", observation(activity));
            assertEquals("A", activity.downloadsForTests().request(fixture() + "/stability/core-health-reconnected-native/echo", "GET", null).route);
            expectedRequests.put(new JSONObject().put("path", "/stability/core-health-reconnected-native/echo").put("method", "GET").put("route", "A"));
            js(activity, "fetch('/stability/core-health-reconnected-web/upload',{method:'POST',body:'explicit-new-request',cache:'no-store'}).then(async r=>window.__healthRecovered=r.headers.get('X-Fixture-Route')+':'+await r.text());true");
            awaitJs(activity, "window.__healthRecovered", "\"A:explicit-new-request\"");
            expectedRequests.put(new JSONObject().put("path", "/stability/core-health-reconnected-web/upload").put("method", "POST").put("route", "A"));
            long homeStarted = SystemClock.elapsedRealtime();
            systemCommand("input keyevent KEYCODE_HOME"); lifecycle(scenario, Lifecycle.State.CREATED);
            Thread.sleep(700); Lifecycle.State stoppedLifecycle = scenario.getState(); assertEquals(Lifecycle.State.CREATED, stoppedLifecycle);
            Networkcore.stop(); assertFalse(Networkcore.isRunning());
            String returnCommand = "am start -W -n " + BuildConfig.APPLICATION_ID + "/com.forcemind.chatgpt.networkpreview.MainActivity";
            systemCommand(returnCommand); lifecycle(scenario, Lifecycle.State.RESUMED);
            AtomicReference<Boolean> sameActivity = new AtomicReference<>(); scenario.onActivity(current -> sameActivity.set(current == activity));
            assertTrue("real Home return must reuse original Activity", sameActivity.get());
            waitState(routes, RouteManager.State.FAILED);
            documents.put("afterHomeResumeFailure", preservedDocument(activity, original, 2));
            evidence.put("background", new JSONObject().put("homeCommand", "input keyevent KEYCODE_HOME").put("returnCommand", returnCommand)
                    .put("stoppedLifecycle", stoppedLifecycle.name()).put("resumedLifecycle", scenario.getState().name()).put("sameActivity", sameActivity.get())
                    .put("elapsedMillis", SystemClock.elapsedRealtime() - homeStarted).put("failure", failedObservation(activity)));
            main(() -> activity.changeRoute(null, -1)); waitState(routes, RouteManager.State.DIRECT);
            assertFalse(Networkcore.isRunning());
            documents.put("afterExplicitDirect", preservedDocument(activity, original, 2)); evidence.put("direct", observation(activity));
            String direct = args.getString("directFixtureUrl", "http://10.0.2.2:18080");
            assertEquals("direct", activity.downloadsForTests().request(direct + "/stability/core-health-direct/echo", "GET", null).route);
            expectedRequests.put(new JSONObject().put("path", "/stability/core-health-direct/echo").put("method", "GET").put("route", "direct"));
            AtomicInteger staleCallbacks = new AtomicInteger(); VlessNode late = node("B");
            main(() -> { routes.switchTo(late, (success, message) -> staleCallbacks.incrementAndGet()); routes.close(); });
            long closedGeneration = routes.snapshot().generation, closeStarted = SystemClock.elapsedRealtime(); int samples = 0;
            while (SystemClock.elapsedRealtime() - closeStarted < 4500) {
                assertEquals(RouteManager.State.CLOSED, routes.snapshot().state); assertEquals(closedGeneration, routes.snapshot().generation);
                samples++; Thread.sleep(75);
            }
            assertFalse("closed route must not restart the core", Networkcore.isRunning()); assertEquals("stale switch callback", 0, staleCallbacks.get());
            evidence.put("closed", new JSONObject().put("state", routes.snapshot().state.name()).put("generation", routes.snapshot().generation)
                    .put("expectedGeneration", closedGeneration).put("observedMillis", SystemClock.elapsedRealtime() - closeStarted)
                    .put("samples", samples).put("callbackInvocations", staleCallbacks.get()).put("coreRunning", Networkcore.isRunning()));
            evidence.put("documents", documents).put("expectedRequests", expectedRequests).put("complete", true);
            java.io.File temporary = new java.io.File(context.getFilesDir(), "core-health-result.json.tmp");
            java.nio.file.Files.write(temporary.toPath(), evidence.toString().getBytes(StandardCharsets.UTF_8));
            assertTrue("atomic core health evidence", temporary.renameTo(new java.io.File(context.getFilesDir(), "core-health-result.json")));
            System.out.println("NETWORK_CORE_HEALTH_RESULT " + evidence.toString());
        }
    }
    private void nativeCoreOnly(VlessNode node, String expected) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        try {
            Networkcore.start(CoreConfig.build(node, port));
            HttpURLConnection connection = (HttpURLConnection) new URL(fixture() + "/download").openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)));
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            try {
                assertEquals(200, connection.getResponseCode()); assertEquals(expected, connection.getHeaderField("X-Fixture-Route"));
                ByteArrayOutputStream output = new ByteArrayOutputStream(); try (InputStream in = connection.getInputStream()) { byte[] buffer = new byte[1024]; int count; while ((count = in.read(buffer)) >= 0) output.write(buffer, 0, count); }
                assertArrayEquals(FILE, output.toByteArray());
            } finally { connection.disconnect(); }
        } finally { Networkcore.stop(); }
    }
}
