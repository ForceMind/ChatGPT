package com.forcemind.chatgpt.networkpreview;

import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

/** Deliberately small share-link contract. Unknown inputs fail rather than changing routing silently. */
public final class VlessNode {
    public final String link, name, id, host, transport, security;
    public final int port;
    public final Map<String, String> options;
    private VlessNode(String link, String name, String id, String host, int port,
                      String transport, String security, Map<String, String> options) {
        this.link = link; this.name = name; this.id = id; this.host = host; this.port = port;
        this.transport = transport; this.security = security;
        this.options = Collections.unmodifiableMap(options);
    }
    private static String decode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name()); }
        catch (Exception e) { throw new IllegalArgumentException("地址编码无效"); }
    }
    public static VlessNode parse(String input, String displayName) {
        if (input == null || input.length() > 16384) throw bad("地址为空或过长");
        String link = input.trim();
        try {
            URI uri = new URI(link);
            if (!"vless".equalsIgnoreCase(uri.getScheme())) throw bad("只支持 vless:// 地址");
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') != authority.lastIndexOf('@')) throw bad("服务器地址无效");
            String id = decode(uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo());
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw bad("UUID 格式无效");
            id = UUID.fromString(id).toString();
            String host = uri.getHost();
            if (host == null || host.isEmpty() || host.contains("%")) throw bad("主机名或 IPv6 地址无效");
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            if (!host.contains(":")) host = IDN.toASCII(host);
            int port = uri.getPort();
            if (port < 1 || port > 65535) throw bad("端口必须为 1–65535");
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) throw bad("传输路径须使用 path 参数");
            Map<String, String> p = new LinkedHashMap<>();
            Set<String> allowed = new HashSet<>(Arrays.asList("encryption", "type", "security", "sni", "fp", "pbk", "sid", "spx", "alpn", "flow", "host", "path", "allowInsecure", "headerType"));
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                String key = decode(pair[0]); String value = pair.length == 2 ? decode(pair[1]) : "";
                if (!allowed.contains(key)) throw bad("地址包含未支持的参数");
                if (p.put(key, value) != null) throw bad("地址包含重复参数");
                if (value.chars().anyMatch(c -> c < 32 || c == 127)) throw bad("参数包含控制字符");
            }
            if (!"none".equals(p.getOrDefault("encryption", "none"))) throw bad("仅支持 encryption=none");
            if (!"none".equals(p.getOrDefault("headerType", "none"))) throw bad("首版不支持 TCP 头部伪装");
            String transport = p.getOrDefault("type", "tcp");
            if ("tcp".equals(transport)) transport = "raw";
            if (!"raw".equals(transport) && !"ws".equals(transport)) throw bad("仅支持 raw/tcp 和 ws 传输");
            String security = p.getOrDefault("security", "none");
            if (!Arrays.asList("none", "tls", "reality").contains(security)) throw bad("不支持此安全协议");
            if ("reality".equals(security) && !"raw".equals(transport)) throw bad("REALITY 仅支持 raw/tcp");
            if (p.containsKey("allowInsecure") && !Arrays.asList("0", "false").contains(p.get("allowInsecure"))) throw bad("不允许跳过证书验证");
            if (p.containsKey("sni") && !p.get("sni").matches("[A-Za-z0-9._:-]+")) throw bad("sni 格式无效");
            if (p.containsKey("host") && !p.get("host").matches("[A-Za-z0-9.\\[\\]:_-]+")) throw bad("WebSocket host 格式无效");
            if (p.containsKey("spx") && !p.get("spx").startsWith("/")) throw bad("spx 必须以 / 开始");
            String flow = p.getOrDefault("flow", "");
            if (!flow.isEmpty() && (!"xtls-rprx-vision".equals(flow) || !"raw".equals(transport) || "none".equals(security))) throw bad("不支持此 flow 组合");
            if (!"ws".equals(transport) && (p.containsKey("host") || p.containsKey("path"))) throw bad("host/path 仅适用于 ws");
            if ("ws".equals(transport) && !p.getOrDefault("path", "/").startsWith("/")) throw bad("WebSocket path 必须以 / 开始");
            if (!"reality".equals(security) && (p.containsKey("pbk") || p.containsKey("sid") || p.containsKey("spx"))) throw bad("REALITY 参数与 security 不一致");
            if ("none".equals(security) && (p.containsKey("sni") || p.containsKey("fp") || p.containsKey("alpn"))) throw bad("TLS 参数与 security 不一致");
            if (p.containsKey("fp") && !Arrays.asList("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized").contains(p.get("fp"))) throw bad("不支持此 TLS 指纹");
            if (p.containsKey("alpn") && !p.get("alpn").matches("(?:h2|http/1\\.1)(?:,(?:h2|http/1\\.1))*")) throw bad("不支持此 ALPN");
            if ("reality".equals(security)) {
                if (!p.getOrDefault("pbk", "").matches("[A-Za-z0-9_-]{43}")) throw bad("REALITY pbk 必须为 32 字节 base64url 公钥");
                byte[] publicKey = java.util.Base64.getUrlDecoder().decode(p.get("pbk"));
                if (publicKey.length != 32 || !java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey).equals(p.get("pbk"))) throw bad("REALITY pbk 编码无效");
                if (!p.getOrDefault("sid", "").matches("(?:[0-9a-fA-F]{2}){0,8}")) throw bad("REALITY sid 必须为至多 8 字节十六进制");
                if (p.getOrDefault("sni", "").isEmpty()) throw bad("REALITY 需要 sni");
                if (p.containsKey("alpn")) throw bad("首版 REALITY 不接受 alpn");
            }
            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) name = uri.getRawFragment() == null ? "线路" : decode(uri.getRawFragment()).trim();
            if (name.isEmpty()) name = "线路";
            if (name.length() > 80) throw bad("名称最多 80 字符");
            return new VlessNode(link, name, id, host, port, transport, security, p);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw bad("VLESS 地址格式无效"); }
    }
    public String endpoint() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
