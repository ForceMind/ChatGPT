package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Same-signer instrumentation against the original human APK. No Activity, network or screenshots. */
@RunWith(AndroidJUnit4.class)
public final class BetaBrowserUpgradeTest {
    private static final String PACKAGE = "com.forcemind.chatgpt.profiles.networkbeta";
    private static final String ORIGIN = "https://beta-login-fixture.invalid/";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final AtomicInteger intercepted = new AtomicInteger();
    private final AtomicInteger inlineDocuments = new AtomicInteger();
    private final AtomicInteger inlineIcons = new AtomicInteger();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> requests = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private File file(String name) { return new File(context.getFilesDir(), name); }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private String js(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main(() -> view.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("JavaScript callback timed out", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }
    private void indexedDB(WebView view, String marker, boolean seed) throws Exception {
        // Async JS must finish and return the exact stored value; a resolved evaluateJavascript is not enough.
        String script = "window.__ownedDb={done:false};(async()=>{try{const db=await new Promise((ok,no)=>{"
            + "const q=indexedDB.open('studio-beta-upgrade',1);q.onupgradeneeded=()=>q.result.createObjectStore('markers');"
            + "q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error);});"
            + (seed ? "await new Promise((ok,no)=>{const t=db.transaction('markers','readwrite');"
                + "t.objectStore('markers').put(" + JSONObject.quote(marker) + ",'account');"
                + "t.oncomplete=ok;t.onerror=()=>no(t.error);t.onabort=()=>no(t.error);});" : "")
            + "const value=await new Promise((ok,no)=>{const q=db.transaction('markers').objectStore('markers').get('account');"
            + "q.onsuccess=()=>ok(q.result??null);q.onerror=()=>no(q.error);});db.close();window.__ownedDb={done:true,value};"
            + "}catch(e){window.__ownedDb={done:true,error:String(e)}}})();true";
        assertEquals("true", js(view, script));
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (!"true".equals(js(view, "window.__ownedDb.done"))) {
            assertTrue("IndexedDB did not complete", android.os.SystemClock.elapsedRealtime() < deadline);
            Thread.sleep(100);
        }
        assertEquals("null", js(view, "window.__ownedDb.error??null"));
        assertEquals(marker == null ? "null" : JSONObject.quote(marker), js(view, "window.__ownedDb.value"));
    }
    private WebView page(String profile, boolean mustExist) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<WebView> result = new AtomicReference<>();
        main(() -> {
            assertTrue("Provider must support real per-account profiles",
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE));
            java.util.List<String> beforeStartup = ProfileStore.getInstance().getAllProfileNames();
            WebView view = new WebView(context);
            java.util.List<String> afterStartup = ProfileStore.getInstance().getAllProfileNames();
            System.out.println("OWNED_PROFILE_STARTUP before=" + beforeStartup + " after=" + afterStartup + " expected=" + profile);
            if (mustExist) assertTrue("Original profile missing before load: " + profile,
                afterStartup.contains(profile));
            if (profile != null) WebViewCompat.setProfile(view, profile);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setBlockNetworkLoads(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    requests.add(url + " mainFrame=" + request.isForMainFrame());
                    if (request.isForMainFrame() && "data:text/html;charset=utf-8;base64,".equals(url)) {
                        inlineDocuments.incrementAndGet();
                        return null;
                    }
                    if (!request.isForMainFrame() && "data:,".equals(url)) {
                        inlineIcons.incrementAndGet();
                        return null;
                    }
                    intercepted.incrementAndGet();
                    return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream("Blocked by owned fixture".getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView v, String url) { ready.countDown(); }
            });
            result.set(view);
            view.loadDataWithBaseURL(ORIGIN, "<!doctype html><title>Owned offline Beta login fixture</title><link rel='icon' href='data:,'><p id='owned'>offline-only</p>",
                "text/html", "UTF-8", null);
        });
        assertTrue("Offline fixture did not load", ready.await(25, TimeUnit.SECONDS));
        assertEquals("\"offline-only\"", js(result.get(), "document.getElementById('owned').textContent"));
        assertEquals(ORIGIN.substring(0, ORIGIN.length() - 1), new JSONTokener(js(result.get(), "location.origin")).nextValue());
        return result.get();
    }
    private void close(WebView view) { main(() -> { WebViewCompat.getProfile(view).getCookieManager().flush(); view.destroy(); }); }
    private void setCookie(WebView view, String value) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        main(() -> WebViewCompat.getProfile(view).getCookieManager().setCookie(ORIGIN, value,
            ok -> { accepted.set(ok); done.countDown(); }));
        assertTrue("Cookie write timed out", done.await(10, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, accepted.get());
    }
    private void browser(JSONObject expected, boolean seed) throws Exception {
        JSONArray accounts = expected.getJSONArray("accounts");
        HashSet<String> profiles = new HashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            String profile = account.getString("profile"), marker = account.getString("marker");
            assertTrue(profiles.add(profile));
            WebView view = page(profile, !seed);
            try {
                if (seed) {
                    setCookie(view, "studio_beta_auth=" + marker + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400");
                    setCookie(view, "studio_beta_account=" + marker + "; Path=/; Secure; SameSite=Lax; Max-Age=86400");
                    assertEquals("true", js(view, "localStorage.setItem('studio-beta-account'," + JSONObject.quote(marker) + ");true"));
                }
                AtomicReference<String> raw = new AtomicReference<>();
                main(() -> raw.set(WebViewCompat.getProfile(view).getCookieManager().getCookie(ORIGIN)));
                assertNotNull(raw.get());
                HashSet<String> cookies = new HashSet<>(java.util.Arrays.asList(raw.get().split(";\\s*")));
                assertEquals(new HashSet<>(java.util.Arrays.asList("studio_beta_auth=" + marker, "studio_beta_account=" + marker)), cookies);
                assertEquals(JSONObject.quote("studio_beta_account=" + marker), js(view, "document.cookie"));
                assertEquals(JSONObject.quote(marker), js(view, "localStorage.getItem('studio-beta-account')"));
                indexedDB(view, marker, seed);
            } finally { close(view); }
            // The first account has two tabs. They must use its original shared profile, not a new default.
            if (i == 0) {
                WebView sibling = page(profile, true);
                try {
                    assertEquals(JSONObject.quote(marker), js(sibling, "localStorage.getItem('studio-beta-account')"));
                    indexedDB(sibling, marker, false);
                } finally { close(sibling); }
            }
        }
        WebView defaultPage = page(null, false);
        try {
            AtomicReference<String> raw = new AtomicReference<>();
            main(() -> raw.set(WebViewCompat.getProfile(defaultPage).getCookieManager().getCookie(ORIGIN)));
            assertTrue("Default profile inherited another account's native cookies", raw.get() == null || raw.get().isEmpty());
            assertEquals("\"\"", js(defaultPage, "document.cookie"));
            assertEquals("null", js(defaultPage, "localStorage.getItem('studio-beta-account')"));
            // Do not open/create an IndexedDB in the default profile: the three originals are tested above.
        } finally { close(defaultPage); }
        assertEquals("Expected exactly five owned inline documents: " + requests, 5, inlineDocuments.get());
        assertEquals("Fixture attempted HTTP or unknown resource: " + requests, 0, intercepted.get());
    }
    private JSONObject seedNative(String signer) throws Exception {
        assertFalse("Refuse to overwrite an existing fixture", file("beta-browser-expected.json").exists());
        assertFalse("Fresh emulator must have no workspace", file("workspace.json").exists());
        WorkspaceState state = WorkspaceState.fresh();
        state.addTab(state.current());
        state.addAccount("Synthetic Beta B", "", WorkspaceState.COLORS[1]);
        state.addAccount("Synthetic Beta C", "", WorkspaceState.COLORS[2]);
        for (WorkspaceState.Account account : state.accounts) for (WorkspaceState.Tab tab : account.tabs)
            tab.url = "https://chatgpt.com/c/synthetic-beta-browser-" + tab.id;
        new StateStore(context.getFilesDir()).save(state);
        JSONObject expected = new JSONObject().put("uid", android.os.Process.myUid()).put("signer", signer)
            .put("stateSHA", hash(Files.readAllBytes(file("workspace.json").toPath())));
        JSONArray accounts = new JSONArray(), records = new JSONArray();
        PageRecoveryStore recovery = new PageRecoveryStore(context.getFilesDir(), DevicePayloadKeys.recoveryCipher(context));
        for (WorkspaceState.Account account : state.accounts) {
            accounts.put(new JSONObject().put("id", account.id).put("storageId", account.storageId)
                .put("profile", WorkspaceState.profile(account.storageId)).put("marker", "owned-" + WorkspaceState.id()));
            for (WorkspaceState.Tab tab : account.tabs) {
                String draft = "owned recovery " + tab.id;
                assertTrue(recovery.save(new JSONObject().put("schema", 1).put("accountId", account.id)
                    .put("storageId", account.storageId).put("tabId", tab.id).put("url", tab.url).put("draft", draft)
                    .put("scroll", 321).put("ratio", 0.25).put("capturedAt", 123456789), recovery.generation(account.id, account.storageId)));
                String path = "page-recovery/" + account.id + "/" + account.storageId + "/" + tab.id + ".bin";
                records.put(new JSONObject().put("account", account.id).put("storage", account.storageId).put("tab", tab.id)
                    .put("url", tab.url).put("draft", draft).put("path", path).put("sha", hash(Files.readAllBytes(file(path).toPath()))));
            }
        }
        String id = WorkspaceState.id();
        byte[] sealed = DevicePayloadKeys.cipher(context).seal(id, "owned encrypted handoff");
        Files.write(file("beta-browser-handoff.bin").toPath(), sealed);
        AssetLedger ledger = new AssetLedger(context.getFilesDir(), state.current().id);
        JSONObject asset = ledger.importCopy(new ByteArrayInputStream("owned asset".getBytes(StandardCharsets.UTF_8)),
            "preserved.txt", "text/plain", "import", null);
        NetworkSettingsStore.Data nodes = new NetworkSettingsStore.Data();
        nodes.nodes.add(NetworkNode.parse("vless://00000000-0000-4000-8000-000000000001@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid", "Owned offline node"));
        nodes.selected = 0; nodes.enabled = false; new NetworkSettingsStore(context).write(nodes);
        assertTrue(context.getSharedPreferences("browser-preferences", 0).edit().putString("beta-browser-marker", "preserved").putBoolean("backgroundTasks", false).commit());
        expected.put("accounts", accounts).put("recoveries", records).put("handoffId", id).put("handoffSHA", hash(sealed))
            .put("assetAccount", state.current().id).put("assetId", asset.getString("id"))
            .put("assetSHA", hash(Files.readAllBytes(ledger.verifiedFile(asset.getString("id")).toPath())))
            .put("nodeCipherSHA", hash(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath())));
        Files.write(file("beta-browser-expected.json").toPath(), expected.toString().getBytes(StandardCharsets.UTF_8));
        return expected;
    }
    private void verifyNative(JSONObject expected, String signer) throws Exception {
        assertEquals(expected.getInt("uid"), android.os.Process.myUid());
        assertEquals(expected.getString("signer"), signer);
        assertEquals(expected.getString("stateSHA"), hash(Files.readAllBytes(file("workspace.json").toPath())));
        WorkspaceState state = new StateStore(context.getFilesDir()).load();
        assertEquals(3, state.accounts.size());
        int tabs = 0; for (WorkspaceState.Account account : state.accounts) tabs += account.tabs.size();
        assertEquals(4, tabs);
        JSONArray accounts = expected.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            assertEquals(account.getString("storageId"), state.find(account.getString("id")).storageId);
        }
        byte[] sealed = Files.readAllBytes(file("beta-browser-handoff.bin").toPath());
        assertEquals(expected.getString("handoffSHA"), hash(sealed));
        assertEquals("owned encrypted handoff", DevicePayloadKeys.cipher(context).open(expected.getString("handoffId"), sealed));
        AssetLedger ledger = new AssetLedger(context.getFilesDir(), expected.getString("assetAccount"));
        assertEquals(expected.getString("assetSHA"), hash(Files.readAllBytes(ledger.verifiedFile(expected.getString("assetId")).toPath())));
        assertEquals(expected.getString("nodeCipherSHA"), hash(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath())));
        NetworkSettingsStore.Data nodes = new NetworkSettingsStore(context).read();
        assertEquals(1, nodes.nodes.size()); assertEquals(0, nodes.selected); assertFalse(nodes.enabled);
        assertEquals("fixture.invalid", nodes.nodes.get(0).host);
        PageRecoveryStore recovery = new PageRecoveryStore(context.getFilesDir(), DevicePayloadKeys.recoveryCipher(context));
        JSONArray records = expected.getJSONArray("recoveries"); assertEquals(4, records.length());
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            JSONObject restored = recovery.load(record.getString("account"), record.getString("storage"), record.getString("tab"), record.getString("url"));
            assertNotNull(restored); assertEquals(record.getString("draft"), restored.getString("draft")); assertEquals(321, restored.getInt("scroll"));
            assertEquals(record.getString("sha"), hash(Files.readAllBytes(file(record.getString("path")).toPath())));
        }
        assertFalse(KeepaliveService.enabled(context));
        assertEquals("preserved", context.getSharedPreferences("browser-preferences", 0).getString("beta-browser-marker", ""));
    }
    private JSONObject persistedRegistry(JSONObject expected) throws Exception {
        HashSet<String> names = new HashSet<>();
        JSONArray accounts = expected.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) names.add(accounts.getJSONObject(i).getString("profile"));
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(context.getDataDir().toPath(), 4)) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) files::iterator) {
                if (!path.getFileName().toString().equals("pref_store")
                    || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > 1024 * 1024) continue;
                JSONObject prefs;
                try { prefs = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); }
                catch (org.json.JSONException pendingWrite) { continue; }
                JSONObject profile = prefs.optJSONObject("profile");
                if (profile == null || profile.optJSONArray("list") == null) continue;
                JSONArray list = profile.getJSONArray("list");
                JSONObject mappings = new JSONObject();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    assertFalse("Duplicate persisted profile", mappings.has(entry.getString("name")));
                    mappings.put(entry.getString("name"), entry.getString("path"));
                }
                if (mappings.length() != names.size() || !mappings.keySet().equals(names)) continue;
                return mappings;
            }
        }
        return null;
    }
    private long registryBarrier(JSONObject expected, boolean seed) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        JSONObject actual = persistedRegistry(expected);
        // Establish a genuinely persisted old baseline before its process exits. Never write Chromium prefs.
        while (seed && actual == null && android.os.SystemClock.elapsedRealtime() - start < 20000) {
            Thread.sleep(100);
            actual = persistedRegistry(expected);
        }
        assertNotNull("Three original profile registrations must exist on disk before process exit/reload", actual);
        if (seed) {
            expected.put("browserRegistry", actual);
            Files.write(file("beta-browser-expected.json").toPath(), expected.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            JSONObject old = expected.getJSONObject("browserRegistry");
            assertEquals(old.keySet(), actual.keySet());
            for (String key : old.keySet()) assertEquals("Profile directory mapping changed", old.getString(key), actual.getString(key));
        }
        return android.os.SystemClock.elapsedRealtime() - start;
    }
    @Test public void originalDeliveredBetaBrowserStateSurvivesUpgrade() throws Exception {
        assertEquals(PACKAGE, context.getPackageName());
        assertEquals(35, android.os.Build.VERSION.SDK_INT);
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.INTERNET));
        Bundle args = InstrumentationRegistry.getArguments();
        String phase = args.getString("phase");
        assertTrue("seed".equals(phase) || "baseline-cold".equals(phase) || "upgraded".equals(phase) || "upgraded-cold".equals(phase));
        long version = context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode();
        assertEquals(phase.startsWith("upgraded") ? 3000026L : 3000025L, version);
        String signer = hash(context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo.getApkContentsSigners()[0].toByteArray());
        assertEquals(args.getString("signer"), signer);
        JSONObject expected = "seed".equals(phase) ? seedNative(signer)
            : new JSONObject(new String(Files.readAllBytes(file("beta-browser-expected.json").toPath()), StandardCharsets.UTF_8));
        verifyNative(expected, signer);
        if (!"seed".equals(phase)) registryBarrier(expected, false);
        browser(expected, "seed".equals(phase));
        long registryMillis = registryBarrier(expected, "seed".equals(phase));
        verifyNative(expected, signer);
        main(() -> {
            for (Stage stage : Stage.values())
                assertTrue("App Activity unexpectedly launched: " + stage,
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).isEmpty());
        });
        AtomicReference<String> provider = new AtomicReference<>();
        main(() -> provider.set(WebView.getCurrentWebViewPackage().packageName + " " + WebView.getCurrentWebViewPackage().versionName));
        JSONObject proof = new JSONObject().put("complete", true).put("phase", phase).put("versionCode", version)
            .put("uid", android.os.Process.myUid()).put("signerSHA256", signer).put("accounts", 3).put("tabs", 4)
            .put("persistentProfiles", 3).put("httpOnlySecureCookies", 3).put("localStorageProfiles", 3).put("indexedDBProfiles", 3)
            .put("encryptedRecoveryRecords", 4).put("defaultProfileEmpty", true).put("provider", provider.get())
            .put("workspaceSHA256", expected.getString("stateSHA")).put("expectedSHA256", hash(Files.readAllBytes(file("beta-browser-expected.json").toPath())))
            .put("persistedRegistryVerified", true).put("registryBarrierMillis", registryMillis)
            .put("interceptedRequests", intercepted.get()).put("inlineDocumentCallbacks", inlineDocuments.get())
            .put("inlineIconCallbacks", inlineIcons.get()).put("inlineSyntheticPagesOnly", true).put("appActivityLaunched", false);
        Bundle result = new Bundle(); result.putString("betaBrowserUpgradeProof", proof.toString());
        InstrumentationRegistry.getInstrumentation().addResults(result);
    }
}
