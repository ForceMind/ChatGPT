package com.forcemind.chatgpt.profiles;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** New-only API checks stay out of the class executed inside the original alpha.22 APK. */
@RunWith(AndroidJUnit4.class)
public final class RegularEnvironmentUpgradeVerifyTest {
    @Test public void originalPublicAccountRemainsLegacyWebView()throws Exception{
        String phase=RegularEnvironmentUpgradeTest.args().getString("phase");assertTrue("verify-new".equals(phase)||"verify-new-cold".equals(phase)||"verify-after-environment".equals(phase));
        RegularEnvironmentUpgradeTest.identity(RegularEnvironmentUpgradeTest.NEW_CODE);
        assertTrue("Candidate must be an explicit non-human testOnly APK",(RegularEnvironmentUpgradeTest.context().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_TEST_ONLY)!=0);
        JSONObject expected=RegularEnvironmentUpgradeTest.expected();WorkspaceState state=new StateStore(RegularEnvironmentUpgradeTest.context().getFilesDir()).load();WorkspaceState.Account account=state.find(expected.getString("accountId"));
        assertEquals("webview",account.engine);assertFalse(account.isEnvironment());assertNull(account.language);assertNull(account.timeZone);assertEquals(-1,account.environmentSlot);
        if(!"verify-after-environment".equals(phase)){assertTrue(state.environmentGroups.isEmpty());assertTrue(state.pendingEnvironmentDeletes.isEmpty());}
        JSONObject workspace=new JSONObject(new String(java.nio.file.Files.readAllBytes(RegularEnvironmentUpgradeTest.file("workspace.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));JSONArray accounts=workspace.getJSONArray("accounts");JSONObject legacy=null;for(int i=0;i<accounts.length();i++)if(account.id.equals(accounts.getJSONObject(i).getString("id")))legacy=accounts.getJSONObject(i);assertNotNull(legacy);
        for(String field:new String[]{"engine","language","timeZone","environmentSlot"})assertFalse("Legacy account gained default environment field: "+field,legacy.has(field));
        RegularEnvironmentUpgradeTest.execute(phase,RegularEnvironmentUpgradeTest.NEW_CODE,false,"verify-after-environment".equals(phase));
    }
}
