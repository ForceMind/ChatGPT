package com.forcemind.chatgpt.profiles;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Same fixed signer, install -r only. No production Activity, Gecko or HTTP. */
@RunWith(AndroidJUnit4.class)
public final class EnvironmentUpgradeVerifyTest {
    @Test public void installedUpgradePreservesNativeState()throws Exception{
        int api=EnvironmentUpgradeSeedTest.verifyIdentity(EnvironmentUpgradeSeedTest.NEW_CODE);
        String phase=InstrumentationRegistry.getArguments().getString("phase");assertTrue("verify-new".equals(phase)||"verify-new-cold".equals(phase));
        JSONObject expected=EnvironmentUpgradeSeedTest.expected();String originalExpectedHash=EnvironmentUpgradeSeedTest.hash(EnvironmentUpgradeSeedTest.file(EnvironmentUpgradeSeedTest.EXPECTED));
        EnvironmentUpgradeSeedTest.verifyNative(expected);
        WorkspaceState state=new StateStore(EnvironmentUpgradeSeedTest.context().getFilesDir()).load();WorkspaceState.Account account=state.find(expected.getString("accountId"));
        assertEquals("webview",account.engine);assertFalse(account.isEnvironment());assertNull(account.language);assertNull(account.timeZone);assertEquals(-1,account.environmentSlot);assertTrue(state.environmentGroups.isEmpty());assertTrue(state.pendingEnvironmentDeletes.isEmpty());
        JSONObject original=new JSONObject(new String(java.nio.file.Files.readAllBytes(EnvironmentUpgradeSeedTest.file("workspace.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject legacy=original.getJSONArray("accounts").getJSONObject(0);for(String key:new String[]{"engine","language","timeZone","environmentSlot"})assertFalse("Upgrade added a default environment field: "+key,legacy.has(key));
        EnvironmentUpgradeSeedTest.registryBarrier(expected,false);
        EnvironmentUpgradeSeedTest.browser(expected,false);
        long millis=EnvironmentUpgradeSeedTest.registryBarrier(expected,false);
        EnvironmentUpgradeSeedTest.verifyNative(expected);
        assertEquals(originalExpectedHash,EnvironmentUpgradeSeedTest.hash(EnvironmentUpgradeSeedTest.file(EnvironmentUpgradeSeedTest.EXPECTED)));
        EnvironmentUpgradeSeedTest.noActivity();EnvironmentUpgradeSeedTest.proof(expected,phase,api,EnvironmentUpgradeSeedTest.NEW_CODE,millis,true);
    }
}
