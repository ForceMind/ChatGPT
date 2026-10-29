package com.forcemind.chatgpt.networkpreview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VlessNodeTest {
    private static final String BASE = "vless://00000000-0000-4000-8000-000000000001@fixture.test:443";
    @Test public void parsesTlsWebSocketAndPreservesEscapedPath() {
        VlessNode node = VlessNode.parse(BASE + "?encryption=none&type=ws&security=tls&sni=tls.fixture.test&host=cdn.fixture.test&path=%2Fchat%3Fed%3D2048&alpn=h2%2Chttp%2F1.1#Test%20Node", null);
        assertEquals("Test Node", node.name); assertEquals("ws", node.transport);
        assertEquals("/chat?ed=2048", node.options.get("path")); assertEquals("tls", node.security);
    }
    @Test public void parsesIpv6AndTcpAlias() {
        VlessNode node = VlessNode.parse("vless://00000000-0000-4000-8000-000000000001@[2001:db8::1]:1234?type=tcp", "IPv6");
        assertEquals("2001:db8::1", node.host); assertEquals("[2001:db8::1]:1234", node.endpoint()); assertEquals("raw", node.transport);
    }
    @Test public void acceptsRawRealityWithCanonicalKey() {
        VlessNode node = VlessNode.parse(BASE + "?type=raw&security=reality&sni=fixture.test&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=aabb&flow=xtls-rprx-vision", "R");
        assertEquals("reality", node.security); assertEquals("R", node.name);
    }
    @Test public void rejectsUnknownAmbiguousAndUnsafeLinks() {
        String[] invalid = {
            BASE + "?type=grpc", BASE + "?type=ws&security=reality", BASE + "?type=tcp&type=raw",
            BASE + "?security=tls&allowInsecure=1", BASE + "?security=tls&unknown=x",
            BASE + "?encryption=aes", BASE + "?type=ws&path=relative", BASE + "?type=ws&host=a%0Ab",
            BASE + "?security=tls&pbk=AAAA", BASE + "?security=none&sni=x",
            BASE + "?type=ws&security=tls&flow=xtls-rprx-vision", BASE + "?security=reality&sni=x&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB",
            BASE + "?security=reality&sni=x&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abc",
            BASE.replace(":443", ":0"), BASE.replace(":443", ":65536"), BASE.replace("00000000-0000-4000-8000-000000000001", "invalid"),
            BASE.replace("vless:", "https:"), BASE + "/ignored", BASE + "?security=tls&sni=hello%20world"
        };
        for (String link : invalid) {
            try { VlessNode.parse(link, "x"); fail("Unexpected accepted input"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }
    @Test public void errorNeverContainsCredentials() {
        try { VlessNode.parse(BASE + "?type=grpc", "x"); fail(); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("00000000")); assertFalse(expected.getMessage().contains(BASE)); }
    }
}
