package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import studio.networkcore.Networkcore;
import static org.junit.Assert.*;

/** Full joint proof WIP. Not connected to the current four-test/capability-only entry. */
final class JointProfileCases {
    private final Bundle args;
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final JSONObject report = new JSONObject();
    private final JSONArray wire = new JSONArray(), fileResults = new JSONArray(), routeChecks = new JSONArray();
    private final Map<String, ProfileFileTasks> files = new LinkedHashMap<>();
    private final Map<String, JSONObject> documents = new LinkedHashMap<>();
    private final Map<String, WebView> retainedViews = new LinkedHashMap<>();
    private final Map<String, File> assets = new LinkedHashMap<>();
    private final Map<String, Uri> exports = new LinkedHashMap<>();
    private final List<Task> tasks = new ArrayList<>();
    private JointProfileActivity activity;
    private JointProfileSession session;
    private RouteManager routes;
    private JointSystemProxy policy;
    private String origin, prefix;
    private String aGate, bGate, documentGate;
    private JSONObject currentStage;
    private final JSONArray stages = new JSONArray();
    private Uri parent, sentinel;
    JointProfileCases(Bundle args) { this.args = args; }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void stage(String name) throws Exception {
        if (currentStage != null && "running".equals(currentStage.getString("state")))
            currentStage.put("state", "passed").put("endedAtMillis", SystemClock.elapsedRealtime());
        currentStage = new JSONObject().put("name", name).put("state", "running").put("startedAtMillis", SystemClock.elapsedRealtime());
        stages.put(currentStage); report.put("currentStage", name); save(false);
    }
    private void difference(Throwable failure) throws Exception {
        if (currentStage != null) currentStage.put("state", "failed").put("endedAtMillis", SystemClock.elapsedRealtime());
        if (!report.has("firstDifference")) report.put("firstDifference", new JSONObject().put("stage", report.optString("currentStage"))
                .put("errorClass", failure.getClass().getSimpleName()).put("message", String.valueOf(failure.getMessage())));
    }
    private WebView view(String tab) { AtomicReference<WebView> value = new AtomicReference<>(); main(() -> value.set(session.view(tab))); return value.get(); }
    private List<String> pages() { AtomicReference<List<String>> value = new AtomicReference<>(); main(() -> value.set(new ArrayList<>(session.pageIds()))); return value.get(); }
    private String js(WebView web, String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        main(() -> web.evaluateJavascript(code, value -> { result.set(value); done.countDown(); }));
        assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    private void await(WebView web, String code, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000; String value;
        do { value = js(web, code); if (expected.equals(value)) return; Thread.sleep(75); } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("joint stage=" + report.optString("currentStage") + " expression=" + code, expected, value);
    }
    private void select(String tab) { main(() -> activity.showPageForTests(tab)); }
    private String path(String operation, String kind) { return "/joint/" + prefix + operation + "/" + kind; }
    private void expected(String path, String route, String account, int status) throws Exception {
        wire.put(new JSONObject().put("path", path).put("route", route).put("syntheticAccount", account)
                .put("method", "GET").put("status", status));
    }
    private void route(String mode, String name) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        VlessNode node = "system".equals(mode) ? null : VlessNode.parse(args.getString("node" + name), name);
        main(() -> { if (node == null) routes.useSystem((ok, message) -> { if (!ok) failure.set(message); done.countDown(); });
            else routes.switchTo(node, (ok, message) -> { if (!ok) failure.set(message); done.countDown(); }); });
        assertTrue(done.await(35, TimeUnit.SECONDS)); assertNull(failure.get());
        assertEquals(node == null ? RouteManager.State.SYSTEM : RouteManager.State.CONNECTED, routes.snapshot().state);
        if (node == null) { assertFalse(Networkcore.isRunning()); try { routes.snapshot().proxy(); fail("SYSTEM was represented as forced direct"); } catch (IllegalStateException expected) { } }
        else assertTrue(Networkcore.isRunning());
    }
    private void cookie(String account, String url, String value) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Boolean> accepted = new AtomicReference<>();
        main(() -> session.cookies(account).setCookie(url, "ci_joint_account=" + value + "; Path=/; SameSite=Lax" + (url.startsWith("https:") ? "; Secure" : ""), ok -> { accepted.set(ok); done.countDown(); }));
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(accepted.get()); main(() -> session.cookies(account).flush());
    }
    private JSONObject document(String tab) throws Exception {
        select(tab); WebView web = view(tab);
        return new JSONObject(js(web, "({identity:window.__jointIdentity,draft:document.getElementById('draft').value,storage:localStorage.getItem('ci_joint_storage'),cookie:(document.cookie.match(/(?:^|;\\s*)ci_joint_account=([^;]*)/)||[])[1]||'missing',url:location.href})"));
    }
    private void preserved(String tab) throws Exception {
        assertSame(retainedViews.get(tab), view(tab)); assertEquals(documents.get(tab).toString(), document(tab).toString());
    }
    private void page(String tab, String account, boolean shared) throws Exception {
        stage("page tab=" + tab + " account=" + account);
        select(tab); WebView web = view(tab); String target = path("page-" + tab, "page");
        main(() -> web.loadUrl(origin + target)); await(web, "document.title", JSONObject.quote("A:" + account));
        await(web, "document.readyState", "\"complete\""); expected(target, "A", account, 200);
        String before = js(web, "localStorage.getItem('ci_joint_storage')");
        assertEquals(shared ? JSONObject.quote(account) : "null", before);
        js(web, "localStorage.setItem('ci_joint_storage'," + JSONObject.quote(account) + ");document.getElementById('draft').value=" + JSONObject.quote("joint-draft-" + tab)
                + ";window.__jointIdentity=" + JSONObject.quote("joint-" + tab + "-") + "+Date.now()+'-'+Math.random();true");
        AtomicReference<String> profile = new AtomicReference<>(); main(() -> profile.set(WebViewCompat.getProfile(web).getName()));
        assertEquals(session.profileName(account), profile.get());
        retainedViews.put(tab, web); documents.put(tab, document(tab));
        report.getJSONArray("isolation").put(new JSONObject().put("tab", tab).put("account", account).put("profileName", profile.get())
                .put("storageBefore", before).put("document", documents.get(tab)));
    }
    private void popup() throws Exception {
        stage("trusted-popup opener=A1 account=A");
        select("A1"); WebView opener = view("A1"); JSONObject box = new JSONObject(js(opener,
                "(()=>{const r=document.getElementById('popup').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,ratio:devicePixelRatio}})()"));
        int[] location = new int[2]; main(() -> opener.getLocationOnScreen(location));
        float x = location[0] + (float) (box.getDouble("x") * box.getDouble("ratio"));
        float y = location[1] + (float) (box.getDouble("y") * box.getDouble("ratio"));
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent release = MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_UP, x, y, 0);
        press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); release.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(press, true));
            assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(release, true));
        } finally { press.recycle(); release.recycle(); }
        long deadline = SystemClock.elapsedRealtime() + 15000; String popup = null;
        do { for (String id : pages()) if (!retainedViews.containsKey(id)) popup = id;
            if (popup != null) break; Thread.sleep(75); } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("trusted gesture must create an owned popup", popup); final String child = popup;
        main(() -> { assertEquals("A1", session.popupOwner(child)); assertEquals("A", session.accountOf(child)); });
        WebView web = view(child); await(web, "document.title", "\"A:A\"");
        AtomicReference<String> profile = new AtomicReference<>(); main(() -> profile.set(WebViewCompat.getProfile(web).getName()));
        assertEquals(session.profileName("A"), profile.get());
        expected(path("page-A1-popup", "page"), "A", "A", 200);
        assertEquals("\"A\"", js(web, "localStorage.getItem('ci_joint_storage')"));
        js(web, "window.__jointIdentity='joint-popup-'+Date.now()+'-'+Math.random();document.getElementById('draft').value='joint-popup-draft';true");
        retainedViews.put(child, web); documents.put(child, document(child));
        report.put("popup", new JSONObject().put("id", child).put("owner", "A1").put("account", "A").put("profileName", profile.get()).put("realGesture", true).put("document", documents.get(child)));
    }
    private static final class Task {
        final CountDownLatch done = new CountDownLatch(1); final AtomicReference<ProfileFileTasks.Result> result = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger(), currentEpochDeliveries = new AtomicInteger();
        String visibleAccount;
    }
    private Task start(String account, String task, String target, boolean export) {
        Task observed = new Task(); tasks.add(observed); long epoch = session.epoch(account);
        main(() -> {
            ProfileFileTasks.Callback callback = result -> {
                observed.callbacks.incrementAndGet(); observed.result.set(result);
                observed.visibleAccount = session.accountOf(session.visiblePage());
                if (session.isCurrent(account, epoch)) observed.currentEpochDeliveries.incrementAndGet();
                observed.done.countDown();
            };
            if (export) files.get(account).saveDocument(task, target, "owned-joint-ci", parent, callback);
            else files.get(account).startAsset(task, target, "owned-joint-ci", callback);
        }); return observed;
    }
    private ProfileFileTasks.Result finished(Task observed, String account, String task, long generation, boolean success) throws Exception {
        assertTrue(observed.done.await(25, TimeUnit.SECONDS)); assertEquals(1, observed.callbacks.get());
        ProfileFileTasks.Result result = observed.result.get(); assertEquals(account, result.accountId); assertEquals(task, result.taskId);
        assertEquals(session.profileName(account), result.storageId); assertEquals(0, result.accountEpoch); assertEquals(generation, result.routeGeneration);
        if (success) assertEquals(ProfileFileTasks.State.COMPLETED, result.state);
        else { assertEquals(ProfileFileTasks.State.CANCELLED, result.state); assertNull(result.file); assertNull(result.document); }
        return result;
    }
    private byte[] documentBytes(Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) >= 0) { bytes.write(buffer, 0, count); assertTrue(bytes.size() < 1024 * 1024); }
            return bytes.toByteArray();
        }
    }
    private String hash(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString();
    }
    private void file(String account, String name, String kind, String route, String responseAccount, boolean export) throws Exception {
        stage("file operation=" + name + " account=" + account + " expectedRoute=" + route + " exit=" + (export ? "document" : "asset"));
        String task = prefix + name, url = origin + path(name, kind); long generation = routes.snapshot().generation;
        Task observed = start(account, task, url, export); select("C1");
        ProfileFileTasks.Result result = finished(observed, account, task, generation, true);
        byte[] bytes = export ? documentBytes(result.document) : Files.readAllBytes(result.file.toPath());
        JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        assertEquals(route, body.getString("route")); assertEquals(responseAccount, body.getString("account"));
        assertEquals(prefix + name + (kind.startsWith("redirect-") ? "-target" : ""), body.getString("operation"));
        expected(path(name, kind), route, account, kind.startsWith("redirect-") ? 302 : 200);
        if (kind.startsWith("redirect-")) expected(path(name + "-target", "download"), route, responseAccount, 200);
        JSONObject output = new JSONObject().put("taskId", result.taskId).put("accountId", result.accountId).put("storageId", result.storageId)
                .put("accountEpoch", result.accountEpoch).put("routeGeneration", result.routeGeneration).put("state", result.state.name())
                .put("capturedRouteGeneration", generation)
                .put("exit", export ? "document" : "asset").put("body", body).put("callbackCount", observed.callbacks.get())
                .put("visibleAccountAtCompletion", observed.visibleAccount).put("currentEpochDeliveries", observed.currentEpochDeliveries.get());
        assertEquals(1, observed.currentEpochDeliveries.get());
        if (export) { assertNull(result.file); assertNotEquals(sentinel, result.document); exports.putIfAbsent(account, result.document); output.put("document", result.document.toString()); }
        else { assertNull(result.document); assets.putIfAbsent(account, result.file); output.put("file", result.file.getName()); }
        fileResults.put(output);
    }
    private void echo(String tab, String name, String route, String account) throws Exception {
        stage("web operation=" + name + " tab=" + tab + " expectedRoute=" + route + " account=" + account);
        select(tab); WebView web = view(tab); String target = path(name, "echo");
        js(web, "window.__jointEcho=null;fetch(" + JSONObject.quote(target) + ",{cache:'no-store'}).then(r=>r.json()).then(value=>window.__jointEcho=value);true");
        long deadline = SystemClock.elapsedRealtime() + 20000; JSONObject actual = null;
        do { String value = js(web, "window.__jointEcho"); if (!"null".equals(value)) { actual = new JSONObject(value); break; } Thread.sleep(75); } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull(actual); assertEquals(route, actual.getString("route")); assertEquals(account, actual.getString("account"));
        expected(target, route, account, 200); routeChecks.put(new JSONObject().put("tab", tab).put("operation", name).put("body", actual).put("routeState", routes.snapshot().state.name()));
    }
    private JSONObject gate(String op, boolean release) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://10.0.2.2:18080/transfer-control/" + op + (release ? "/release" : "/state")).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
        if (release) { connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0); connection.getOutputStream().close(); }
        try { if (connection.getResponseCode() == 404) return null;
            assertEquals(200, connection.getResponseCode()); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[2048]; int count; while ((count = input.read(buffer)) >= 0) { bytes.write(buffer, 0, count); assertTrue(bytes.size() < 16384); } }
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
    private JSONObject prefix(String op, String account) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000; JSONObject value;
        do { value = gate(op, false); if (value != null && value.getBoolean("prefix")) break; Thread.sleep(75); } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull(value); assertTrue(value.getBoolean("prefix")); assertEquals(account, value.getString("syntheticAccount"));
        assertFalse(value.getBoolean("released")); assertEquals(1, value.getInt("opCount")); return value;
    }
    private java.util.Set<String> partialNames() {
        java.util.Set<String> names = new java.util.HashSet<>(); File[] entries = context.getFilesDir().listFiles(); assertNotNull(entries);
        for (File entry : entries) if (entry.getName().startsWith("download-")) names.add(entry.getName()); return names;
    }
    private File realPartial(java.util.Set<String> before, String operation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000; File partial = null;
        do {
            java.util.Set<String> added = partialNames(); added.removeAll(before);
            if (added.size() == 1) partial = new File(context.getFilesDir(), added.iterator().next());
            if (partial != null && partial.length() >= 4096) break; Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("missing local partial: " + operation, partial);
        assertTrue("client has not consumed gated prefix: " + operation, partial.length() >= 4096 && partial.length() < 262144); return partial;
    }
    private java.util.Set<String> documentIds() throws Exception {
        java.util.Set<String> ids = new java.util.HashSet<>(); Uri children = DocumentsContract.buildChildDocumentsUri(parent.getAuthority(), "root");
        try (android.database.Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            assertNotNull(cursor); while (cursor.moveToNext()) ids.add(cursor.getString(0));
        } return ids;
    }
    private void cancelDocumentWhileBActive(Task bTask, File bPartial, byte[] sentinelBytes) throws Exception {
        stage("document-task-cancel-and-closed-owner-rejection-while-B-active");
        documentGate = prefix + "document-cancel"; java.util.Set<String> beforeFiles = partialNames(), beforeDocuments = documentIds();
        long generation = routes.snapshot().generation;
        Task cancelled = start("C", documentGate, origin + "/transfer/" + documentGate + "/download", true);
        JSONObject started = prefix(documentGate, "C"); File partial = realPartial(beforeFiles, documentGate); long bytes = partial.length();
        assertEquals(2, routes.activeTransfers()); long at = SystemClock.elapsedRealtime(); main(() -> files.get("C").cancel(documentGate));
        assertTrue("document cancellation deadline", cancelled.done.await(10, TimeUnit.SECONDS));
        finished(cancelled, "C", documentGate, generation, false); long cancelMillis = SystemClock.elapsedRealtime() - at; assertTrue(cancelMillis <= 10000);
        assertEquals(beforeFiles, partialNames()); assertEquals(beforeDocuments, documentIds()); assertArrayEquals(sentinelBytes, documentBytes(sentinel));
        assertEquals(0, bTask.callbacks.get()); assertTrue(bPartial.exists()); assertTrue(bPartial.length() >= 4096); assertEquals(1, routes.activeTransfers());
        gate(documentGate, true);
        AtomicReference<CookieManager> cookies = new AtomicReference<>(); main(() -> cookies.set(session.cookies("C")));
        ProfileFileTasks closed = new ProfileFileTasks(context, routes, "C", session.profileName("C"), 0, cookies.get(), () -> session.isCurrent("C", 0)); closed.close();
        String rejectedId = prefix + "closed-owner-rejected"; Task rejected = new Task(); tasks.add(rejected);
        main(() -> closed.saveDocument(rejectedId, origin + "/transfer/" + rejectedId + "/download", "owned-joint-ci", parent,
                result -> { rejected.result.set(result); rejected.callbacks.incrementAndGet(); rejected.done.countDown(); }));
        assertTrue(rejected.done.await(5, TimeUnit.SECONDS)); assertEquals(1, rejected.callbacks.get());
        assertNotEquals(ProfileFileTasks.State.COMPLETED, rejected.result.get().state); assertNull(rejected.result.get().file); assertNull(rejected.result.get().document);
        assertNull(gate(rejectedId, false)); assertEquals(beforeDocuments, documentIds()); assertEquals(1, routes.activeTransfers());
        report.put("documentCancellation", new JSONObject().put("opid", documentGate).put("started", started).put("partialBytes", bytes)
                .put("state", cancelled.result.get().state.name()).put("callbackCount", cancelled.callbacks.get()).put("elapsedMillis", cancelMillis)
                .put("cancelledFileAbsent", cancelled.result.get().file == null).put("cancelledDocumentAbsent", cancelled.result.get().document == null)
                .put("documentsBefore", new JSONArray(beforeDocuments)).put("documentsAfter", new JSONArray(documentIds()))
                .put("sentinelSHA256", hash(documentBytes(sentinel))).put("bCallbackCountBeforeRelease", bTask.callbacks.get())
                .put("rejectedOpid", rejectedId).put("rejectedState", rejected.result.get().state.name()).put("rejectedCallbackCount", rejected.callbacks.get()).put("rejectedServerAbsent", true)
                .put("rejectedFileAbsent", rejected.result.get().file == null).put("rejectedDocumentAbsent", rejected.result.get().document == null));
    }
    private void system(String mode, String expectedRoute) throws Exception {
        stage("configure-system mode=" + mode + " expectedRoute=" + expectedRoute);
        JSONObject configured = policy.configure(mode); route("system", null);
        for (String application : new String[]{"system-initial", "A", "B", "system-restored"}) {
            if ("A".equals(application) || "B".equals(application)) route("application", application);
            else if ("system-restored".equals(application)) route("system", null);
            String actualRoute = application.startsWith("system") ? expectedRoute : application;
            String tag = mode + "-" + application;
            echo("B1", tag + "-web", actualRoute, "B");
            file("B", tag + "-asset", "download", actualRoute, "B", false);
            file("C", tag + "-document", "download", actualRoute, "C", true);
            assertEquals("app override must not mutate OS proxy", configured.getJSONObject("actualProxyInfo").toString(), policy.current().getJSONObject("actualProxyInfo").toString());
        }
    }
    private void save(boolean complete) throws Exception {
        report.put("complete", complete).put("expectedRequests", wire).put("fileResults", fileResults).put("routeChecks", routeChecks).put("stages", stages);
        if (policy != null) report.put("systemPolicy", policy.evidence);
        File temporary = new File(context.getFilesDir(), "joint-profile-result.json.tmp");
        Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(temporary.renameTo(new File(context.getFilesDir(), "joint-profile-result.json")));
    }
    void run() throws Exception {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci")); assertEquals("1", JointSystemProxy.shell("getprop ro.kernel.qemu").trim());
        origin = args.getString("fixtureUrl"); String transport = args.getString("expectedSecurity"); if ("none".equals(transport)) transport = "raw";
        prefix = transport + "-" + new URL(origin).getProtocol() + "-joint-";
        report.put("schema", 1).put("origin", origin).put("transport", transport).put("prefix", prefix).put("pid", android.os.Process.myPid())
                .put("sourceSHA", args.getString("sourceSHA", "unavailable")).put("isolation", new JSONArray()).put("fullJointAcceptance", true);
        boolean complete = false;
        try (ActivityScenario<JointProfileActivity> scenario = ActivityScenario.launch(JointProfileActivity.class)) {
            scenario.onActivity(current -> { activity = current; session = current.sessionForTests(); }); routes = session.routes();
            assertTrue(JointProfileSession.supported());
            JSONObject names = new JSONObject(); for (String account : new String[]{"A", "B", "C"}) names.put(account, session.profileName(account));
            assertEquals(3, new java.util.HashSet<>(Arrays.asList(names.getString("A"), names.getString("B"), names.getString("C"))).size());
            report.put("profileNames", names); route("application", "A");
            for (String account : new String[]{"A", "B", "C"}) {
                cookie(account, origin, account); cookie(account, "https://files.network-fixture.test:18480", account + "-files");
                long epoch = session.epoch(account); AtomicReference<CookieManager> cookies = new AtomicReference<>(); main(() -> cookies.set(session.cookies(account)));
                ProfileFileTasks owner = new ProfileFileTasks(context, routes, account, session.profileName(account), epoch, cookies.get(), () -> session.isCurrent(account, epoch));
                files.put(account, owner); session.registerAccountClose(account, owner::clearAccount);
            }
            page("A1", "A", false); page("A2", "A", true); page("B1", "B", false); page("C1", "C", false); popup();
            parent = DocumentsContract.buildDocumentUri(BuildConfig.APPLICATION_ID + ".owned-docs", "root");
            sentinel = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/octet-stream", "owned-sentinel.bin"); assertNotNull(sentinel);
            byte[] sentinelBytes = "owned-sentinel-must-survive".getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = context.getContentResolver().openOutputStream(sentinel, "w")) { assertNotNull(output); output.write(sentinelBytes); }
            for (String account : new String[]{"A", "B", "C"}) {
                file(account, account + "-asset-A", "download", "A", account, false);
                file(account, account + "-doc-A", "download", "A", account, true);
            }
            file("A", "A-other", "redirect-other", "A", "A-files", false); file("A", "A-same", "redirect-same", "A", "A", true);
            file("B", "B-same", "redirect-same", "A", "B", false); file("B", "B-other", "redirect-other", "A", "B-files", true);
            route("application", "B");
            for (String tab : pages()) { AtomicReference<String> account = new AtomicReference<>(); main(() -> account.set(session.accountOf(tab))); echo(tab, "B-web-" + tab, "B", account.get()); }
            for (String account : new String[]{"A", "B", "C"}) { file(account, account + "-asset-B", "download", "B", account, false); file(account, account + "-doc-B", "download", "B", account, true); }
            route("application", "A"); save(false);
            stage("concurrent-account-gates and activity-recreation");
            String aOp = prefix + "a-clear", bOp = prefix + "b-live"; long generation = routes.snapshot().generation;
            aGate = aOp; bGate = bOp;
            java.util.Set<String> beforeA = partialNames();
            Task aTask = start("A", aOp, origin + "/transfer/" + aOp + "/download", false);
            JSONObject aStarted = prefix(aOp, "A"); File aPartial = realPartial(beforeA, aOp); long aBytes = aPartial.length();
            java.util.Set<String> beforeB = partialNames();
            Task bTask = start("B", bOp, origin + "/transfer/" + bOp + "/download", false);
            JSONObject bStarted = prefix(bOp, "B"); File bPartial = realPartial(beforeB, bOp); long bBytes = bPartial.length(); assertEquals(2, routes.activeTransfers());
            report.put("localGatePartials", new JSONObject().put("A", aBytes).put("B", bBytes).put("activeTransfers", routes.activeTransfers()));
            JointProfileSession beforeSession = session; JointProfileActivity oldActivity = activity;
            scenario.recreate(); scenario.onActivity(current -> { activity = current; assertNotSame(oldActivity, current); assertSame(beforeSession, current.sessionForTests()); });
            assertEquals(generation, routes.snapshot().generation); assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state); assertTrue(Networkcore.isRunning());
            JSONArray liveDocuments = new JSONArray();
            for (String tab : pages()) { preserved(tab); liveDocuments.put(new JSONObject().put("tab", tab)
                    .put("before", documents.get(tab)).put("after", document(tab)).put("sameView", retainedViews.get(tab) == view(tab))); }
            CountDownLatch refused = new CountDownLatch(1); AtomicReference<Boolean> switchAccepted = new AtomicReference<>();
            main(() -> routes.switchTo(VlessNode.parse(args.getString("nodeB"), "B"), (ok, message) -> { switchAccepted.set(ok); refused.countDown(); }));
            assertTrue(refused.await(5, TimeUnit.SECONDS)); assertFalse(switchAccepted.get()); assertEquals(generation, routes.snapshot().generation);
            stage("clear-A-epoch-while-B-in-flight");
            Map<String, byte[]> keptAssets = new LinkedHashMap<>(), keptExports = new LinkedHashMap<>();
            for (String account : new String[]{"B", "C"}) { keptAssets.put(account, Files.readAllBytes(assets.get(account).toPath())); keptExports.put(account, documentBytes(exports.get(account))); }
            byte[] aExport = documentBytes(exports.get("A")); AtomicReference<JointProfileSession.ClearResult> cleared = new AtomicReference<>();
            long clearStarted = SystemClock.elapsedRealtime();
            main(() -> { session.showPage("C1"); cleared.set(session.clearAccount("A")); });
            finished(aTask, "A", aOp, generation, false); assertEquals(0, aTask.currentEpochDeliveries.get());
            long deadline = clearStarted + 10000;
            while (!files.get("A").clearComplete() && files.get("A").clearError().isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue(files.get("A").clearError(), files.get("A").clearComplete());
            assertTrue("account clear must finish within its 10s contract", SystemClock.elapsedRealtime() - clearStarted <= 10000);
            long clearMillis = SystemClock.elapsedRealtime() - clearStarted;
            assertFalse(session.isCurrent("A", 0)); assertEquals(1, session.epoch("A")); assertEquals(1, routes.activeTransfers());
            assertFalse(new File(new File(context.getFilesDir(), "joint-assets"), session.profileName("A") + "-0").exists());
            List<String> remainingPages = pages(); assertFalse(remainingPages.contains("A1")); assertFalse(remainingPages.contains("A2"));
            assertFalse(remainingPages.contains(report.getJSONObject("popup").getString("id")));
            assertFalse(aPartial.exists()); assertTrue(bPartial.exists()); assertEquals(0, bTask.callbacks.get());
            cancelDocumentWhileBActive(bTask, bPartial, sentinelBytes);
            stage("complete-B-and-verify-account-file-boundaries");
            gate(aOp, true); gate(bOp, true); ProfileFileTasks.Result bResult = finished(bTask, "B", bOp, generation, true);
            assertEquals(1, bTask.currentEpochDeliveries.get()); assertEquals(262144, bResult.file.length());
            byte[] livePayload = new byte[262144]; Arrays.fill(livePayload, (byte) 'D'); assertArrayEquals(livePayload, Files.readAllBytes(bResult.file.toPath()));
            JSONArray protectedFiles = new JSONArray();
            for (String account : new String[]{"B", "C"}) {
                byte[] assetAfter = Files.readAllBytes(assets.get(account).toPath()), exportAfter = documentBytes(exports.get(account));
                assertArrayEquals(keptAssets.get(account), assetAfter); assertArrayEquals(keptExports.get(account), exportAfter);
                protectedFiles.put(new JSONObject().put("accountId", account).put("assetBeforeSHA256", hash(keptAssets.get(account)))
                        .put("assetAfterSHA256", hash(assetAfter)).put("documentBeforeSHA256", hash(keptExports.get(account))).put("documentAfterSHA256", hash(exportAfter)));
            }
            assertArrayEquals(aExport, documentBytes(exports.get("A"))); assertArrayEquals(sentinelBytes, documentBytes(sentinel)); preserved("B1"); preserved("C1");
            JointProfileSession.ClearResult clear = cleared.get();
            report.put("clearA", new JSONObject().put("epoch", clear.epoch).put("profileDeleted", clear.profileDeleted)
                    .put("deletionError", clear.deletionError == null ? JSONObject.NULL : clear.deletionError)
                    .put("deferredProfiles", new JSONArray(session.deferredProfiles())).put("oldResultState", aTask.result.get().state.name())
                    .put("remainingPages", new JSONArray(remainingPages)).put("clearMillis", clearMillis).put("protectedFiles", protectedFiles)
                    .put("sentinelBeforeSHA256", hash(sentinelBytes)).put("sentinelAfterSHA256", hash(documentBytes(sentinel)))
                    .put("aExportBeforeSHA256", hash(aExport)).put("aExportAfterSHA256", hash(documentBytes(exports.get("A"))))
                    .put("oldResultCurrentEpochDeliveries", aTask.currentEpochDeliveries.get()).put("bResultState", bResult.state.name()));
            report.put("recreation", new JSONObject().put("sameSession", true).put("sameViewsAndDocuments", true).put("coreRunning", Networkcore.isRunning()).put("generation", generation).put("documents", liveDocuments));
            report.put("accountGates", new JSONArray().put(new JSONObject().put("opid", aOp).put("started", aStarted).put("final", gate(aOp, false)))
                    .put(new JSONObject().put("opid", bOp).put("started", bStarted).put("final", gate(bOp, false))));
            stage("provision-CI-only-device-owner"); policy = new JointSystemProxy(context); system("http", "S"); system("pac", "P");
            stage("preserve-B-C-after-all-system-and-application-proxy-switches");
            preserved("B1"); preserved("C1");
            report.put("afterSystemDocuments", new JSONObject().put("B1", document("B1")).put("C1", document("C1")));
            assertArrayEquals(sentinelBytes, documentBytes(sentinel));
            for (Task task : tasks) assertEquals(1, task.callbacks.get());
            JSONArray finalGates = new JSONArray();
            for (String op : new String[]{aGate, bGate, documentGate}) {
                JSONObject last = gate(op, false); assertEquals(1, last.getInt("opCount")); assertEquals(1, last.getInt("attemptCount")); finalGates.put(last);
            }
            report.put("finalAccountGates", finalGates);
            complete = true;
        } catch (Exception | AssertionError failure) { difference(failure); report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage()); throw failure; }
        finally {
            for (String op : new String[]{aGate, bGate, documentGate}) if (op != null) try { if (gate(op, false) != null) gate(op, true); } catch (Exception ignored) { }
            for (ProfileFileTasks owner : files.values()) owner.close();
            try { stage("restore-system-proxy-and-remove-CI-role"); if (policy != null) policy.close();
                currentStage.put("state", "passed").put("endedAtMillis", SystemClock.elapsedRealtime()); }
            catch (Exception | AssertionError restoration) { complete = false; difference(restoration); report.put("restorationFailure", restoration.getClass().getSimpleName()); throw restoration; }
            finally { save(complete); }
            // Do not dispose the retained session or B/C before the separate cold proof.
        }
    }
}
