package com.forcemind.chatgpt.profiles;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

/** Reads the existing WebView profile in the actual R8 install target. */
@RunWith(AndroidJUnit4.class)
public final class AppUpdateInstalledTest {
    @Test public void readLoginAfterInstall() throws Exception {
        assertEquals(3000038L, AppUpdateBrowserData.context().getPackageManager()
                .getPackageInfo(AppUpdateBrowserData.PACKAGE, 0).getLongVersionCode());
        assertEquals(AppUpdateBrowserData.args().getString("signer"), AppUpdateBrowserData.signer());
        JSONObject golden = AppUpdateBrowserData.expected();
        assertEquals(golden.getInt("uid"), android.os.Process.myUid());
        OptimizationUpgradeFiles.verify(golden, true);
        AppUpdateBrowserData.verifyExistingRegistry(golden);
        AppUpdateBrowserData.browser(golden, false);
        OptimizationUpgradeFiles.verify(golden, true);
    }
}
