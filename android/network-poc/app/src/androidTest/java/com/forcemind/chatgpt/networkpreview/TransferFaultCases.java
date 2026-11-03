package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** Called only by the fourth CI test. Controls use owned direct HTTP, never a production fallback. */
final class TransferFaultCases {
    private static final String CONTROL = "http://10.0.2.2:18080/transfer-control/";
    private static final String ACK = "network-poc-upload-committed\n";
    private final Bundle args;
    private final JSONArray phases = new JSONArray(), controls = new JSONArray();
    private final List<String> attempted = new ArrayList<>();
    private final List<Download> downloads = new ArrayList<>();
    private final List<NativeDownloads> clients = new ArrayList<>();
    private Context context;
    private MainActivity activity;
    private RouteManager routes;
    private String origin, transport, prefix;
    private JSONObject original, report;
    TransferFaultCases(Bundle args) { this.args = args; }
    private void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private String js(String code) throws Exception {
        AtomicReference<String> result = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        main(() -> activity.webViewForTests().evaluateJavascript(code, value -> { result.set(value); done.countDown(); }));
        assertTrue("local JS callback", done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private JSONObject document() throws Exception {
        return new JSONObject(js("({identity:window.__transferIdentity,draft:document.getElementById('draft').value,url:location.href,submissions:window.__transferSubmissions})"));
    }
    private JSONObject preserved() throws Exception {
        JSONObject actual = document();
        for (String key : new String[]{"identity", "draft", "url", "submissions"}) assertEquals("retained " + key, original.get(key), actual.get(key));
        return actual;
    }
    private void state(RouteManager.State expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (routes.snapshot().state != expected && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertEquals(expected, routes.snapshot().state);
    }
    private void connect() throws Exception {
        VlessNode node = VlessNode.parse(args.getString("nodeA"), "A");
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        main(() -> routes.switchTo(node, (ok, message) -> { if (!ok) failure.set(message); done.countDown(); }));
        assertTrue(done.await(35, TimeUnit.SECONDS)); assertNull(failure.get()); state(RouteManager.State.CONNECTED);
        assertTrue(Networkcore.isRunning());
        if (original != null) preserved();
    }
    private NativeDownloads client() { NativeDownloads value = new NativeDownloads(context, routes); clients.add(value); return value; }
    private String op(String suffix) { String value = prefix + suffix; attempted.add(value); return value; }
    private String business(String op, String kind) { return origin + "/transfer/" + op + "/" + kind; }
    private JSONObject control(String op, boolean release) throws Exception {
        String address = CONTROL + op + (release ? "/release" : "/state");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setUseCaches(false);
        if (release) { connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0); connection.getOutputStream().close(); }
        try {
            int status = connection.getResponseCode();
            controls.put(new JSONObject().put("opid", op).put("method", release ? "POST" : "GET").put("status", status)
                    .put("ownedDirectControl", true));
            if (status == 404) return null;
            assertEquals("owned control response", 200, status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) { byte[] buffer = new byte[2048]; int count; while ((count = stream.read(buffer)) >= 0) { bytes.write(buffer, 0, count); assertTrue(bytes.size() <= 16384); } }
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
    private JSONObject gate(String op, boolean upload) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000; JSONObject value = null;
        do {
            value = control(op, false);
            if (value != null && value.getBoolean(upload ? "bodyComplete" : "prefix")) break;
            Thread.sleep(75);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("real operation reached its server gate", value);
        assertTrue(value.getBoolean(upload ? "bodyComplete" : "prefix"));
        assertEquals(1, value.getInt("opCount")); assertEquals(upload ? 1 : 0, value.getInt("commitCount"));
        assertFalse("response is still gated", value.getBoolean("released")); assertFalse(value.getBoolean("timedOut"));
        return value;
    }
    private JSONObject release(String op) throws Exception {
        assertNotNull(control(op, true)); long deadline = SystemClock.elapsedRealtime() + 15000; JSONObject value;
        do {
            value = control(op, false);
            if (value.getBoolean("completed") || !value.isNull("writeError") || !value.isNull("readError") || value.getBoolean("timedOut")) break;
            Thread.sleep(75);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue(value.getBoolean("released")); assertFalse(value.getBoolean("timedOut"));
        assertTrue("server reached a terminal response attempt", value.getBoolean("completed") || !value.isNull("writeError") || !value.isNull("readError"));
        assertEquals(1, value.getInt("opCount"));
        // Successful server write() does not prove a disconnected client received it.
        return value;
    }
    private Set<String> files() {
        Set<String> result = new HashSet<>(); File[] current = context.getFilesDir().listFiles();
        assertNotNull(current); for (File file : current) if (file.getName().startsWith("download-")) result.add(file.getName());
        return result;
    }
    private JSONArray names(Set<String> values) { List<String> sorted = new ArrayList<>(values); java.util.Collections.sort(sorted); return new JSONArray(sorted); }
    private static final class Download {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger(), successes = new AtomicInteger();
        final AtomicReference<File> file = new AtomicReference<>(); final AtomicReference<String> error = new AtomicReference<>();
        final AtomicLong completedAt = new AtomicLong();
        String opid;
        Future<?> future;
    }
    private Download download(NativeDownloads owner, String op) {
        Download result = new Download(); result.opid = op; downloads.add(result);
        main(() -> result.future = owner.download(business(op, "download"), "owned-transfer-fault-ci", (file, error) -> {
            result.callbacks.incrementAndGet(); if (file != null && error == null) result.successes.incrementAndGet();
            result.file.set(file); result.error.set(error); result.completedAt.compareAndSet(0, SystemClock.elapsedRealtime()); result.done.countDown();
        }));
        return result;
    }
    private long partial(Set<String> baseline) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000; long size = 0;
        do {
            Set<String> added = files(); added.removeAll(baseline);
            if (added.size() == 1) size = new File(context.getFilesDir(), added.iterator().next()).length();
            if (size >= 4096) break;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("client really read the first download segment", size >= 4096 && size < 262144);
        assertEquals("live transfer lease", 1, routes.activeTransfers()); return size;
    }
    private void finish(Download result, boolean success) throws Exception {
        finish(result, success, 10);
    }
    private void finish(Download result, boolean success, int seconds) throws Exception {
        assertTrue("download termination without releasing server response", result.done.await(seconds, TimeUnit.SECONDS));
        if (result.future != null) try { result.future.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.CancellationException cancelled) { assertFalse(success); }
        assertEquals(1, result.callbacks.get()); assertEquals(success ? 1 : 0, result.successes.get());
        if (success) { assertNotNull(result.file.get()); assertNull(result.error.get()); }
        else { assertNull(result.file.get()); assertNotNull(result.error.get()); }
        assertEquals(0, routes.activeTransfers());
    }
    private void recordDownload(String op, String action, NativeDownloads owner) throws Exception {
        Set<String> baseline = files(); Download result = download(owner, op);
        JSONObject started = gate(op, false); long partialBytes = partial(baseline);
        assertEquals(0, result.callbacks.get());
        report.put("lastInFlight", new JSONObject().put("opid", op).put("gate", started).put("partialBytes", partialBytes)
                .put("baselineFiles", names(baseline)).put("activeTransfers", routes.activeTransfers()).put("callbackCount", result.callbacks.get())); checkpoint(false);
        long actionAt = SystemClock.elapsedRealtime();
        if ("core-stop".equals(action)) { Networkcore.stop(); assertFalse(Networkcore.isRunning()); }
        else if ("cancel".equals(action)) main(owner::cancel);
        else main(owner::close);
        finish(result, false, "core-stop".equals(action) ? 25 : 10);
        long terminationMillis = result.completedAt.get() - actionAt;
        assertTrue(terminationMillis >= 0);
        if (!"core-stop".equals(action)) assertTrue("active cancellation/close beats the 20s read timeout", terminationMillis <= 10000);
        if ("core-stop".equals(action)) { state(RouteManager.State.FAILED); assertFalse(Networkcore.isRunning()); }
        else { assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state); assertTrue(Networkcore.isRunning()); }
        Set<String> after = files(); assertEquals("runtime cleaned only this new partial", baseline, after);
        JSONObject terminal = release(op);
        phases.put(new JSONObject().put("opid", op).put("kind", "download").put("action", action).put("started", started)
                .put("partialBytes", partialBytes).put("callbackCount", result.callbacks.get()).put("successCount", result.successes.get())
                .put("terminationMillis", terminationMillis).put("coreRunning", Networkcore.isRunning())
                .put("error", result.error.get()).put("baselineFiles", names(baseline)).put("afterFiles", names(after))
                .put("activeTransfers", routes.activeTransfers()).put("routeState", routes.snapshot().state.name())
                .put("document", preserved()).put("terminal", terminal)); checkpoint(false);
    }
    private void successfulDownload(String op, NativeDownloads owner) throws Exception {
        Set<String> baseline = files(); Download result = download(owner, op); JSONObject started = gate(op, false); partial(baseline);
        JSONObject terminal = release(op); finish(result, true);
        byte[] expected = new byte[262144]; Arrays.fill(expected, (byte) 'D'); byte[] actual = Files.readAllBytes(result.file.get().toPath()); assertArrayEquals(expected, actual);
        assertFalse(baseline.contains(result.file.get().getName())); assertTrue("remove only this successful test output", result.file.get().delete());
        assertEquals(baseline, files());
        phases.put(new JSONObject().put("opid", op).put("kind", "download").put("action", "explicit-new-success")
                .put("started", started).put("terminal", terminal).put("callbackCount", result.callbacks.get()).put("successCount", result.successes.get())
                .put("deliveredBytes", actual.length)
                .put("baselineFiles", names(baseline)).put("afterFiles", names(files())).put("activeTransfers", routes.activeTransfers())
                .put("routeState", routes.snapshot().state.name()).put("coreRunning", Networkcore.isRunning()).put("document", preserved())); checkpoint(false);
    }
    private static final class Upload {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger completions = new AtomicInteger();
        final AtomicReference<NativeDownloads.Response> response = new AtomicReference<>(); final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicLong completedAt = new AtomicLong();
    }
    private void nativeUpload(String op, NativeDownloads owner, String action) throws Exception {
        Upload result = new Upload(); byte[] body = new byte[65536]; Arrays.fill(body, (byte) 'U');
        new Thread(() -> {
            try { result.response.set(owner.request(business(op, "upload"), "POST", body)); }
            catch (Exception error) { result.error.set(error); }
            finally { result.completions.incrementAndGet(); result.completedAt.set(SystemClock.elapsedRealtime()); result.done.countDown(); }
        }, "owned-transfer-upload").start();
        JSONObject started = gate(op, true); assertEquals(body.length, started.getInt("requestBytesRead"));
        assertEquals(1, routes.activeTransfers()); assertEquals(0, result.completions.get());
        report.put("lastInFlight", new JSONObject().put("opid", op).put("gate", started).put("activeTransfers", routes.activeTransfers())
                .put("completionCount", result.completions.get())); checkpoint(false);
        JSONObject terminal;
        boolean unknown = !"explicit-new-success".equals(action);
        long actionAt = SystemClock.elapsedRealtime();
        if ("core-stop".equals(action)) { Networkcore.stop(); assertFalse(Networkcore.isRunning()); }
        else if ("close".equals(action)) main(owner::close);
        if (!unknown) terminal = release(op); else terminal = null;
        assertTrue("native upload terminates while response held", result.done.await("close".equals(action) ? 10 : 25, TimeUnit.SECONDS));
        long terminationMillis = result.completedAt.get() - actionAt;
        if ("close".equals(action)) assertTrue("close actively disconnects before the 20s read timeout", terminationMillis >= 0 && terminationMillis <= 10000);
        assertEquals(1, result.completions.get()); assertEquals(0, routes.activeTransfers());
        boolean outcomeUncertain = false;
        if (unknown) {
            assertNull(result.response.get()); assertTrue(result.error.get() instanceof NativeDownloads.RequestFailure);
            outcomeUncertain = ((NativeDownloads.RequestFailure) result.error.get()).outcomeUncertain;
            assertTrue("committed upload must not be labelled unsent", outcomeUncertain);
            assertTrue(result.error.get().getMessage().contains("结果不明"));
            if ("core-stop".equals(action)) { state(RouteManager.State.FAILED); assertFalse(Networkcore.isRunning()); }
            else { assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state); assertTrue(Networkcore.isRunning()); }
            terminal = release(op);
        } else { assertNull(result.error.get()); assertEquals("A", result.response.get().route); assertArrayEquals(ACK.getBytes(StandardCharsets.UTF_8), result.response.get().bytes); }
        phases.put(new JSONObject().put("opid", op).put("kind", "native-upload").put("action", action).put("started", started)
                .put("terminal", terminal).put("completionCount", result.completions.get()).put("successCount", result.response.get() == null ? 0 : 1)
                .put("terminationMillis", terminationMillis).put("coreRunning", Networkcore.isRunning())
                .put("error", result.error.get() == null ? JSONObject.NULL : result.error.get().getMessage())
                .put("responseBytes", result.response.get() == null ? 0 : result.response.get().bytes.length)
                .put("outcomeUncertain", outcomeUncertain).put("activeTransfers", routes.activeTransfers())
                .put("routeState", routes.snapshot().state.name()).put("document", preserved())); checkpoint(false);
    }
    private void webUpload(String op, boolean stop) throws Exception {
        js("window.__transferResults[" + JSONObject.quote(op) + "]={state:'pending',completions:0};(()=>{const r=window.__transferResults[" + JSONObject.quote(op)
                + "];const form=new FormData();form.append('file',new File([new Uint8Array(32768)],'owned.bin',{type:'application/octet-stream'}));fetch("
                + JSONObject.quote(business(op, "upload")) + ",{method:'POST',body:form,cache:'no-store'}).then(async response=>{r.status=response.status;if(!response.ok)throw new Error('HTTP '+response.status);r.route=response.headers.get('X-Fixture-Route');r.body=await response.text();r.state='success'}).catch(error=>{r.error=String(error);r.state='error'}).finally(()=>r.completions++)})();true");
        JSONObject started = gate(op, true); assertTrue(started.getInt("requestBytesRead") > 32768);
        JSONObject pending = new JSONObject(js("window.__transferResults[" + JSONObject.quote(op) + "]"));
        assertEquals("pending", pending.getString("state")); assertEquals(0, pending.getInt("completions"));
        report.put("lastInFlight", new JSONObject().put("opid", op).put("gate", started).put("client", pending)); checkpoint(false);
        if (stop) { Networkcore.stop(); assertFalse(Networkcore.isRunning()); } else release(op);
        JSONObject result = null; long deadline = SystemClock.elapsedRealtime() + 25000;
        do { result = new JSONObject(js("window.__transferResults[" + JSONObject.quote(op) + "]")); if (result.getInt("completions") > 0) break; Thread.sleep(75); }
        while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(1, result.getInt("completions")); assertEquals(stop ? "error" : "success", result.getString("state"));
        if (stop) { state(RouteManager.State.FAILED); assertFalse(Networkcore.isRunning()); }
        else { assertEquals(200, result.getInt("status")); assertEquals("A", result.getString("route")); assertEquals(ACK, result.getString("body")); }
        JSONObject terminal = stop ? release(op) : control(op, false);
        phases.put(new JSONObject().put("opid", op).put("kind", "web-upload").put("action", stop ? "core-stop" : "explicit-new-success")
                .put("started", started).put("terminal", terminal).put("client", result).put("outcomeUncertain", stop)
                .put("clientBefore", pending).put("coreRunning", Networkcore.isRunning())
                .put("activeTransfers", routes.activeTransfers()).put("routeState", routes.snapshot().state.name()).put("document", preserved())); checkpoint(false);
    }
    private JSONArray rejectClosed(NativeDownloads owner) throws Exception {
        JSONArray denied = new JSONArray(); String downloadOp = op("closed-rejected-download"), requestOp = op("closed-rejected-upload");
        Download rejected = download(owner, downloadOp); finish(rejected, false); assertNull(control(downloadOp, false));
        boolean requestRejected = false;
        try { owner.request(business(requestOp, "upload"), "POST", new byte[]{1}); fail("closed instance started a new request"); }
        catch (IllegalStateException expected) { requestRejected = true; }
        assertNull(control(requestOp, false)); assertEquals(0, routes.activeTransfers());
        denied.put(new JSONObject().put("opid", downloadOp).put("kind", "download").put("callbackCount", rejected.callbacks.get()).put("successCount", rejected.successes.get()).put("serverAbsent", true));
        denied.put(new JSONObject().put("opid", requestOp).put("kind", "upload").put("rejectedBeforeTransfer", requestRejected).put("serverAbsent", true)); return denied;
    }
    private void checkpoint(boolean complete) throws Exception {
        report.put("complete", complete).put("phases", phases).put("controlRequests", controls);
        File temporary = new File(context.getFilesDir(), "transfer-fault-result.json.tmp");
        Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(temporary.renameTo(new File(context.getFilesDir(), "transfer-fault-result.json")));
    }
    void run() throws Exception {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci")); assertEquals("http://10.0.2.2:18080/page", BuildConfig.NETWORK_HOME_URL);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        origin = args.getString("fixtureUrl"); transport = args.getString("expectedSecurity", "none"); if ("none".equals(transport)) transport = "raw";
        prefix = transport + "-" + new URL(origin).getProtocol() + "-";
        new NodeStore(context).write(new NodeStore.Data());
        report = new JSONObject().put("schema", 1).put("transport", transport).put("origin", origin).put("controlOrigin", "http://10.0.2.2:18080")
                .put("controlPlane", "androidTest-only direct owned control; not production fallback");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> reference = new AtomicReference<>(); scenario.onActivity(reference::set); activity = reference.get(); routes = activity.routeManagerForTests();
            state(RouteManager.State.DIRECT); connect();
            main(() -> activity.webViewForTests().loadUrl(origin + "/stability/transfer-fault-" + prefix + "/page"));
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (!"\"A\"".equals(js("document.title")) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(75);
            assertEquals("\"A\"", js("document.title"));
            while (!"\"complete\"".equals(js("document.readyState")) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(75);
            assertEquals("\"complete\"", js("document.readyState"));
            js("document.getElementById('draft').value='transfer-fault-draft';window.__transferIdentity='owned-'+Date.now()+'-'+Math.random();window.__transferSubmissions=0;window.__transferResults={};document.addEventListener('submit',()=>window.__transferSubmissions++);true");
            original = document(); report.put("originalDocument", original); checkpoint(false);
            NativeDownloads owner = client();
            recordDownload(op("core-stop-download"), "core-stop", owner); connect();
            successfulDownload(op("recovered-download"), owner);
            nativeUpload(op("core-stop-native-upload"), owner, "core-stop"); connect();
            nativeUpload(op("recovered-native-upload"), owner, "explicit-new-success");
            webUpload(op("core-stop-web-upload"), true); connect(); webUpload(op("recovered-web-upload"), false);
            recordDownload(op("cancel-download"), "cancel", owner); successfulDownload(op("after-cancel-download"), owner);
            recordDownload(op("close-download"), "close", owner); report.put("closedRejections", rejectClosed(owner));
            NativeDownloads fresh = client(); successfulDownload(op("new-instance-download"), fresh);
            nativeUpload(op("close-native-upload"), fresh, "close"); NativeDownloads newest = client();
            nativeUpload(op("after-close-native-upload"), newest, "explicit-new-success");
            JSONArray finalOperations = new JSONArray(), finalCallbacks = new JSONArray(); report.put("finalOperations", finalOperations);
            for (int i = 0; i < phases.length(); i++) {
                JSONObject phase = phases.getJSONObject(i), terminal = control(phase.getString("opid"), false); finalOperations.put(terminal);
                assertEquals("no resend after explicit recovery", 1, terminal.getInt("opCount"));
                assertEquals("no late duplicate attempt", 1, terminal.getInt("attemptCount"));
                assertEquals("no duplicate server commit", "download".equals(phase.getString("kind")) ? 0 : 1, terminal.getInt("commitCount"));
            }
            for (Download result : downloads) {
                assertEquals("old callback never completes a newer operation", 1, result.callbacks.get());
                finalCallbacks.put(new JSONObject().put("opid", result.opid).put("callbackCount", result.callbacks.get()).put("successCount", result.successes.get()));
            }
            report.put("finalDownloadCallbacks", finalCallbacks).put("browserScope", "owned File/FormData fixture only; not a real ChatGPT upload implementation");
            report.put("finalDocument", preserved()).put("activeTransfers", routes.activeTransfers()); assertEquals(0, routes.activeTransfers());
            checkpoint(true);
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage()); checkpoint(false); throw failure;
        } finally {
            for (String op : attempted) try { if (control(op, false) != null) control(op, true); } catch (Exception ignored) { }
            for (NativeDownloads owner : clients) owner.close();
        }
    }
}
