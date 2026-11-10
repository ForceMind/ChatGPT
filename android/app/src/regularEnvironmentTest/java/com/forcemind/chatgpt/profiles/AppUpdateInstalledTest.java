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
        assertEquals(3000038L, RegularEnvironmentUpgradeTest.context().getPackageManager()
                .getPackageInfo(RegularEnvironmentUpgradeTest.PACKAGE, 0).getLongVersionCode());
        assertEquals(RegularEnvironmentUpgradeTest.args().getString("signer"), RegularEnvironmentUpgradeTest.signer());
        JSONObject golden = RegularEnvironmentUpgradeTest.expected();
        assertEquals(golden.getInt("uid"), android.os.Process.myUid());
        RegularEnvironmentUpgradeTest.registryBarrier(golden, false);
        RegularEnvironmentUpgradeTest.browser(golden, false);
    }
}
