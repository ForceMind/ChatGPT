package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Separate cold-process proof. Not wired into the current capability-only entry. */
@RunWith(AndroidJUnit4.class)
public final class JointCleanupInstrumentedTest {
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private String js(WebView web, String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        main(() -> web.evaluateJavascript(code, value -> { result.set(value); done.countDown(); }));
        assertTrue(done.await(10, TimeUnit.SECONDS)); return result.get();
    }
    @Test public void coldCleanupDeletesOnlyOwnedAAndPreservesPersistedBC() throws Exception {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); Bundle args = InstrumentationRegistry.getArguments();
        JSONObject hot = new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(), "joint-profile-result.json").toPath()), StandardCharsets.UTF_8));
        assertTrue("full joint source must complete before cold proof", hot.getBoolean("complete"));
        assertNotEquals("actual process restart required", hot.getInt("pid"), android.os.Process.myPid());
        JSONObject names = hot.getJSONObject("profileNames"), clear = hot.getJSONObject("clearA");
        String a = names.getString("A"), b = names.getString("B"), c = names.getString("C"), origin = hot.getString("origin");
        for (String name : new String[]{a, b, c}) assertTrue(name.matches("joint-[a-zA-Z0-9-]{1,48}-[ABC]"));
        JSONObject proof = new JSONObject().put("schema", 1).put("complete", false).put("oldPid", hot.getInt("pid"))
                .put("pid", android.os.Process.myPid()).put("newViewsAndDocuments", true).put("sameLiveDocumentClaim", false)
                .put("profileNames", names);
        AtomicReference<List<String>> remaining = new AtomicReference<>(); AtomicReference<Exception> cleanupError = new AtomicReference<>();
        JSONArray cleanupResults = new JSONArray();
        // This is deliberately before getProfile/getOrCreateProfile or any WebView.
        main(() -> {
            try {
                MainActivity.ensureWebViewSpace();
                for (JointProfileSession.ClearResult result : JointProfileSession.cleanupDeferredProfiles(context))
                    cleanupResults.put(new JSONObject().put("accountId", result.accountId).put("profileName", result.profileName)
                            .put("epoch", result.epoch).put("profileDeleted", result.profileDeleted));
                remaining.set(new ArrayList<>(ProfileStore.getInstance().getAllProfileNames()));
            } catch (Exception failure) { cleanupError.set(failure); }
        });
        proof.put("cleanupResults", cleanupResults).put("remainingProfiles", new JSONArray(remaining.get() == null ? new ArrayList<String>() : remaining.get()));
        RouteManager routes = new RouteManager(); List<WebView> views = new ArrayList<>();
        try {
            assertNull(cleanupError.get()); assertFalse("A must be physically absent after cold cleanup", remaining.get().contains(a));
            assertTrue(remaining.get().contains(b)); assertTrue(remaining.get().contains(c));
            if (!clear.getBoolean("profileDeleted")) {
                JSONObject tombstones = new JSONObject(new String(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "owned-joint-profile-tombstones.json").toPath()), StandardCharsets.UTF_8));
                boolean found = false;
                for (int i = 0; i < tombstones.getJSONArray("profiles").length(); i++) {
                    JSONObject entry = tombstones.getJSONArray("profiles").getJSONObject(i);
                    if (a.equals(entry.getString("profileName"))) {
                        found = true; assertTrue(entry.getBoolean("deleted"));
                        assertEquals("retain original deferred failure", clear.getString("deletionError"), entry.getString("deletionError"));
                        proof.put("ownedTombstone", entry);
                    }
                }
                assertTrue(found);
            }
            CountDownLatch connected = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
            VlessNode node = VlessNode.parse(args.getString("nodeA"), "A");
            main(() -> routes.switchTo(node, (ok, message) -> { if (!ok) failure.set(message); connected.countDown(); }));
            assertTrue(connected.await(35, TimeUnit.SECONDS)); assertNull(failure.get());
            assertEquals(RouteManager.State.CONNECTED, routes.snapshot().state);
            JSONArray persisted = new JSONArray();
            for (String account : new String[]{"B", "C"}) {
                AtomicReference<WebView> ref = new AtomicReference<>();
                main(() -> {
                    WebView web = new WebView(context); WebViewCompat.setProfile(web, names.optString(account));
                    web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
                    views.add(web); ref.set(web); web.loadUrl(origin + "/joint/" + hot.optString("prefix") + "cold-" + account + "/page");
                });
                WebView web = ref.get(); String expected = JSONObject.quote("A:" + account);
                long deadline = SystemClock.elapsedRealtime() + 20000; String title;
                do { title = js(web, "document.title"); if (expected.equals(title)) break; Thread.sleep(75); } while (SystemClock.elapsedRealtime() < deadline);
                assertEquals(expected, title);
                assertEquals(JSONObject.quote(account), js(web, "localStorage.getItem('ci_joint_storage')"));
                assertEquals("null", js(web, "window.__jointIdentity"));
                persisted.put(new JSONObject().put("accountId", account).put("profileName", names.getString(account))
                        .put("title", title).put("localStorage", js(web, "localStorage.getItem('ci_joint_storage')")));
            }
            proof.put("persisted", persisted).put("complete", true);
        } catch (Exception | AssertionError failure) {
            proof.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage()); throw failure;
        } finally {
            main(() -> { for (WebView web : views) web.destroy(); routes.close(); });
            Files.write(new File(context.getFilesDir(), "joint-cleanup-result.json").toPath(), proof.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("NETWORK_JOINT_CLEANUP " + proof.toString());
        }
    }
}
