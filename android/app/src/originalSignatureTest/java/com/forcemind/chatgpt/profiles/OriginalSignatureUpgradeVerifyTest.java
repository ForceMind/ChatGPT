package com.forcemind.chatgpt.profiles;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Only the original certificate, owned emulator, and exact installed APK may enter. */
@RunWith(AndroidJUnit4.class)
public final class OriginalSignatureUpgradeVerifyTest {
    @Test public void actualReleaseRetainsTheOriginalData()throws Exception{
        OriginalSignatureUpgradeData.identity(OriginalSignatureUpgradeData.NEW_CODE);
        Class<?> flags=OriginalSignatureUpgradeData.context().getClassLoader().loadClass("com.forcemind.chatgpt.profiles.BuildConfig");
        assertTrue(flags.getField("ENVIRONMENT_CLIENT").getBoolean(null));
        for(String name:new String[]{"ENVIRONMENT_TEST","ENVIRONMENT_NETWORK_TEST","NETWORK_TEST"})assertFalse(name,flags.getField(name).getBoolean(null));
        String phase=OriginalSignatureUpgradeData.args().getString("phase");
        assertTrue("verify-new".equals(phase)||"verify-new-cold".equals(phase));
        JSONObject expected=OriginalSignatureUpgradeData.expected();
        WorkspaceState state=new StateStore(OriginalSignatureUpgradeData.context().getFilesDir()).load();
        WorkspaceState.Account account=state.find(expected.getString("accountId"));
        assertEquals("webview",account.engine);assertFalse(account.isEnvironment());
        assertNull(account.language);assertNull(account.timeZone);assertEquals(-1,account.environmentSlot);
        assertTrue(state.environmentGroups.isEmpty());assertTrue(state.pendingEnvironmentDeletes.isEmpty());
        OriginalSignatureUpgradeData.execute(phase,OriginalSignatureUpgradeData.NEW_CODE,false,false);
    }
}
