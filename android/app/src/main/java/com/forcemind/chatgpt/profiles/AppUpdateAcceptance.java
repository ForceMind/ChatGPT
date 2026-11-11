package com.forcemind.chatgpt.profiles;

/** Fixed private host. The per-device access code is entered after installation. */
final class AppUpdateAcceptance {
    static final String ORIGIN = "https://studio-android-acceptance.pages.dev";
    static final String FEED = ORIGIN + "/android.json";
    static final String APK = ORIGIN + "/update.apk";
    static final String HEADER = "X-Studio-Acceptance";

    static boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9]{64}");
    }

    private AppUpdateAcceptance() {}
}
