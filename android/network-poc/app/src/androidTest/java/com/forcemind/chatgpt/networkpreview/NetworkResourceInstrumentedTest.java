package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic, host-synchronized resource profiles. Run only through measure-resources.py in CI. */
@RunWith(AndroidJUnit4.class)
public final class NetworkResourceInstrumentedTest {
    private static final byte[] FILE = "network-poc-fixture\n".getBytes(StandardCharsets.UTF_8);
    private final Bundle args = InstrumentationRegistry.getArguments();
    private final String DIRECT = args.getString("directFixtureUrl", "http://10.0.2.2:18080");
    private final String PROXY = args.getString("fixtureUrl", "http://network-fixture.test:18080");
    private final String transport = args.getString("transport", "raw");
    private final JSONArray completed = new JSONArray();
    private Context context;
    private int windowSeconds, warmupSeconds;

    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void write(String name, String value) throws Exception {
        File temporary = new File(context.getFilesDir(), name + ".tmp");
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
        assertTrue("atomic phase publication", temporary.renameTo(new File(context.getFilesDir(), name)));
    }
    private void awaitAck(String token) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + (windowSeconds + 120L) * 1000;
        File ack = new File(context.getFilesDir(), "resource-ack");
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ack.exists() && token.equals(new String(Files.readAllBytes(ack.toPath()), StandardCharsets.UTF_8))) return;
            Thread.sleep(500);
        }
        fail("Host sampler did not acknowledge " + token);
    }
    private void switchRoute(MainActivity activity, VlessNode node) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        main(() -> activity.routeManagerForTests().switchTo(node, (success, detail) -> {
            if (!success) error.set(detail); done.countDown();
        }));
        assertTrue("route callback", done.await(35, TimeUnit.SECONDS)); assertNull(error.get());
        assertEquals(node == null ? RouteManager.State.DIRECT : RouteManager.State.CONNECTED,
                activity.routeManagerForTests().snapshot().state);
    }
    private String js(MainActivity activity, String source) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        main(() -> activity.webViewForTests().evaluateJavascript(source, result -> { value.set(result); done.countDown(); }));
        assertTrue("JavaScript callback", done.await(10, TimeUnit.SECONDS)); return value.get();
    }
    private void awaitJs(MainActivity activity, String source, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000; String actual;
        do { actual = js(activity, source); if (expected.equals(actual)) return; Thread.sleep(100); }
        while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(expected, actual);
    }
    private void page(MainActivity activity, String origin, String route) throws Exception {
        NativeDownloads.Response response = activity.downloadsForTests().request(origin + "/echo", "GET", null);
        assertEquals(200, response.status); assertEquals(route, response.route);
        main(() -> activity.webViewForTests().loadUrl(origin + "/page"));
        awaitJs(activity, "document.title", JSONObject.quote(route));
        awaitJs(activity, "document.readyState", "\"complete\"");
    }
    private void phase(MainActivity activity, String name, String route, boolean background, boolean workload) throws Exception {
        Thread.sleep(warmupSeconds * 1000L);
        write("resource-phase.json", new JSONObject().put("phase", name).put("state", "ready")
                .put("route", route).put("background", background).put("pid", android.os.Process.myPid())
                .put("transport", transport)
                .put("windowSeconds", windowSeconds).put("warmupSeconds", warmupSeconds).toString());
        awaitAck(name + ":start");
        long started = SystemClock.elapsedRealtime();
        if (workload) {
            // Exactly 30 cycles spread over the window: disk download, fixed 128 KiB echo,
            // and a WebView fetch. The tiny download fixture is deliberately reported as 20 bytes.
            byte[] upload = new byte[128 * 1024]; Arrays.fill(upload, (byte) 'r');
            for (int i = 0; i < 30; i++) {
                long due = started + i * windowSeconds * 1000L / 30;
                long delay = due - SystemClock.elapsedRealtime(); if (delay > 0) Thread.sleep(delay);
                assertEquals(RouteManager.State.CONNECTED, activity.routeManagerForTests().snapshot().state);
                CountDownLatch done = new CountDownLatch(1); AtomicReference<File> file = new AtomicReference<>();
                AtomicReference<String> error = new AtomicReference<>();
                main(() -> activity.downloadsForTests().download(PROXY + "/download", "network-resource-ci", (output, detail) -> {
                    file.set(output); error.set(detail); done.countDown();
                }));
                assertTrue("native file completed", done.await(25, TimeUnit.SECONDS)); assertNull(error.get()); assertNotNull(file.get());
                assertArrayEquals(FILE, Files.readAllBytes(file.get().toPath())); assertTrue(file.get().delete());
                NativeDownloads.Response echoed = activity.downloadsForTests().request(PROXY + "/upload", "POST", upload);
                assertEquals(200, echoed.status); assertEquals("A", echoed.route); assertArrayEquals(upload, echoed.bytes);
                js(activity, "window.__resourceFetch='pending';fetch('/download',{cache:'no-store'}).then(async r=>{window.__resourceFetch=r.headers.get('X-Fixture-Route')==='A'&&(await r.text())==='network-poc-fixture\\n'?'ok':'bad'}).catch(()=>window.__resourceFetch='error');true");
                awaitJs(activity, "window.__resourceFetch", "\"ok\"");
            }
            assertTrue("fixed workload exceeded measurement window", SystemClock.elapsedRealtime() - started <= windowSeconds * 1000L);
        }
        awaitAck(name + ":done");
        assertTrue("host window was shortened", SystemClock.elapsedRealtime() - started >= (windowSeconds - 2L) * 1000);
        assertEquals("no pending file transfer", 0, activity.routeManagerForTests().activeTransfers());
        assertEquals("A".equals(route) ? RouteManager.State.CONNECTED : RouteManager.State.DIRECT,
                activity.routeManagerForTests().snapshot().state);
        completed.put(new JSONObject().put("phase", name).put("route", route).put("background", background)
                .put("workloadCycles", workload ? 30 : 0).put("nativeFileDownloadBytes", workload ? 30 * FILE.length : 0)
                .put("nativeUploadBytes", workload ? 30 * 128 * 1024 : 0)
                .put("nativeUploadEchoBytes", workload ? 30 * 128 * 1024 : 0)
                .put("webViewDownloadBytes", workload ? 30 * FILE.length : 0)
                .put("durationMillis", SystemClock.elapsedRealtime() - started));
        write("resource-result.json", new JSONObject().put("complete", false).put("phases", completed).toString());
    }
    @Test public void syntheticResourceProfiles() throws Exception {
        assertTrue("Isolated CI app only", BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertEquals("Synthetic homepage only", "http://10.0.2.2:18080/page", BuildConfig.NETWORK_HOME_URL);
        assertTrue(Arrays.asList("http://10.0.2.2:18080", "https://10.0.2.2:18480").contains(DIRECT));
        assertTrue(Arrays.asList("http://network-fixture.test:18080", "https://network-fixture.test:18480").contains(PROXY));
        assertTrue(Arrays.asList("raw", "tls", "reality").contains(transport));
        assertEquals("Host sampler required", "1", args.getString("resourceSampler"));
        windowSeconds = Integer.parseInt(args.getString("resourceWindowSeconds", "60"));
        warmupSeconds = Integer.parseInt(args.getString("resourceWarmupSeconds", "5"));
        assertTrue(windowSeconds >= 60 && windowSeconds <= 120); assertTrue(warmupSeconds >= 3 && warmupSeconds <= 30);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new NodeStore(context).write(new NodeStore.Data());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> ref = new AtomicReference<>(); scenario.onActivity(ref::set); MainActivity activity = ref.get();
            assertTrue("Full WebView proxy support required for resource comparison", RouteManager.supportsWebViewProxy());
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (activity.routeManagerForTests().snapshot().state == RouteManager.State.CONNECTING && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            assertEquals(RouteManager.State.DIRECT, activity.routeManagerForTests().snapshot().state);
            page(activity, DIRECT, "direct");
            phase(activity, "direct_idle_foreground", "direct", false, false);
            VlessNode node = VlessNode.parse(args.getString("nodeA", "vless://00000000-0000-4000-8000-000000000001@10.0.2.2:18443?encryption=none&type=tcp&security=none"), "A");
            assertEquals("resource protocol", "raw".equals(transport) ? "none" : transport, node.security);
            switchRoute(activity, node); page(activity, PROXY, "A");
            phase(activity, "proxy_idle_foreground", "A", false, false);
            phase(activity, "proxy_fixed_workload", "A", false, true);
            scenario.moveToState(Lifecycle.State.CREATED);
            assertEquals(Lifecycle.State.CREATED, scenario.getState());
            phase(activity, "proxy_idle_background", "A", true, false);
            scenario.moveToState(Lifecycle.State.RESUMED);
            switchRoute(activity, null); page(activity, DIRECT, "direct");
            phase(activity, "recovery_direct", "direct", false, false);
        }
        write("resource-result.json", new JSONObject().put("complete", true).put("transport", transport).put("phases", completed).toString());
        write("resource-phase.json", new JSONObject().put("phase", "complete").put("state", "complete").toString());
        System.out.println("NETWORK_RESOURCE_RESULT profiles=5 synthetic=true complete=true");
    }
}
