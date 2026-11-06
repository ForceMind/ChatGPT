package com.forcemind.chatgpt.profiles;

import java.io.File;
import org.json.JSONObject;

/** CI observes committed task metadata. It cannot authorize a task or supply page results. */
final class EnvironmentHandoffChecks {
    interface Probe { void saved(JSONObject data); }
    private static volatile Probe probe;
    static void install(Probe value) {
        if (!BuildConfig.ENVIRONMENT_CLIENT || !BuildConfig.ENVIRONMENT_TEST || !BuildConfig.ENVIRONMENT_NETWORK_TEST)
            throw new SecurityException("Owned handoff evidence only");
        probe = value;
    }
    static void initialize(File files) {
        if (!BuildConfig.ENVIRONMENT_CLIENT || !BuildConfig.ENVIRONMENT_TEST || !BuildConfig.ENVIRONMENT_NETWORK_TEST || probe != null) return;
        try {
            java.lang.reflect.Method method = Class.forName("com.forcemind.chatgpt.profiles.CIBuild").getDeclaredMethod("installHandoffProbe", File.class);
            method.setAccessible(true); method.invoke(null, files);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot initialize committed handoff evidence", failure);
        }
    }
    static void saved(JSONObject data) { Probe current = probe; if (current != null) current.saved(data); }
    private EnvironmentHandoffChecks() {}
}
