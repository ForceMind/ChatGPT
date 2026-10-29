package com.forcemind.chatgpt.networkpreview;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CoreConfigTest {
    private static final String BASE = "vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    private JSONObject config(String query) throws Exception { return new JSONObject(CoreConfig.build(VlessNode.parse(BASE + query, "x"), 34001)); }
    private JSONObject stream(JSONObject config) throws Exception { return config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings"); }
    @Test public void onlyLoopbackHttpInboundAndSelectedOutbound() throws Exception {
        JSONObject config = config("?type=tcp");
        assertEquals(1, config.getJSONArray("inbounds").length()); assertEquals(1, config.getJSONArray("outbounds").length());
        JSONObject inbound = config.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("127.0.0.1", inbound.getString("listen")); assertEquals("http", inbound.getString("protocol")); assertEquals(34001, inbound.getInt("port"));
        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(0).getString("protocol")); assertFalse(config.has("routing"));
        assertEquals("none", config.getJSONObject("log").getString("loglevel"));
    }
    @Test public void tlsAlwaysVerifiesAndWsKeepsHostPath() throws Exception {
        JSONObject s = stream(config("?type=ws&security=tls&sni=secure.test&fp=chrome&alpn=http%2F1.1&path=%2Fws&host=cdn.test"));
        assertFalse(s.getJSONObject("tlsSettings").getBoolean("allowInsecure")); assertEquals("secure.test", s.getJSONObject("tlsSettings").getString("serverName"));
        assertEquals("http/1.1", s.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0));
        assertEquals("cdn.test", s.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"));
        assertEquals("/ws", s.getJSONObject("wsSettings").getString("path"));
    }
    @Test public void realityUsesPinnedCorePasswordField() throws Exception {
        JSONObject s = stream(config("?type=raw&security=reality&sni=secure.test&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123&flow=xtls-rprx-vision"));
        JSONObject r = s.getJSONObject("realitySettings");
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", r.getString("password")); assertEquals("0123", r.getString("shortId")); assertEquals("chrome", r.getString("fingerprint"));
        assertFalse(s.has("tlsSettings")); assertEquals("raw", s.getString("network"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidListenerPort() { CoreConfig.build(VlessNode.parse(BASE, "x"), 0); }
    @Test public void acceptsExplicitNoHeaderWithoutEnablingCamouflage() throws Exception {
        assertEquals("raw", stream(config("?type=tcp&headerType=none")).getString("network"));
        try { config("?type=tcp&headerType=http"); fail("unsupported header camouflage accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
