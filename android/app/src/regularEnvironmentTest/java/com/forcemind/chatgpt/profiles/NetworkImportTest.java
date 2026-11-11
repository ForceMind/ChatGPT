package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NetworkImportTest {
    private static final String BASE = "vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    private static final String PASTE = BASE + "?type=tcp#%E9%A6%99%E6%B8%AF%2001";
    private static final String CAMERA = BASE + "?type=raw#%E7%9B%B8%E6%9C%BA%20%E7%BA%BF%E8%B7%AF";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private NetworkSession session;

    @Test public void clipboardAndCameraOnlySaveOnConfirmation() throws Exception {
        assertTrue(BuildConfig.ENVIRONMENT_TEST && BuildConfig.APP_UPDATE_TEST);
        NetworkSettingsStore store = new NetworkSettingsStore(context);
        NetworkSettingsStore.Data before = new NetworkSettingsStore.Data();
        before.nodes.add(NetworkNode.parse(BASE, "已有线路"));
        store.write(before);
        WorkspaceState workspace = new StateStore(context.getFilesDir()).load();
        Intent intent = new Intent(context, AssetWorkbenchActivity.class)
                .putExtra(AssetWorkbenchActivity.ACCOUNT, workspace.current().id);
        try (ActivityScenario<AssetWorkbenchActivity> host = ActivityScenario.launch(intent)) {
            host.onActivity(activity -> {
                session = new NetworkSession(context, () -> {});
                NetworkSheet.show(activity, session);
            });
            try {
                click("添加 VLESS 线路");
                clipboard(PASTE);
                click("粘贴链接");
                expectText("线路名称", "香港 01");
                replaceText("线路名称", "我改的名称");
                clipboard(BASE + "?type=ws&path=%2Fsocket#Rejected");
                click("粘贴链接");
                waitFor("WebSocket error", () -> {
                    AccessibilityNodeInfo link = find(root(), "VLESS 地址", true);
                    return link != null && link.getError() != null && link.getError().toString().contains("WebSocket");
                });
                expectText("线路名称", "我改的名称");
                assertEquals(1, store.read().nodes.size());
                click("取消");
                assertEquals(1, store.read().nodes.size());

                click("添加 VLESS 线路");
                click("扫码导入");
                expectText("线路名称", "相机 线路");
                assertEquals(1, store.read().nodes.size());
                replaceText("线路名称", "相机自定义");
                click("保存");
                awaitNode("添加 VLESS 线路", false, true);
                NetworkSettingsStore.Data saved = store.read();
                assertEquals(2, saved.nodes.size());
                assertEquals("已有线路", saved.nodes.get(0).name);
                assertEquals("相机自定义", saved.nodes.get(1).name);
                assertEquals(CAMERA, saved.nodes.get(1).link);
                assertFalse(saved.enabled);

                click("添加 VLESS 线路");
                clipboard(PASTE);
                click("粘贴链接");
                expectText("线路名称", "香港 01");
                click("保存");
                awaitNode("添加 VLESS 线路", false, true);
                assertEquals(3, store.read().nodes.size());
                assertEquals(PASTE, store.read().nodes.get(2).link);
                assertEquals(workspace.current().id, new StateStore(context.getFilesDir()).load().current().id);
                Bundle result = new Bundle();
                result.putString("networkImportProof", "clipboard-name,camera-name,manual-name,unsupported-ws,explicit-save,existing-settings");
                instrumentation.addResults(result);
            } finally {
                host.onActivity(activity -> session.close());
            }
        }
    }

    private void clipboard(String value) {
        instrumentation.runOnMainSync(() -> context.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Synthetic fixture", value)));
    }

    private void click(String label) throws Exception {
        assertTrue("Click rejected: " + label,
                awaitNode(label, false, true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private void replaceText(String label, String text) throws Exception {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue(awaitNode(label, true, false).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    }

    private void expectText(String label, String expected) throws Exception {
        waitFor("Text for " + label + ": " + expected, () -> {
            AccessibilityNodeInfo node = find(root(), label, true);
            return node != null && expected.contentEquals(node.getText() == null ? "" : node.getText());
        });
    }

    private AccessibilityNodeInfo awaitNode(String value, boolean description, boolean clickable) throws Exception {
        AtomicReference<AccessibilityNodeInfo> result = new AtomicReference<>();
        waitFor("Native control " + value, () -> {
            AccessibilityNodeInfo node = find(root(), value, description);
            if (clickable) {
                while (node != null && !node.isClickable()) node = node.getParent();
            }
            result.set(node);
            return node != null && node.isEnabled();
        });
        return result.get();
    }

    private AccessibilityNodeInfo root() {
        return instrumentation.getUiAutomation().getRootInActiveWindow();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value, boolean description) {
        if (node == null) return null;
        CharSequence label = description ? node.getContentDescription() : node.getText();
        if (label != null && value.contentEquals(label)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(node.getChild(i), value, description);
            if (match != null) return match;
        }
        return null;
    }

    private static void waitFor(String label, BooleanSupplier predicate) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertTrue(label, predicate.getAsBoolean());
    }
}
