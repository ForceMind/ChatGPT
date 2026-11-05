package com.forcemind.chatgpt.profiles;

import java.util.Locale;
import java.util.Set;
import java.time.ZoneId;

/** Explicit, stable settings for one Android environment process. */
final class EnvironmentConfig {
    static final int MAX_SLOTS = 30;
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    static String canonicalLanguage(String value) {
        if (value == null || !value.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) throw new IllegalArgumentException("请输入有效的 BCP 47 语言，例如 zh-CN 或 en-US");
        try {
            Locale locale = new Locale.Builder().setLanguageTag(value).build();
            String tag = locale.toLanguageTag();
            if (locale.getLanguage().isEmpty() || "und".equals(tag)) throw new IllegalArgumentException("语言无效");
            return tag;
        } catch (java.util.IllformedLocaleException e) { throw new IllegalArgumentException("语言格式无效", e); }
    }
    static String canonicalTimeZone(String value) {
        if (value == null || !ZONES.contains(value)) throw new IllegalArgumentException("请选择有效的 IANA 时区，例如 Asia/Shanghai");
        return value;
    }
    static void validateSlot(int slot) { if (slot < 0 || slot >= MAX_SLOTS) throw new IllegalArgumentException("Invalid environment slot"); }
    private EnvironmentConfig() {}
}
