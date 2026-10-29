package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** CI-only synthetic services. Never receives user node credentials or navigates the real product. */
@RunWith(AndroidJUnit4.class)
public final class NetworkPocInstrumentedTest {
    private static final byte[] FILE = "network-poc-fixture\n".getBytes(StandardCharsets.UTF_8);
    private final Bundle args = InstrumentationRegistry.getArguments();
    private String fixture() { return args.getString("fixtureUrl", "http://network-fixture.test:18080"); }
    private VlessNode node(String suffix) {
        return VlessNode.parse(args.getString("node" + suffix, "vless://00000000-0000-4000-8000-000000000001@10.0.2.2:" + ("A".equals(suffix) ? "18443" : "18444") + "?encryption=none&type=tcp&security=none"), suffix);
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
            if (android.os.Build.VERSION.SDK_INT >= 35) assertTrue("API35 requires full WebView proxy verification", RouteManager.supportsWebViewProxy());
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
            System.out.println("NETWORK_POC_RESULT native=A,B WebView=A,B draft=PRESERVED upload=B failure=FAIL_CLOSED direct=EXPLICIT");
        }
    }
    private void assertNativeDownload(MainActivity activity) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<java.io.File> output = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        main(() -> activity.downloadsForTests().download(fixture() + "/download", "network-poc-ci", (file, message) -> { output.set(file); error.set(message); done.countDown(); }));
        assertTrue(done.await(30, TimeUnit.SECONDS)); assertNull(error.get()); assertNotNull(output.get());
        assertArrayEquals(FILE, java.nio.file.Files.readAllBytes(output.get().toPath())); assertTrue(output.get().delete());
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
