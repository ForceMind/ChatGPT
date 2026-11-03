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
        for(String suffix:new String[]{"?type=ws&security=tls", "?type=grpc", "?type=xhttp", "?type=tcp&type=raw", "?security=tls&allowInsecure=1", "?security=none&sni=x", "?security=tls&unknown=1"}) {
            try { NetworkNode.parse(BASE+suffix,"x");fail("Unsupported configuration accepted"); }
            catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains(BASE));assertFalse(expected.getMessage().contains("00000000"));}
        }
    }
    @Test public void ipv6RemainsCanonicalAndInvalidPortIsRejected() {
        assertEquals("[2001:db8::1]:443",NetworkNode.parse(BASE.replace("fixture.test","[2001:db8::1]"),"IPv6").endpoint());
        try {NetworkNode.parse(BASE.replace(":443",":0"),"x");fail();}catch(IllegalArgumentException expected){}
    }
}
