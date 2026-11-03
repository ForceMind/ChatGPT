package com.forcemind.chatgpt.profiles;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NetworkCoreConfigTest {
    private static final String BASE="vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    @Test public void coreOnlyExposesLoopbackAndNoFallbackOutbound() throws Exception {
        JSONObject c=new JSONObject(NetworkCoreConfig.build(NetworkNode.parse(BASE,"A"),31001));
        assertEquals(1,c.getJSONArray("inbounds").length());assertEquals("127.0.0.1",c.getJSONArray("inbounds").getJSONObject(0).getString("listen"));
        assertEquals(1,c.getJSONArray("outbounds").length());assertEquals("vless",c.getJSONArray("outbounds").getJSONObject(0).getString("protocol"));
        assertEquals("none",c.getJSONObject("log").getString("loglevel"));assertFalse(c.has("routing"));
    }
    @Test public void releaseTrustAndRealityNeverDisableAuthentication() throws Exception {
        JSONObject c=new JSONObject(NetworkCoreConfig.build(NetworkNode.parse(BASE+"?security=tls&sni=secure.test","TLS"),31001));
        JSONObject tls=c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("tlsSettings");
        assertFalse(tls.getBoolean("allowInsecure"));assertEquals("secure.test",tls.getString("serverName"));assertFalse(tls.has("certificates"));
        JSONObject r=new JSONObject(NetworkCoreConfig.build(NetworkNode.parse(BASE+"?security=reality&sni=secure.test&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123","R"),31001));
        JSONObject reality=r.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("realitySettings");
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",reality.getString("password"));assertEquals("0123",reality.getString("shortId"));
    }
}
