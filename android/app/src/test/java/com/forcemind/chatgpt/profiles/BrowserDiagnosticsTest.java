package com.forcemind.chatgpt.profiles;

import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class BrowserDiagnosticsTest {
    @Test public void journalIsBoundedAndDoesNotAcceptWebContentOrUrls()throws Exception{
        Path root=Files.createTempDirectory("studio-diagnostics-");root.toFile().deleteOnExit();root.resolve("browser-events.json").toFile().deleteOnExit();root.resolve("browser-events.tmp").toFile().deleteOnExit();BrowserDiagnostics diagnostics=new BrowserDiagnostics(root.toFile());
        for(int i=0;i<125;i++)diagnostics.event("page-load",WorkspaceState.id(),WorkspaceState.id(),"epoch:"+i);
        diagnostics.event("page-error",null,null,"https://example.test/?secret=DO_NOT_STORE");
        diagnostics.event("<script>unsafe</script>",null,null,"ignored");JSONArray events=diagnostics.snapshot();assertEquals(100,events.length());assertFalse(events.toString().contains("DO_NOT_STORE"));assertFalse(events.toString().contains("https://"));
        events.remove(0);assertEquals(100,diagnostics.snapshot().length());assertEquals("网页开始载入",BrowserDiagnostics.label("page-load"));
    }
    @Test public void manualTabTitlesRoundTripWithoutChangingLegacyIds()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();String account=state.activeAccount,tab=state.current().activeTab,storage=state.current().storageId;
        assertFalse(WorkspaceState.parse(state.json().toString()).current().current().customTitle);
        state.current().current().title="项目说明";state.current().current().customTitle=true;WorkspaceState restored=WorkspaceState.parse(state.json().toString());
        assertEquals(account,restored.activeAccount);assertEquals(tab,restored.current().activeTab);assertEquals(storage,restored.current().storageId);assertTrue(restored.current().current().customTitle);assertEquals("项目说明",restored.current().current().title);
        JSONObject broken=state.json();broken.getJSONArray("accounts").getJSONObject(0).getJSONArray("tabs").getJSONObject(0).put("customTitle","true");assertThrows(IllegalArgumentException.class,()->WorkspaceState.parse(broken.toString()));
    }
}
