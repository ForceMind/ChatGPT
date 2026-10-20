package com.forcemind.chatgpt.profiles;

import java.net.URI;
import java.util.Set;

public final class NavigationPolicy {
    public static final String HOME = "https://chatgpt.com/";
    private static final Set<String> CHAT = hosts("chatgpt.com", "chat.openai.com");
    private static final Set<String> AUTH = hosts("auth.openai.com", "auth0.openai.com", "accounts.google.com",
            "appleid.apple.com", "idmsa.apple.com", "login.live.com", "login.microsoftonline.com", "login.microsoft.com", "account.live.com");
    public static boolean https(String value) {
        if (value == null || value.length() > 8192) return false;
        try { URI u = URI.create(value); return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                && u.getRawUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443); }
        catch (IllegalArgumentException e) { return false; }
    }
    public static boolean chat(String value) {
        return https(value) && CHAT.contains(URI.create(value).getHost().toLowerCase(java.util.Locale.ROOT));
    }
    public static boolean internal(String value) {
        if (!https(value)) return false;
        String host = URI.create(value).getHost().toLowerCase(java.util.Locale.ROOT);
        return CHAT.contains(host) || AUTH.contains(host);
    }
    public static String restorable(String value) {
        if (!chat(value)) return HOME;
        URI u = URI.create(value);
        // Do not persist OAuth query parameters, fragments, or third-party login URLs.
        return "https://" + u.getHost() + (u.getRawPath().isEmpty() ? "/" : u.getRawPath());
    }
    private static Set<String> hosts(String... names) { return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(names))); }
    private NavigationPolicy() {}
}
