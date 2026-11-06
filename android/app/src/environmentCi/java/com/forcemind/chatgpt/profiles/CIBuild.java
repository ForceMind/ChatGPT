package com.forcemind.chatgpt.profiles;

final class CIBuild {
    static boolean environmentUrl(String url) { return EnvironmentPolicy.testUrl(url); }
    static String home() { return BuildConfig.ENVIRONMENT_HOME_URL; }
    static void installLocalExportProbe(java.io.File files) {
        if(BuildConfig.ENVIRONMENT_TEST&&BuildConfig.ENVIRONMENT_NETWORK_TEST)EnvironmentLocalExportProbe.install(files);
    }
    private CIBuild() {}
}
