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
public final class NetworkPanelCandidateTest {
    private static final String BASE = "vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    private static final String PASTE = BASE + "?type=tcp#%E9%A6%99%E6%B8%AF%2001";
    private static final String CAMERA = BASE + "?type=raw#%E7%9B%B8%E6%9C%BA%20%E7%BA%BF%E8%B7%AF";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private NetworkSession session;
    private StudioSheet sheet;

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
                clipboard(BASE + "?type=grpc&serviceName=fixture#Rejected");
                click("粘贴链接");
                awaitNode("未导入：此线路使用 gRPC，当前版本支持 VLESS TCP（RAW）和 WebSocket", false, false);
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
                result.putString("networkImportProof", "clipboard-name,camera-name,manual-name,unsupported-grpc,explicit-save,existing-settings");
                instrumentation.addResults(result);
            } finally {
                host.onActivity(activity -> session.close());
            }
        }
    }

    @Test public void selectionDeletionAndProbePreserveTheActiveRoute() throws Exception {
        assertTrue(BuildConfig.ENVIRONMENT_TEST && BuildConfig.APP_UPDATE_TEST);
        String id = InstrumentationRegistry.getArguments().getString("nodeIdentity");
        assertNotNull(id);
        NetworkSettingsStore store = new NetworkSettingsStore(context);
        NetworkSettingsStore.Data settings = new NetworkSettingsStore.Data();
        String base = "vless://" + id + "@127.0.0.1:";
        settings.nodes.add(NetworkNode.parse(base + "18778?type=raw", "原线路"));
        settings.nodes.add(NetworkNode.parse(base + "18779?type=ws&path=%2Fowned&host=owned.test", "WebSocket 线路"));
        settings.nodes.add(NetworkNode.parse(base + "18780?type=tcp&headerType=http&path=%2Fowned&host=owned.test", "TCP HTTP 线路"));
        settings.enabled = true;
        settings.selected = 0;
        store.write(settings);
        WorkspaceState workspace = new StateStore(context.getFilesDir()).load();
        Intent intent = new Intent(context, AssetWorkbenchActivity.class)
                .putExtra(AssetWorkbenchActivity.ACCOUNT, workspace.current().id);
        try (ActivityScenario<AssetWorkbenchActivity> host = ActivityScenario.launch(intent)) {
            host.onActivity(activity -> {
                session = new NetworkSession(context, () -> {});
                sheet = NetworkSheet.show(activity, session);
            });
            try {
                awaitNode("当前使用 · 已连接 · TCP", false, false);
                assertSelection(host, "原线路", "WebSocket 线路");
                NetworkRoute.Snapshot original = session.routes.snapshot();
                byte[] saved = java.nio.file.Files.readAllBytes(new java.io.File(
                        context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath());

                clickDescription("测试线路 WebSocket 线路");
                awaitPartialText("检查目标可达 · ");
                assertSame("Probe replaced the active route", original, session.routes.snapshot());
                assertSelection(host, "原线路", "WebSocket 线路");
                clickDescription("测试线路 TCP HTTP 线路");
                awaitPartialText("TCP HTTP\n检查目标可达 · ");
                assertSame("Probe replaced the active route", original, session.routes.snapshot());
                assertArrayEquals(saved, java.nio.file.Files.readAllBytes(new java.io.File(
                        context.getNoBackupFilesDir(), "studio-network-v1.enc").toPath()));
                assertTrue(studio.networkcore.Networkcore.isRunning());

                click("WebSocket 线路");
                click("继续");
                awaitNode("当前使用 · 已连接 · WebSocket", false, false);
                assertSelection(host, "WebSocket 线路", "原线路");

                clickDescription("删除线路 原线路");
                awaitNode("删除「原线路」？", false, false);
                click("确认删除");
                awaitPartialText("线路已删除");
                assertEquals(0, store.read().selected);
                assertEquals(2, store.read().nodes.size());
                assertSelection(host, "WebSocket 线路", "TCP HTTP 线路");

                clickDescription("删除线路 WebSocket 线路");
                awaitPartialText("删除后将切回系统网络");
                click("取消");
                assertEquals(2, store.read().nodes.size());
                assertTrue(store.read().enabled);
                clickDescription("删除线路 WebSocket 线路");
                click("切回系统网络并删除");
                awaitNode("线路已删除，已切回系统网络", false, false);
                assertSelection(host, "使用系统网络", "TCP HTTP 线路");
                NetworkSettingsStore.Data after = store.read();
                assertFalse(after.enabled);
                assertEquals(-1, after.selected);
                assertEquals(1, after.nodes.size());
                assertEquals("TCP HTTP 线路", after.nodes.get(0).name);
                assertEquals(NetworkRoute.State.SYSTEM, session.routes.snapshot().state);
                assertEquals(workspace.current().id, new StateStore(context.getFilesDir()).load().current().id);
                Bundle result = new Bundle();
                result.putString("networkSelectionProof", "selected-frame,ws-probe,tcp-http-probe,active-route-preserved,select-ws,delete-inactive,delete-active-to-system");
                instrumentation.addResults(result);
            } finally {
                host.onActivity(activity -> session.close());
            }
        }
    }

    private void assertSelection(ActivityScenario<AssetWorkbenchActivity> host, String active, String other) {
        host.onActivity(activity -> {
            android.view.View activeRow = routeRow(active);
            android.view.View otherRow = routeRow(other);
            assertTrue(activeRow.isSelected());
            assertFalse(otherRow.isSelected());
            android.graphics.drawable.RippleDrawable ripple = (android.graphics.drawable.RippleDrawable) activeRow.getBackground();
            android.graphics.drawable.GradientDrawable frame = (android.graphics.drawable.GradientDrawable) ripple.getDrawable(0);
            assertEquals(0xfff4f4f4, frame.getColor().getDefaultColor());
            assertTrue(((android.widget.LinearLayout) activeRow).getChildAt(0) instanceof android.widget.ImageView);
        });
    }

    private android.view.View routeRow(String name) {
        for (int i = 0; i < sheet.body.getChildCount(); i++) {
            android.view.View child = sheet.body.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout row)) continue;
            for (int j = 0; j < row.getChildCount(); j++) {
                if (!(row.getChildAt(j) instanceof android.widget.LinearLayout copy) || copy.getChildCount() == 0) continue;
                if (copy.getChildAt(0) instanceof android.widget.TextView text && name.contentEquals(text.getText())) return row;
            }
        }
        throw new AssertionError("Route row missing: " + name);
    }

    private void clickDescription(String label) throws Exception {
        assertTrue(awaitNode(label, true, true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private void awaitPartialText(String text) throws Exception {
        waitFor("Visible text: " + text, () -> visibleLabels(root(), 0).contains(text));
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

    private void waitFor(String label, BooleanSupplier predicate) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate.getAsBoolean()) return;
            Thread.sleep(50);
        }
        if (!predicate.getAsBoolean()) {
            AccessibilityNodeInfo active = root();
            fail(label + "; active window: " + (active == null ? "none" : active.getPackageName())
                    + ", controls: " + visibleLabels(active, 0));
        }
    }

    private static String visibleLabels(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 9) return "";
        StringBuilder labels = new StringBuilder();
        if (!node.isPassword() && !node.isEditable()) {
            CharSequence text = node.getText();
            if (text != null) labels.append(text).append(" | ");
        }
        for (int i = 0; i < node.getChildCount() && labels.length() < 1000; i++)
            labels.append(visibleLabels(node.getChild(i), depth + 1));
        return labels.toString();
    }
}
