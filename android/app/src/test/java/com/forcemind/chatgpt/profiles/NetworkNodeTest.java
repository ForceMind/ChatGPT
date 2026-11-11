package com.forcemind.chatgpt.profiles;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NetworkNodeTest {
    private static final String BASE="vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    @Test public void rawTlsAndRealityPreserveRequiredIdentity() {
        assertEquals("raw",NetworkNode.parse(BASE+"?type=tcp", "A").transport);
        NetworkNode tls=NetworkNode.parse(BASE+"?type=raw&security=tls&sni=secure.test&alpn=http%2F1.1", "TLS");
        assertEquals("secure.test",tls.options.get("sni"));assertEquals("tls",tls.security);
        assertEquals("reality",NetworkNode.parse(BASE+"?security=reality&sni=secure.test&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123", "R").security);
    }
    @Test public void unsupportedTransportAndAmbiguityNeverDowngrade() {
        for(String suffix:new String[]{"?type=grpc", "?type=xhttp", "?type=tcp&type=raw", "?security=tls&allowInsecure=1", "?security=none&sni=x", "?security=tls&unknown=1"}) {
            try { NetworkNode.parse(BASE+suffix,"x");fail("Unsupported configuration accepted"); }
            catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains(BASE));assertFalse(expected.getMessage().contains("00000000"));}
        }
    }
    @Test public void linkNameDecodesAndManualNameTakesPrecedence() {
        String link = BASE + "#%E9%A6%99%E6%B8%AF%20A+B";
        assertEquals("香港 A+B", NetworkNode.parse(link, null).name);
        assertEquals("自定义", NetworkNode.parse(link, "自定义").name);
        assertEquals("线路", NetworkNode.parse(BASE, "").name);
    }
    @Test public void unsupportedTransportIsReportedBeforeItsTransportSpecificOptions() {
        String[][] cases = {
            {"grpc&serviceName=fixture", "gRPC"},
            {"xhttp&mode=packet-up", "XHTTP"},
            {"httpupgrade&path=%2Fupgrade", "HTTP Upgrade"}
        };
        for (String[] row : cases) {
            try {
                NetworkNode.parse(BASE + "?type=" + row[0], null);
                fail("Unsupported transport accepted");
            } catch (IllegalArgumentException error) {
                assertEquals("此线路使用 " + row[1] + "，当前版本支持 VLESS TCP（RAW）和 WebSocket", error.getMessage());
            }
        }
    }
    @Test public void webSocketAndHttpHeaderAreMappedToXray() throws Exception {
        NetworkNode ws = NetworkNode.parse(BASE + "?type=ws&security=tls&host=front.test&path=%2Fsocket", null);
        assertEquals("ws", ws.transport);
        org.json.JSONObject probe = new org.json.JSONObject(NetworkCoreConfig.buildProbe(ws));
        assertFalse(probe.has("inbounds"));
        org.json.JSONObject stream = probe.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings");
        assertEquals("front.test", stream.getJSONObject("wsSettings").getString("host"));
        assertEquals("/socket", stream.getJSONObject("wsSettings").getString("path"));
        assertEquals("tls", stream.getString("security"));

        NetworkNode http = NetworkNode.parse(BASE + "?type=tcp&headerType=http&host=one.test%2Ctwo.test&path=%2Fa%2C%2Fb", null);
        org.json.JSONObject raw = new org.json.JSONObject(NetworkCoreConfig.build(http, 17654))
                .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings");
        org.json.JSONObject header = raw.getJSONObject("rawSettings").getJSONObject("header");
        assertEquals("http", header.getString("type"));
        assertEquals("/b", header.getJSONObject("request").getJSONArray("path").getString(1));
        assertEquals("two.test", header.getJSONObject("request").getJSONObject("headers").getJSONArray("Host").getString(1));
    }
    @Test public void ipv6RemainsCanonicalAndInvalidPortIsRejected() {
        assertEquals("[2001:db8::1]:443",NetworkNode.parse(BASE.replace("fixture.test","[2001:db8::1]"),"IPv6").endpoint());
        try {NetworkNode.parse(BASE.replace(":443",":0"),"x");fail();}catch(IllegalArgumentException expected){}
    }
}
