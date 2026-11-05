package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Native product controls and encrypted settings only; no WebView documents, screenshots or real nodes. */
@RunWith(AndroidJUnit4.class)
public final class NetworkPanelTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private NetworkSession session;
    private StudioSheet sheet;
    private Activity activity;
    private <T> T ui(Supplier<T> action) {
        AtomicReference<T> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> out.set(action.get()));
        return out.get();
    }
    private void main(Runnable action) { ui(() -> { action.run(); return null; }); }
    private void until(String label, BooleanSupplier predicate) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) { if (predicate.getAsBoolean()) return; Thread.sleep(40); }
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        assertTrue(label + "; active native controls: " + describe(root, 0), predicate.getAsBoolean());
    }
    private String describe(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return "";
        StringBuilder out = new StringBuilder("[" + node.getClassName() + " text=" + node.getText() + " desc=" + node.getContentDescription() + "]");
        for (int i = 0; i < node.getChildCount() && out.length() < 6000; i++) out.append(describe(node.getChild(i), depth + 1));
        return out.toString();
    }
    private List<String> texts(View view) {
        List<String> values = new ArrayList<>();
        if (view instanceof TextView) values.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) values.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return values;
    }
    private boolean shows(String text) { return ui(() -> sheet.isShowing() && texts(sheet.getWindow().getDecorView()).contains(text)); }
    private int observers() {
        return ui(() -> {
            try { java.lang.reflect.Field field = NetworkSession.class.getDeclaredField("panelObservers"); field.setAccessible(true); return ((java.util.Set<?>) field.get(session)).size(); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value, boolean description) {
        if (node == null) return null;
        CharSequence text = description ? node.getContentDescription() : node.getText();
        if (text != null && value.contentEquals(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = find(node.getChild(i), value, description);
            if (child != null) return child;
        }
        return null;
    }
    private AccessibilityNodeInfo node(String value, boolean description) throws Exception {
        AtomicReference<AccessibilityNodeInfo> result = new AtomicReference<>();
        until("Native control not found: " + value, () -> {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            result.set(find(root, value, description)); return result.get() != null;
        });
        return result.get();
    }
    private void click(String text, boolean longClick) throws Exception {
        AccessibilityNodeInfo target = node(text, false);
        while (target != null && !(longClick ? target.isLongClickable() : target.isClickable())) target = target.getParent();
        assertNotNull("Actionable ancestor missing: " + text, target);
        assertTrue("Native click rejected: " + text, target.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK));
    }
    private void input(String label, String value) throws Exception {
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue(node(label, true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    }
    private JSONObject existing(boolean beforeEditing) throws Exception {
        JSONObject expected = new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(), "panel-existing.json").toPath()), StandardCharsets.UTF_8));
        assertEquals(expected.getInt("uid"), android.os.Process.myUid());
        assertEquals(expected.getString("signer"), BetaPanelSeedTest.hash(context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()[0].toByteArray()));
        assertEquals(expected.getString("workspaceSHA256"), BetaPanelSeedTest.hash(Files.readAllBytes(new File(context.getFilesDir(), "workspace.json").toPath())));
        byte[] sealed = Files.readAllBytes(new File(context.getFilesDir(), "panel-draft.bin").toPath());
        assertEquals(expected.getString("draftSHA256"), BetaPanelSeedTest.hash(sealed));
        assertEquals("owned panel draft", DevicePayloadKeys.cipher(context).open(expected.getString("draftId"), sealed));
        if (beforeEditing) assertEquals(expected.getString("nodeCipherSHA256"), BetaPanelSeedTest.hash(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath())));
        return expected;
    }
    @Test public void liveNetworkManagementPreservesContextAndUnsubscribes() throws Exception {
        assertEquals("com.forcemind.chatgpt.profiles.networkbeta", context.getPackageName());
        assertEquals(3000028L, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode());
        JSONObject old = existing(true);
        WorkspaceState state = new StateStore(context.getFilesDir()).load();
        List<String> changes = new CopyOnWriteArrayList<>();
        Runnable record = () -> changes.add(session.status());
        Intent intent = new Intent(context, AssetWorkbenchActivity.class).putExtra(AssetWorkbenchActivity.ACCOUNT, state.current().id);
        try (ActivityScenario<AssetWorkbenchActivity> host = ActivityScenario.launch(intent)) {
            host.onActivity(value -> activity = value);
            main(() -> {
                session = new NetworkSession(context, () -> {});
                sheet = NetworkSheet.show(activity, session);
                assertNotNull(sheet);
                assertTrue(texts(sheet.getWindow().getDecorView()).contains("正在准备网络 · 所有账号共用"));
            });
            until("Live system status was not rendered", () -> shows("使用系统网络 · 所有账号共用"));
            until("Panel did not subscribe exactly once", () -> observers() == 1);
            main(() -> session.observePanel(record));
            existing(true);
            assertFalse(ui(() -> session.connectedTo(0)));
            assertFalse(ui(() -> texts(sheet.getWindow().getDecorView()).stream().anyMatch(text -> text.contains("已连接"))));
            StudioSheet original = sheet;
            click("添加 VLESS 线路", false);
            input("线路名称", "新线路");
            input("VLESS 地址", "vless://00000000-0000-4000-8000-000000000002@fixture.invalid:443?type=tcp&security=tls&sni=fixture.invalid");
            click("保存", false);
            until("Saved route did not appear in the original panel", () -> shows("新线路"));
            assertSame(original, sheet); assertTrue(ui(() -> sheet.isShowing()));
            assertTrue("Save stage must be observable", changes.contains("正在保存线路"));
            assertEquals(2, (int) ui(() -> session.nodes().size()));
            click("新线路", true);
            input("线路名称", "重命名线路"); click("保存", false);
            until("Edited route did not update live", () -> shows("重命名线路"));
            assertSame(original, sheet);
            click("重命名线路", true); click("删除", false);
            node("删除此线路？", false); click("删除", false);
            until("Deletion did not update live", () -> ui(() -> session.nodes().size()) == 1 && !shows("重命名线路"));
            main(() -> session.routes.block("合成连接中断，保留页面"));
            until("Failure reason/retry did not appear", () -> shows("合成连接中断，保留页面") && shows("重新连接"));
            assertSame(original, sheet); assertFalse(ui(() -> session.connectedTo(0)));
            click("重新连接", false);
            until("Retry failed to update the same sheet", () -> shows("使用系统网络 · 所有账号共用") && !shows("重新连接"));
            assertTrue(changes.contains("连接失败")); assertTrue(changes.contains("正在连接"));
            assertFalse("Native UI test must never start a proxy core", ui(() -> session.routes.coreInitialized()));
            main(() -> sheet.dismiss());
            until("Dismiss leaked a panel observer", () -> observers() == 1);
            main(() -> sheet = NetworkSheet.show(activity, session));
            until("Reopened panel did not attach", () -> observers() == 2);
            host.recreate();
            until("Owner destruction left stale dialog/subscriber", () -> !ui(() -> sheet.isShowing()) && observers() == 1);
            host.onActivity(value -> activity = value);
            main(() -> sheet = NetworkSheet.show(activity, session));
            until("Panel did not reopen after recreation", () -> observers() == 2);
            main(() -> session.close());
            until("Closed session left a live panel", () -> !ui(() -> sheet.isShowing()) && observers() == 0);
        } finally {
            main(() -> { if (sheet != null) sheet.dismiss(); if (session != null) { session.removePanelObserver(record); session.close(); } });
        }
        existing(false);
        NetworkSettingsStore.Data saved = new NetworkSettingsStore(context).read();
        assertFalse(saved.enabled); assertEquals(1, saved.nodes.size()); assertEquals("原线路", saved.nodes.get(0).name);
        assertEquals(0, saved.selected);
        JSONObject proof = new JSONObject().put("complete", true).put("api", android.os.Build.VERSION.SDK_INT)
            .put("uid", android.os.Process.myUid()).put("signerSHA256", old.getString("signer"))
            .put("nativeTransitions", new JSONArray(changes)).put("samePanelAcrossOperations", true)
            .put("nativeAddEditDelete", true).put("liveFailureAndRetry", true).put("dismissAndOwnerCleanup", true)
            .put("existingWorkspaceAndEncryptedDraftPreserved", true).put("proxyCoreStarted", false)
            .put("webViewDocumentsLoaded", false).put("screenshots", false).put("realChatGPT", false);
        Bundle result = new Bundle(); result.putString("networkPanelProof", proof.toString());
        InstrumentationRegistry.getInstrumentation().addResults(result);
    }
}
