package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.webkit.WebViewCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Drives the real update sheet in both app processes on the owned CI emulator. */
@RunWith(AndroidJUnit4.class)
public final class AppUpdateFlowTest {
    private static final String PACKAGE = RegularEnvironmentUpgradeTest.PACKAGE;
    private static final String ORIGIN = "http://127.0.0.1:18777";
    private final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    private final UiAutomation ui = instrument.getUiAutomation();
    private final android.content.Context context = instrument.getTargetContext();

    private static void waitFor(String description, long seconds, Supplier<Boolean> condition) {
        long end = SystemClock.elapsedRealtime() + seconds * 1000;
        do {
            if (condition.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        fail("Timed out: " + description);
    }

    private <T> T onMain(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        instrument.runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label, boolean prefix) {
        if (node == null) return null;
        for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription()}) {
            if (value != null && (prefix ? value.toString().startsWith(label) : label.contentEquals(value))) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(node.getChild(i), label, prefix);
            if (match != null) return match;
        }
        return null;
    }

    private static boolean percentVisible(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        if (text != null && text.toString().matches("[1-9][0-9]?% · .+ MiB")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (percentVisible(node.getChild(i))) return true;
        }
        return false;
    }

    private void click(String label, boolean prefix) {
        waitFor("click " + label, 30, () -> {
            AccessibilityNodeInfo node = find(ui.getRootInActiveWindow(), label, prefix);
            while (node != null && !node.isClickable()) node = node.getParent();
            return node != null && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        });
    }

    private void see(String label, boolean prefix) {
        waitFor("visible " + label, 45, () -> find(ui.getRootInActiveWindow(), label, prefix) != null);
    }

    private void openUpdate() {
        click("更多功能", false);
        click("应用更新", false);
        see("应用更新", false);
    }

    private void back() throws Exception {
        ui.executeShellCommand("input keyevent KEYCODE_BACK").close();
    }

