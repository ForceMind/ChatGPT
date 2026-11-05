package com.forcemind.chatgpt.profiles;

/** Only the separate CI build enables its exact owned origin; human builds contain no fixture URL. */
final class EnvironmentPolicy {
    static boolean testUrl(String value) {
        if (!BuildConfig.ENVIRONMENT_TEST || value == null || BuildConfig.ENVIRONMENT_HOME_URL.isEmpty()) return false;
        try {
            java.net.URI expected = java.net.URI.create(BuildConfig.ENVIRONMENT_HOME_URL), candidate = java.net.URI.create(value);
            return "https".equals(candidate.getScheme()) && candidate.getRawUserInfo() == null
                && expected.getHost().equals(candidate.getHost()) && expected.getPort() == candidate.getPort();
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private EnvironmentPolicy() {}
}
