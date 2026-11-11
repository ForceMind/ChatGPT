package com.forcemind.chatgpt.profiles;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdateReleaseTest {
    private static JSONObject feed() throws Exception {
        return new JSONObject().put("schema", 1).put("packageName", AppUpdateRelease.PACKAGE)
                .put("versionName", "2026.11.09-1236").put("versionCode", 3000036).put("abi", "arm64-v8a")
                .put("size", 228463145).put("sha256", "0".repeat(64)).put("certificateSHA256", "1".repeat(64))
                .put("url", "https://github.com/ForceMind/ChatGPT/releases/download/v2026.11.9-1236/ChatGPT-Studio-2026.11.09-1236-android-arm64.apk");
    }

    @Test public void bindsReleaseVersionFilenameAndRedirectHosts() throws Exception {
        AppUpdateRelease release = AppUpdateRelease.parse(feed().toString(), false);
        assertEquals(3000036, release.code);
        assertEquals("https://gh-proxy.org/" + release.url, release.downloadUrl(true, false));
        assertTrue(release.allowsDownload("https://release-assets.githubusercontent.com/file?token=signed", false, false));
        assertFalse(release.allowsDownload("https://release-assets.githubusercontent.com.evil.invalid/file", false, false));
        assertFalse(release.allowsDownload("https://name@release-assets.githubusercontent.com/file", false, false));
        assertFalse(release.allowsDownload("https://gh-proxy.org/" + release.url, false, false));
    }

    @Test public void privateSourceIsCandidateOnlyAndCannotRedirectOrProxy() throws Exception {
        String metadata = feed().put("url", AppUpdateAcceptance.APK).toString();
        if (!BuildConfig.APP_UPDATE_ACCEPTANCE) {
            assertThrows(IllegalArgumentException.class, () -> AppUpdateRelease.parse(metadata, false));
            return;
        }
        AppUpdateRelease release = AppUpdateRelease.parse(metadata, false);
        assertTrue(release.privateSource);
        assertEquals(AppUpdateAcceptance.APK, release.downloadUrl(true, false));
        assertTrue(release.allowsDownload(AppUpdateAcceptance.APK, false, false));
        assertFalse(release.allowsDownload(AppUpdateAcceptance.APK + "?other=1", false, false));
        assertFalse(release.allowsDownload("https://release-assets.githubusercontent.com/file", false, false));
        assertFalse(release.allowsDownload("https://gh-proxy.org/" + release.url, true, false));
        assertTrue(AppUpdateAcceptance.validKey("a".repeat(64)));
        assertFalse(AppUpdateAcceptance.validKey("a".repeat(63)));
        assertFalse(AppUpdateAcceptance.validKey("a".repeat(64) + "\n"));
    }

    @Test public void rejectsTamperedManifestContract() throws Exception {
        Object[][] changes = {{"versionCode", "3000036"}, {"versionCode", 3000036.5}, {"versionName", "2026.02.31-1236"},
                {"packageName", "other.app"}, {"abi", "x86_64"}, {"size", AppUpdateRelease.MAX_BYTES + 1},
                {"sha256", "abc"}, {"url", "https://evil.invalid/update.apk"}};
        for (Object[] change : changes) {
            try {
                AppUpdateRelease.parse(feed().put((String) change[0], change[1]).toString(), false);
                fail("Accepted changed field " + change[0]);
            } catch (IllegalArgumentException | org.json.JSONException | java.time.DateTimeException expected) {
                // Each modified contract must fail before any transfer.
            }
        }
    }
}
