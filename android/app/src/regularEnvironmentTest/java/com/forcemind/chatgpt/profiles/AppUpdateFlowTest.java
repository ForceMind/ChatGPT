package com.forcemind.chatgpt.profiles;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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

    private void openRecoveryFixture() throws Exception {
        assertTrue(BuildConfig.APP_UPDATE_TEST);
        assertEquals(3000037, code());
        assertEquals(RegularEnvironmentUpgradeTest.args().getString("signer"), RegularEnvironmentUpgradeTest.signer());
        MainActivity.handoffFixture = (owner, request) -> new android.webkit.WebResourceResponse(
                "text/html", "UTF-8", new ByteArrayInputStream("<p>Owned offline fixture</p>".getBytes(StandardCharsets.UTF_8)));
        instrument.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NetworkRoute route = onMain(() -> BrowserMemory.get(context).network().routes);
        waitFor("owned recovery line connected", 30, () -> route.snapshot().state == NetworkRoute.State.CONNECTED);
        openUpdate();
    }

    private void recoveryProof(String stage) {
        Bundle result = new Bundle();
        result.putString("appUpdateRecoveryStage", stage + ":" + android.os.Process.myPid());
        instrument.addResults(result);
    }

    @Test public void startDownloadBeforeExit() throws Exception {
        String node = RegularEnvironmentUpgradeTest.args().getString("ownedUpdateNode");
        assertNotNull(node);
        assertTrue(node.matches("vless://[0-9a-f-]{36}@127\\.0\\.0\\.1:18778"));
        NetworkSettingsStore.Data route = new NetworkSettingsStore.Data();
        route.nodes.add(NetworkNode.parse(node, "Owned update line"));
        route.selected = 0;
        route.enabled = true;
        new NetworkSettingsStore(context).write(route);
        openRecoveryFixture();
        click("检查更新", false);
        see("发现新版本", true);
        click("下载更新", false);
        waitFor("partial download with persisted offer", 30, () -> onMain(() ->
                task().phase() == AppUpdateTask.Phase.DOWNLOADING && task().received() > 0
                && task().received() < task().release().size));
        assertTrue(RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").length() > 0);
        assertFalse(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").exists());
        String marker = android.os.Process.myPid() + ":" +
                RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").length();
        java.nio.file.Files.write(RegularEnvironmentUpgradeTest.file("app-update-exit-proof.txt").toPath(),
                marker.getBytes(StandardCharsets.UTF_8));
        // Host must force-stop this live download. A normal test teardown may cancel it first.
        SystemClock.sleep(120000);
        fail("Owned emulator did not interrupt the live download");
    }

    @Test public void retryAfterDownloadExit() throws Exception {
        openRecoveryFixture();
        see("上次下载已中断，请重试", false);
        see("重试下载", false);
        waitFor("old incomplete file removed", 10,
                () -> !RegularEnvironmentUpgradeTest.file("app-update/download.part.apk").exists());
        click("重试下载", false);
        see("安装更新", false);
        assertTrue(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").isFile());
        recoveryProof("ready");
    }

    private void cancelOwnedSystemPrompt() {
        waitFor("real system installer cancel button", 30, () -> {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return false;
            String owner = root.getPackageName().toString();
            if (!java.util.Arrays.asList("com.android.packageinstaller", "com.google.android.packageinstaller",
                    "com.android.permissioncontroller", "com.google.android.permissioncontroller").contains(owner)) return false;
            for (String label : new String[]{"Cancel", "CANCEL", "取消"}) {
                AccessibilityNodeInfo button = find(root, label, false);
                if (button != null && button.isEnabled() && button.isClickable()
                        && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            return false;
        });
    }

    @Test public void installAfterReadyExitAndRetryAfterCancel() throws Exception {
        openRecoveryFixture();
        see("更新已下载，可以安装", false);
        assertTrue(context.getPackageManager().canRequestPackageInstalls());
        click("安装更新", false);
        see("确认安装", false);
        click("确认安装", false);
        cancelOwnedSystemPrompt();
        see("安装未完成，可以重试", false);
        see("安装更新", false);
        assertEquals(3000037, code());
        assertTrue(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").isFile());
        click("安装更新", false);
        see("确认安装", false);
        click("确认安装", false);
        recoveryProof("reopened-retry-confirmation");
    }

    private int autoRequests(String key) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(ORIGIN + "/control/stats").openConnection(Proxy.NO_PROXY);
        try {
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);
            assertEquals(200, request.getResponseCode());
            try (java.io.InputStream input = request.getInputStream()) {
                return new JSONObject(readOutput(input)).getInt(key);
            }
        } finally { request.disconnect(); }
    }

    private static String readOutput(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private String shellOutput(String command) throws java.io.IOException {
        // Android 9's UiAutomation repeater can die with EPIPE if its output pipe is closed early.
        try (ParcelFileDescriptor.AutoCloseInputStream stream =
                     new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
            return readOutput(stream);
        }
    }

    private void reopenGecko(WorkspaceState.Account account) throws java.io.IOException {
        String result = shellOutput("am start -W -n " + PACKAGE + "/.MainActivity --es " + EnvironmentLauncher.ACCOUNT_ID + " " + account.id);
        assertFalse(result, result.contains("Error:") || result.contains("Exception"));
    }

    private void expectAutoRequests(String key, int expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45000;
        int count;
        do {
            count = autoRequests(key);
            if (count >= expected) break;
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals(key, expected, count);
    }

    @Test public void configurePrivateUpdateSource() {
        assertTrue(BuildConfig.APP_UPDATE_ACCEPTANCE && BuildConfig.APP_UPDATE_TEST);
        AppUpdateActivity update = (AppUpdateActivity) instrument.startActivitySync(
                new Intent(context, AppUpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        see("应用更新", false);
        click("验收更新源", false);
        see("真机验收更新源", false);
        AccessibilityNodeInfo input = find(ui.getRootInActiveWindow(), "验收访问码", false);
        assertNotNull(input);
        String syntheticKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Bundle key = new Bundle();
        key.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, syntheticKey);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, key));
        click("启用", false);
        assertEquals(syntheticKey, onMain(() -> task().acceptanceKey()));
        see("当前使用真机验收更新源", true);
        click("验收更新源", false);
        click("使用官网", false);
        assertEquals("", onMain(() -> task().acceptanceKey()));
        see("当前使用官网更新源", true);
        click("关闭弹层", false);
        waitFor("update sheet finished", 15, () -> onMain(() -> update.isFinishing() || update.isDestroyed()));
        Bundle evidence = new Bundle();
        evidence.putString("privateUpdateSourceProof", "enabled,official,closed");
        instrument.addResults(evidence);
    }

    @Test public void automaticNoonAndExplicitDownload() throws Exception {
        assertTrue(BuildConfig.APP_UPDATE_TEST && BuildConfig.APP_UPDATE_AUTO);
        assertEquals(BuildConfig.APP_UPDATE_ACCEPTANCE ? 3000039 : 3000038, code());
        assertEquals(RegularEnvironmentUpgradeTest.args().getString("signer"), RegularEnvironmentUpgradeTest.signer());
        AtomicReference<java.time.Instant> now = new AtomicReference<>(java.time.Instant.parse("2026-11-10T03:50:00Z"));
        AppUpdateAuto.clockForTest(now::get);
        String node = RegularEnvironmentUpgradeTest.args().getString("ownedUpdateNode");
        assertNotNull(node);
        assertTrue(node.matches("vless://[0-9a-f-]{36}@127\\.0\\.0\\.1:18778"));
        NetworkSettingsStore.Data route = new NetworkSettingsStore.Data();
        route.nodes.add(NetworkNode.parse(node, "Owned update line"));
        route.selected = 0;
        route.enabled = true;
        new NetworkSettingsStore(context).write(route);
        WorkspaceState state = new StateStore(context.getFilesDir()).update(workspace -> {
            WorkspaceState.Account legacy = workspace.addAccount("Owned auto WebView", "", WorkspaceState.COLORS[0]);
            legacy.current().url = "https://chatgpt.com/c/owned-update-auto";
            workspace.addEnvironmentAccount("Owned auto Gecko", "", WorkspaceState.COLORS[1], "en-US", "UTC");
            workspace.activeAccount = legacy.id;
        });
        WorkspaceState.Account gecko = state.accounts.stream().filter(WorkspaceState.Account::isEnvironment).findFirst().get();
        MainActivity.handoffFixture = (owner, request) -> new android.webkit.WebResourceResponse(
                "text/html", "UTF-8", new ByteArrayInputStream("<!doctype html><title>Owned updater</title><p>Owned update fixture</p>".getBytes(StandardCharsets.UTF_8)));
        MainActivity main = (MainActivity) instrument.startActivitySync(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).putExtra(EnvironmentLauncher.ACCOUNT_ID, state.current().id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        expectAutoRequests("feed", 1);
        waitFor("startup check completed", 20, () -> onMain(() -> task().phase() == AppUpdateTask.Phase.IDLE));
        waitFor("owned WebView page loaded", 25, () -> onMain(() -> {
            WebView web = main.recoveryCurrentWeb();
            return web != null && "Owned updater".equals(web.getTitle()) && web.getProgress() == 100;
        }));
        assertEquals(0, autoRequests("apk"));

        click("切换账号，当前：Owned auto WebView", false);
        click("Owned auto Gecko", false);
        see("切换账号，当前：Owned auto Gecko", false);
        assertEquals(1, autoRequests("feed"));
        mode("resume");
        shellOutput("input keyevent KEYCODE_HOME");
        waitFor("app is backgrounded", 15, () -> {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            return root != null && !PACKAGE.contentEquals(root.getPackageName());
        });
        now.set(java.time.Instant.parse("2026-11-10T04:00:00Z"));
        String job = shellOutput("cmd jobscheduler run -f " + PACKAGE + " " + AppUpdateAuto.JOB);
        assertTrue(job, job.contains("Running job"));
        expectAutoRequests("feed", 2);
        waitFor("background update available", 20, () -> onMain(() -> task().phase() == AppUpdateTask.Phase.AVAILABLE));
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        assertNotNull(root);
        assertFalse(PACKAGE.contentEquals(root.getPackageName()));
        assertEquals(0, autoRequests("apk"));

        reopenGecko(gecko);
        see("发现新版本", false);
        click("稍后", false);
        assertEquals(2, autoRequests("feed"));
        assertEquals(0, autoRequests("apk"));

        shellOutput("input keyevent KEYCODE_HOME");
        waitFor("app is backgrounded on the next day", 15, () -> {
            AccessibilityNodeInfo background = ui.getRootInActiveWindow();
            return background != null && !PACKAGE.contentEquals(background.getPackageName());
        });
        now.set(java.time.Instant.parse("2026-11-11T04:30:00Z"));
        reopenGecko(gecko);
        expectAutoRequests("feed", 3);
        see("发现新版本", false);
        click("查看更新", false);
        see("下载更新", false);
        assertEquals(0, autoRequests("apk"));
        click("重新检查", false);
        expectAutoRequests("feed", 4);
        see("下载更新", false);
        assertEquals(0, autoRequests("apk"));
        click("下载更新", false);
        expectAutoRequests("apk", 1);
        see("安装更新", false);
        assertTrue(RegularEnvironmentUpgradeTest.file("app-update/ready.apk").isFile());
        Bundle result = new Bundle();
        result.putString("appUpdateAutoProof", "startup,engine-dedupe,noon-job,foreground-notice,missed-slot,manual-check,explicit-download");
        instrument.addResults(result);
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
