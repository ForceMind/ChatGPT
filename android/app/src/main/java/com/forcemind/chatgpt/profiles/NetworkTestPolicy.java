package com.forcemind.chatgpt.profiles;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;

/** CI policy is generated in the isolated build type; release has no fixture trust or destination. */
final class NetworkTestPolicy {
    static boolean owned(String value) {
        if (!BuildConfig.NETWORK_TEST || value == null) return false;
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme()) && u.getRawUserInfo() == null
                    && ("network-fixture.test".equals(u.getHost()) || "files.network-fixture.test".equals(u.getHost()))
                    && u.getPort() == 18480;
        } catch (IllegalArgumentException error) { return false; }
    }
    static String home() { return BuildConfig.NETWORK_TEST ? BuildConfig.NETWORK_HOME_URL : "https://chatgpt.com/"; }
    static String probeURL() { return BuildConfig.NETWORK_TEST ? BuildConfig.NETWORK_PROBE_URL : "https://chatgpt.com/"; }
    static void applyTrust(JSONObject tls, NetworkNode node) throws org.json.JSONException {
        if (!BuildConfig.NETWORK_TEST || !"10.0.2.2".equals(node.host) || (node.port != 19443 && node.port != 19444)) return;
        try {
            String pem = (String) Class.forName("com.forcemind.chatgpt.profiles.GeneratedNetworkTrust").getField("PEM").get(null);
            tls.put("disableSystemRoot", true);
            tls.put("certificates", new JSONArray().put(new JSONObject().put("usage", "verify")
                    .put("certificate", new JSONArray(pem.split("\\r?\\n")))));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Missing CI-only trust", failure); }
    }
    private NetworkTestPolicy() {}
}
