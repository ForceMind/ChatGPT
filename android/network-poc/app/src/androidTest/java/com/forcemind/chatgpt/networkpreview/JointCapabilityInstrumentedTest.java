package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.os.Build;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Capability gate only: no Activity, page, account/Profile creation or business network request. */
@RunWith(AndroidJUnit4.class)
public final class JointCapabilityInstrumentedTest {
    @Test public void runtimeSupportsIndependentProfilesAndProxyOverride() throws Exception {
        assertTrue("CI package only", BuildConfig.APPLICATION_ID.endsWith(".ci"));
        assertEquals("http://10.0.2.2:18080/page", BuildConfig.NETWORK_HOME_URL);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject report = new JSONObject().put("schema", 1).put("api", Build.VERSION.SDK_INT)
                .put("capabilityGateOnly", true).put("fullJointAcceptance", false).put("complete", false)
                .put("scope", "No pages/profiles/tasks; this does not prove joint isolation, PAC routing, lifecycle or cleanup");
        AtomicReference<Exception> queryFailure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                // Suffix must precede provider initialization; old and joint Activities share this exact-once entry.
                MainActivity.ensureWebViewSpace();
                PackageInfo provider = WebView.getCurrentWebViewPackage();
                report.put("provider", provider == null ? JSONObject.NULL : provider.packageName)
                        .put("providerVersion", provider == null ? JSONObject.NULL : provider.versionName)
                        .put("providerVersionCode", provider == null ? JSONObject.NULL : provider.getLongVersionCode());
                report.put("multiProfile", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                        .put("proxyOverride", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE));
                ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                ProxyInfo proxy = manager.getDefaultProxy();
                if (proxy == null) report.put("systemProxy", JSONObject.NULL);
                else report.put("systemProxy", new JSONObject().put("host", proxy.getHost()).put("port", proxy.getPort())
                        .put("pacURL", proxy.getPacFileUrl().toString()).put("exclusions", new org.json.JSONArray(java.util.Arrays.asList(proxy.getExclusionList()))));
                report.put("systemProxyRead", true);
            } catch (Exception failure) {
                queryFailure.set(failure);
                try { report.put("queryErrorClass", failure.getClass().getSimpleName()); }
                catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
            }
        });
        boolean supported = queryFailure.get() == null && !report.isNull("provider")
                && report.optBoolean("multiProfile", false) && report.optBoolean("proxyOverride", false)
                && report.optBoolean("systemProxyRead", false);
        report.put("complete", supported);
        File temporary = new File(context.getFilesDir(), "joint-capability.json.tmp");
        Files.write(temporary.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue("publish actual capability evidence", temporary.renameTo(new File(context.getFilesDir(), "joint-capability.json")));
        System.out.println("NETWORK_JOINT_CAPABILITY " + report.toString());
        assertNull("Capability query failed: " + report.toString(), queryFailure.get());
        assertFalse("No active WebView provider: " + report.toString(), report.isNull("provider"));
        assertTrue("MULTI_PROFILE unavailable; shared/default Profile fallback prohibited: " + report.toString(), report.getBoolean("multiProfile"));
        assertTrue("PROXY_OVERRIDE unavailable: " + report.toString(), report.getBoolean("proxyOverride"));
        assertTrue(report.getBoolean("systemProxyRead"));
    }
}
