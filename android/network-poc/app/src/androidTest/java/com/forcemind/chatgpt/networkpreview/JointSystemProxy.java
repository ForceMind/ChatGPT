package com.forcemind.chatgpt.networkpreview;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import static org.junit.Assert.*;

/** Dormant until full joint acceptance calls it. Public DPM APIs; disposable CI emulator only. */
final class JointSystemProxy implements AutoCloseable {
    private final Context context;
    private final DevicePolicyManager policy;
    private final ConnectivityManager connectivity;
    private final ComponentName admin;
    private final ProxyInfo original;
    final JSONObject evidence = new JSONObject();
    private boolean provisioned;
    JointSystemProxy(Context context) throws Exception {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertEquals("1", shell("getprop ro.kernel.qemu").trim());
        this.context = context;
        policy = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        admin = new ComponentName(context, JointProxyAdminReceiver.class);
        original = connectivity.getDefaultProxy();
        evidence.put("originalProxy", describe(original)).put("deviceOwnerBefore", policy.isDeviceOwnerApp(context.getPackageName()))
                .put("configurations", new JSONArray()).put("restored", false).put("roleRemoved", false);
        assertFalse("do not take over an existing owner", policy.isDeviceOwnerApp(context.getPackageName()));
        assertTrue("fresh emulator must have no pre-existing admin", policy.getActiveAdmins() == null || policy.getActiveAdmins().isEmpty());
        String result = shell("dpm set-device-owner " + admin.flattenToString());
        evidence.put("provisionOutput", result).put("deviceOwnerAfter", policy.isDeviceOwnerApp(context.getPackageName()));
        provisioned = policy.isDeviceOwnerApp(context.getPackageName());
        assertTrue("public DPM role unavailable: " + evidence.toString(), provisioned);
    }
    static String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[512]; int count;
            while ((count = input.read(buffer)) >= 0) if (bytes.size() < 4096) bytes.write(buffer, 0, Math.min(count, 4096 - bytes.size()));
            return bytes.toString("UTF-8");
        }
    }
    static Object describe(ProxyInfo proxy) throws Exception {
        if (proxy == null) return JSONObject.NULL;
        JSONArray exclusions = new JSONArray();
        if (proxy.getExclusionList() != null) for (String value : proxy.getExclusionList()) exclusions.put(value);
        return new JSONObject().put("host", proxy.getHost()).put("port", proxy.getPort())
                .put("pacURL", proxy.getPacFileUrl().toString()).put("exclusions", exclusions);
    }
    private boolean matches(ProxyInfo actual, ProxyInfo expected) {
        if (expected == null) return actual == null;
        if (actual == null) return false;
        if (!Uri.EMPTY.equals(expected.getPacFileUrl()))
            return expected.getPacFileUrl().equals(actual.getPacFileUrl()) && actual.getPort() > 0;
        return expected.equals(actual);
    }
    private ProxyInfo await(ProxyInfo expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000; ProxyInfo actual;
        do {
            actual = connectivity.getDefaultProxy();
            if (matches(actual, expected)) return actual;
            Thread.sleep(75);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("system ProxyInfo did not reach requested configuration; expected=" + describe(expected) + " actual=" + describe(actual));
        return actual;
    }
    JSONObject configure(String mode) throws Exception {
        assertTrue(provisioned && policy.isDeviceOwnerApp(context.getPackageName()));
        ProxyInfo requested;
        if ("http".equals(mode)) requested = ProxyInfo.buildDirectProxy("10.0.2.2", 18082);
        else { assertEquals("pac", mode); requested = ProxyInfo.buildPacProxy(Uri.parse("http://10.0.2.2:18080/joint-system.pac")); }
        long started = SystemClock.elapsedRealtime(); policy.setRecommendedGlobalProxy(admin, requested);
        ProxyInfo actual = await(requested);
        JSONObject observation = new JSONObject().put("mode", mode).put("requested", describe(requested))
                .put("actualProxyInfo", describe(actual)).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("deviceOwner", policy.isDeviceOwnerApp(context.getPackageName()));
        evidence.getJSONArray("configurations").put(observation);
        return observation;
    }
    JSONObject current() throws Exception { return new JSONObject().put("actualProxyInfo", describe(connectivity.getDefaultProxy())); }
    @Override public void close() throws Exception {
        if (!provisioned) return;
        Throwable restorationFailure = null;
        try {
            policy.setRecommendedGlobalProxy(admin, original); await(original);
            evidence.put("restoredProxyInfo", describe(connectivity.getDefaultProxy())).put("restored", true);
        } catch (Exception | AssertionError failure) { restorationFailure = failure; evidence.put("restorationError", failure.getClass().getSimpleName()); }
        try {
            evidence.put("removeOutput", shell("dpm remove-active-admin " + admin.flattenToString()));
            evidence.put("roleRemoved", !policy.isDeviceOwnerApp(context.getPackageName()) && !policy.isAdminActive(admin));
            assertTrue("temporary CI device owner was not removed: " + evidence.toString(), evidence.getBoolean("roleRemoved"));
            assertTrue("removing role changed the restored proxy", matches(connectivity.getDefaultProxy(), original));
        } finally { provisioned = false; }
        if (restorationFailure instanceof Exception) throw (Exception) restorationFailure;
        if (restorationFailure != null) throw (AssertionError) restorationFailure;
        assertTrue(evidence.getBoolean("restored"));
    }
}