    private void mode(String name) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(ORIGIN + "/control/" + name).openConnection(Proxy.NO_PROXY);
        try {
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);
            assertEquals(204, request.getResponseCode());
        } finally {
            request.disconnect();
        }
    }

    private AppUpdateTask task() {
        return AppUpdateTask.get(context);
    }

    private int code() throws Exception {
        return (int) context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode();
    }

    private void invalidDownload(String mode) throws Exception {
        mode(mode);
        click("重新检查", false);
        click("下载更新", false);
        see("重试下载", false);
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").exists());
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").exists());
        assertEquals(3000037, code());
    }

    @Test public void downloadAndRequestSystemConfirmation() throws Exception {
        assertTrue(BuildConfig.APP_UPDATE_TEST);
        assertEquals(3000037, code());
        assertEquals(RegularEnvironmentUpgradeTest.args().getString("signer"), RegularEnvironmentUpgradeTest.signer());
        JSONObject golden = RegularEnvironmentUpgradeTest.seed();
        RegularEnvironmentUpgradeTest.browser(golden, true);
        RegularEnvironmentUpgradeTest.registryBarrier(golden, true);
        RegularEnvironmentUpgradeTest.verifyNative(golden, false);

        String node = RegularEnvironmentUpgradeTest.args().getString("ownedUpdateNode");
        assertNotNull(node);
        assertTrue(node.matches("vless://[0-9a-f-]{36}@127\\.0\\.0\\.1:18778"));
        NetworkSettingsStore.Data routeSettings = new NetworkSettingsStore.Data();
        routeSettings.nodes.add(NetworkNode.parse(node, "Owned update line"));
        routeSettings.selected = 0;
        routeSettings.enabled = true;
        new NetworkSettingsStore(context).write(routeSettings);

        StateStore store = new StateStore(context.getFilesDir());
        WorkspaceState state = store.update(workspace -> {
            WorkspaceState.Account legacy = workspace.addAccount("Owned update WebView", "", WorkspaceState.COLORS[0]);
            legacy.current().url = "https://chatgpt.com/c/owned-update-first";
            legacy.current().title = "Owned update first";
            legacy.current().customTitle = true;
            WorkspaceState.Tab second = workspace.addTab(legacy);
            second.url = "https://chatgpt.com/c/owned-update-second";
            second.title = "Owned update second";
            second.customTitle = true;
            workspace.addEnvironmentAccount("Owned update Gecko", "", WorkspaceState.COLORS[1], "en-US", "UTC");
            workspace.activeAccount = legacy.id;
        });
        MainActivity.handoffFixture = (owner, request) -> {
            if (!request.isForMainFrame()) return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            String html = "<!doctype html><title>Owned updater</title><p>offline</p><textarea></textarea>"
                    + "<script>window.ownedDocument=Math.random().toString()</script>";
            return new android.webkit.WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        };
        MainActivity main = (MainActivity) instrument.startActivitySync(new Intent(context, MainActivity.class)
                .putExtra(EnvironmentLauncher.ACCOUNT_ID, state.current().id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NetworkRoute route = onMain(() -> BrowserMemory.get(context).network().routes);
        waitFor("owned route connected", 30, () -> route.snapshot().state == NetworkRoute.State.CONNECTED);
        assertEquals("Owned update line", route.snapshot().node.name);
        waitFor("second WebView exists", 30, () -> onMain(main::recoveryCurrentWeb) != null);
        WebView second = onMain(main::recoveryCurrentWeb);
        waitFor("second inline fixture committed", 25, () -> onMain(second::getProgress) == 100);
        String secondDoc = RegularEnvironmentUpgradeTest.js(second, "window.ownedDocument");
        assertNotEquals("null", secondDoc);
        click("切换标签页：Owned update first", false);
        waitFor("first WebView exists", 20, () -> onMain(main::recoveryCurrentWeb) != second);
        WebView first = onMain(main::recoveryCurrentWeb);
        assertNotNull(first);
        waitFor("first inline fixture committed", 25, () -> onMain(first::getProgress) == 100);
        String firstDoc = RegularEnvironmentUpgradeTest.js(first,
                "document.querySelector('textarea').value='owned draft';window.ownedDocument");
        assertNotEquals("null", firstDoc);

        AtomicReference<Boolean> cookieSet = new AtomicReference<>();
        instrument.runOnMainSync(() -> WebViewCompat.getProfile(first).getCookieManager().setCookie(
                ORIGIN, "owned_updater_auth=synthetic; Path=/; HttpOnly", cookieSet::set));
        waitFor("account cookie recorded for exact update origin", 10, () -> Boolean.TRUE.equals(cookieSet.get()));
        assertEquals("owned_updater_auth=synthetic",
                onMain(() -> WebViewCompat.getProfile(first).getCookieManager().getCookie(ORIGIN)));

        openUpdate();
        mode("current");
        click("检查更新", false);
        see("当前没有可用更新", false);
        mode("wrong-certificate");
        click("检查更新", false);
        see("检查更新未完成", true);
        assertEquals(3000037, code());
        mode("slow");
        click("检查更新", false);
        see("发现新版本", true);
        click("下载更新", false);
        waitFor("positive percentage bytes", 30, () -> onMain(() -> task().received() > task().release().size / 100));
        waitFor("percentage text visible", 15, () -> percentVisible(ui.getRootInActiveWindow()));

        ui.executeShellCommand("input keyevent KEYCODE_HOME").close();
        waitFor("app in background", 15, () -> {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            return root != null && !PACKAGE.contentEquals(root.getPackageName());
        });
        long backgroundStart = onMain(() -> task().received());
        waitFor("download advances in background", 15, () -> onMain(() -> task().received()) > backgroundStart);
        context.startActivity(new Intent(context, AppUpdateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        see("取消下载", false);

        NetworkRoute.Snapshot held = route.snapshot();
        AtomicReference<Boolean> switched = new AtomicReference<>();
        instrument.runOnMainSync(() -> route.useDirect((ok, error) -> switched.set(ok)));
        assertEquals(Boolean.FALSE, switched.get());
        assertSame(held, route.snapshot());
        back();
        click("切换账号，当前：", true);
        click("Owned update Gecko", false);
        see("切换账号，当前：Owned update Gecko", false);
        openUpdate();
        see("取消下载", false);
        click("取消下载", false);
        see("已取消下载", false);
        assertEquals(0, route.activeTransfers());
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").exists());

        invalidDownload("bad-digest");
        invalidDownload("not-an-apk");
        invalidDownload("wrong-version");
        invalidDownload("wrong-package");
        mode("valid");
        click("重新检查", false);
        click("下载更新", false);
        see("重试下载", false);
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").exists());
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").exists());
        click("重试下载", false);
        see("安装更新", false);
        assertTrue(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").isFile());

        back();
        click("切换账号，当前：Owned update Gecko", false);
        click("Owned update WebView", false);
        see("切换账号，当前：Owned update WebView", false);
        assertSame(first, onMain(main::recoveryCurrentWeb));
        assertEquals(firstDoc, RegularEnvironmentUpgradeTest.js(first, "window.ownedDocument"));
        assertEquals("\"owned draft\"", RegularEnvironmentUpgradeTest.js(first, "document.querySelector('textarea').value"));
        assertEquals("owned_updater_auth=synthetic",
                onMain(() -> WebViewCompat.getProfile(first).getCookieManager().getCookie(ORIGIN)));
        click("切换标签页：Owned update second", false);
        waitFor("second retained WebView", 20, () -> onMain(main::recoveryCurrentWeb) == second);
        assertEquals(secondDoc, RegularEnvironmentUpgradeTest.js(second, "window.ownedDocument"));
        RegularEnvironmentUpgradeTest.verifyNative(golden, true);

        openUpdate();
        assertFalse(context.getPackageManager().canRequestPackageInstalls());
        click("安装更新", false);
        waitFor("system install-source settings", 25, () -> {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            return root != null && "com.android.settings".contentEquals(root.getPackageName());
        });
        assertEquals(3000037, code());
        assertFalse(context.getPackageManager().canRequestPackageInstalls());
        mode("authorize");
        waitFor("emulator grants install-source app-op", 20, () -> context.getPackageManager().canRequestPackageInstalls());
        back();
        click("安装更新", false);
        see("确认安装", false);
        assertEquals(3000037, code());
        assertTrue(context.getPackageManager().getPackageInstaller().getMySessions().stream()
                .anyMatch(session -> PACKAGE.equals(session.getAppPackageName())));
        click("确认安装", false);
        Bundle result = new Bundle();
        result.putString("appUpdateSourceProof", "both-entries,progress,background,route-lease,cancel,bad-packages,retry,permission,live-tabs,draft,system-confirmation");
        instrument.addResults(result);
    }

}
