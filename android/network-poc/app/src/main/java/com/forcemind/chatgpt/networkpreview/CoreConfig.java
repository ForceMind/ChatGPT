package com.forcemind.chatgpt.networkpreview;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class CoreConfig {
    private CoreConfig() {}
    public static String build(VlessNode n, int localPort) {
        if (localPort < 1 || localPort > 65535) throw new IllegalArgumentException("本地端口无效");
        try {
            JSONObject user = new JSONObject().put("id", n.id).put("encryption", "none");
            if (!n.options.getOrDefault("flow", "").isEmpty()) user.put("flow", n.options.get("flow"));
            JSONObject server = new JSONObject().put("address", n.host).put("port", n.port).put("users", new JSONArray().put(user));
            JSONObject stream = new JSONObject().put("network", n.transport).put("security", n.security);
            if ("ws".equals(n.transport)) {
                JSONObject ws = new JSONObject().put("path", n.options.getOrDefault("path", "/"));
                if (n.options.containsKey("host")) ws.put("headers", new JSONObject().put("Host", n.options.get("host")));
                stream.put("wsSettings", ws);
            }
            if ("tls".equals(n.security)) {
                JSONObject tls = new JSONObject().put("allowInsecure", false).put("serverName", n.options.getOrDefault("sni", n.host));
                if (n.options.containsKey("fp")) tls.put("fingerprint", n.options.get("fp"));
                if (n.options.containsKey("alpn")) tls.put("alpn", new JSONArray(n.options.get("alpn").split(",")));
                FixtureTrust.apply(tls, n);
                stream.put("tlsSettings", tls);
            }
            if ("reality".equals(n.security)) stream.put("realitySettings", new JSONObject()
                    .put("serverName", n.options.get("sni")).put("fingerprint", n.options.getOrDefault("fp", "chrome"))
                    .put("password", n.options.get("pbk")).put("shortId", n.options.getOrDefault("sid", ""))
                    .put("spiderX", n.options.getOrDefault("spx", "/")));
            JSONObject inbound = new JSONObject().put("tag", "app-http").put("listen", "127.0.0.1").put("port", localPort)
                    .put("protocol", "http").put("settings", new JSONObject().put("allowTransparent", false));
            JSONObject outbound = new JSONObject().put("tag", "selected").put("protocol", "vless")
                    .put("settings", new JSONObject().put("vnext", new JSONArray().put(server))).put("streamSettings", stream);
            return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                    .put("inbounds", new JSONArray().put(inbound)).put("outbounds", new JSONArray().put(outbound)).toString();
        } catch (JSONException e) { throw new IllegalArgumentException("无法生成线路配置", e); }
    }
}
