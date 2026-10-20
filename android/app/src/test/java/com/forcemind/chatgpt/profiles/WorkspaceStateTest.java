package com.forcemind.chatgpt.profiles;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceStateTest {
    @Test public void accountsPersistWithIndependentStorage() throws Exception {
        WorkspaceState s = WorkspaceState.fresh(); String a = s.current().storageId;
        s.addAccount("工作", "work@example.test", WorkspaceState.COLORS[1]);
        WorkspaceState reloaded = WorkspaceState.parse(s.json().toString());
        assertEquals(2, reloaded.accounts.size()); assertNotEquals(a, reloaded.current().storageId); assertEquals("工作", reloaded.current().name);
    }
    @Test public void clearRotatesOnlySelectedProfile() {
        WorkspaceState s = WorkspaceState.fresh(); WorkspaceState.Account first = s.current(); String old = first.storageId;
        WorkspaceState.Account second = s.addAccount("工作", "", WorkspaceState.COLORS[1]); String keep = second.storageId;
        s.clearAccount(first); s.validate();
        assertNotEquals(old, first.storageId); assertEquals(keep, second.storageId); assertEquals(java.util.List.of(old), s.pendingDeletes);
    }
    @Test public void lastTabAndRestorePreserveOwnership() {
        WorkspaceState s = WorkspaceState.fresh(); WorkspaceState.Account a = s.current(); String old = a.activeTab;
        s.closeTab(a, old); s.validate(); assertEquals(1, a.tabs.size()); assertNotEquals(old, a.activeTab); assertEquals(old, a.closedTabs.get(0).id);
        WorkspaceState snapshot = s.copy(); snapshot.current().name = "新名称"; assertNotEquals(snapshot.current().name, s.current().name);
    }
    @Test public void corruptAndSharedProfilesAreRejected() throws Exception {
        WorkspaceState s = WorkspaceState.fresh(); WorkspaceState.Account a = s.current();
        s.addAccount("工作", "", WorkspaceState.COLORS[1]).storageId = a.storageId;
        assertThrows(IllegalArgumentException.class, s::validate);
        assertThrows(Exception.class, () -> WorkspaceState.parse("{}"));
        WorkspaceState valid = WorkspaceState.fresh(); valid.pendingDeletes.add(valid.current().storageId);
        assertThrows(IllegalArgumentException.class, valid::validate);
    }
    @Test public void navigationRequiresExactHttpsOriginsAndDropsLoginParameters() {
        assertTrue(NavigationPolicy.internal("https://accounts.google.com/o/oauth2"));
        for (String bad : new String[]{"https://chatgpt.com.evil.test/", "file:///etc/passwd", "intent://foo", "http://chatgpt.com/", "https://user@chatgpt.com/", "https://chatgpt.com:8443/"}) assertFalse(bad, NavigationPolicy.internal(bad));
        assertEquals("https://chatgpt.com/c/abc", NavigationPolicy.restorable("https://chatgpt.com/c/abc?token=secret#fragment"));
        assertEquals(NavigationPolicy.HOME, NavigationPolicy.restorable("https://auth.openai.com/token?code=secret"));
    }
}
