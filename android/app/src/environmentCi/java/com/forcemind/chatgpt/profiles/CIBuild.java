package com.forcemind.chatgpt.profiles;

final class CIBuild {
    static boolean environmentUrl(String url) { return EnvironmentPolicy.testUrl(url); }
    static String home() { return BuildConfig.ENVIRONMENT_HOME_URL; }
    private CIBuild() {}
}
