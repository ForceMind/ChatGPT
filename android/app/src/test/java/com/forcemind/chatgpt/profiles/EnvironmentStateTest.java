package com.forcemind.chatgpt.profiles;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentStateTest {
    private WorkspaceState.Account add(WorkspaceState state,String language,String zone){return state.addEnvironmentAccount("独立账号","",WorkspaceState.COLORS[1],language,zone);}
    @Test public void legacyRoundTripAddsNoEnvironmentFields()throws Exception{
        WorkspaceState original=WorkspaceState.fresh();String storage=original.current().storageId;
        JSONObject value=original.json();WorkspaceState reloaded=WorkspaceState.parse(value.toString());
        assertEquals("webview",reloaded.current().engine);assertEquals(storage,reloaded.current().storageId);
        JSONObject account=reloaded.json().getJSONArray("accounts").getJSONObject(0);
        for(String key:new String[]{"engine","language","timeZone","environmentSlot"})assertFalse(key,account.has(key));
        assertFalse(reloaded.json().has("environmentGroups"));assertFalse(reloaded.json().has("pendingEnvironmentDeletes"));
        assertEquals(value.toString(),reloaded.json().toString());
    }
    @Test public void environmentRoundTripKeepsExplicitSettingsAndStorage()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account account=add(state,"ZH-cn","Asia/Shanghai");
        WorkspaceState restored=WorkspaceState.parse(state.json().toString());WorkspaceState.Account copy=restored.find(account.id);
        assertTrue(copy.isEnvironment());assertEquals("zh-CN",copy.language);assertEquals("Asia/Shanghai",copy.timeZone);assertEquals(account.storageId,copy.storageId);assertEquals(account.environmentSlot,copy.environmentSlot);
        assertEquals("gecko",restored.json().getJSONArray("accounts").getJSONObject(1).getString("engine"));
    }
    @Test public void identicalEnvironmentSharesSlotButNeverAccountStorage(){
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account first=add(state,"en-US","America/New_York"),second=add(state,"en-us","America/New_York"),third=add(state,"en-US","Asia/Shanghai");
        assertEquals(first.environmentSlot,second.environmentSlot);assertNotEquals(first.storageId,second.storageId);assertNotEquals(first.environmentSlot,third.environmentSlot);assertEquals(2,state.environmentGroups.size());state.validate();
    }
    @Test public void clearAndRemoveQueueOnlyTheirEngineAndKeepGroups()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account account=add(state,"ja-JP","Asia/Tokyo");String old=account.storageId;int slot=account.environmentSlot;
        state.clearAccount(account);assertNotEquals(old,account.storageId);assertTrue(state.pendingDeletes.isEmpty());assertEquals(old,state.pendingEnvironmentDeletes.get(0).storageId);assertEquals(slot,state.pendingEnvironmentDeletes.get(0).environmentSlot);
        String rotated=account.storageId;state.removeAccount(account);assertEquals(2,state.pendingEnvironmentDeletes.size());assertEquals(rotated,state.pendingEnvironmentDeletes.get(1).storageId);assertEquals(1,state.environmentGroups.size());
        WorkspaceState.Account replacement=add(state,"ja-JP","Asia/Tokyo");assertEquals(slot,replacement.environmentSlot);
        WorkspaceState restored=WorkspaceState.parse(state.json().toString());assertEquals(2,restored.pendingEnvironmentDeletes.size());assertEquals(1,restored.environmentGroups.size());
        state.clearAccount(state.accounts.get(0));assertEquals(1,state.pendingDeletes.size());state.validate();
    }
    @Test public void malformedLanguagesAndNonIanaTimeZonesAreRejected(){
        for(String bad:new String[]{"", "zh_CN", " en-US", "en-", "x-private", "en-u", "123", "und"})assertThrows(bad,IllegalArgumentException.class,()->EnvironmentConfig.canonicalLanguage(bad));
        for(String bad:new String[]{"", "PST", "GMT+08:00", "+08:00", "Mars/Olympus", "Asia/Shanghai "})assertThrows(bad,IllegalArgumentException.class,()->EnvironmentConfig.canonicalTimeZone(bad));
        assertEquals("en-US",EnvironmentConfig.canonicalLanguage("EN-us"));assertEquals("UTC",EnvironmentConfig.canonicalTimeZone("UTC"));
    }
    @Test public void invalidSlotGroupEngineAndPendingDeletionAreRejected()throws Exception{
        WorkspaceState state=WorkspaceState.fresh();WorkspaceState.Account account=add(state,"en-US","UTC");JSONObject serialized=state.json();
        serialized.getJSONArray("accounts").getJSONObject(1).put("environmentSlot",30);assertThrows(Exception.class,()->WorkspaceState.parse(serialized.toString()));
        JSONObject fraction=state.json();fraction.getJSONArray("accounts").getJSONObject(1).put("environmentSlot",0.5);assertThrows(Exception.class,()->WorkspaceState.parse(fraction.toString()));
        JSONObject unknown=state.json();unknown.getJSONArray("accounts").getJSONObject(1).put("engine","unknown");assertThrows(Exception.class,()->WorkspaceState.parse(unknown.toString()));
        account.environmentSlot=-1;assertThrows(IllegalArgumentException.class,state::validate);account.environmentSlot=0;
        state.environmentGroups.add(new WorkspaceState.EnvironmentGroup(0,"ja-JP","Asia/Tokyo"));assertThrows(IllegalArgumentException.class,state::validate);state.environmentGroups.remove(1);
        state.pendingEnvironmentDeletes.add(new WorkspaceState.EnvironmentDelete(account.storageId,0));assertThrows(IllegalArgumentException.class,state::validate);state.pendingEnvironmentDeletes.clear();
        String dead=WorkspaceState.id();state.pendingDeletes.add(dead);state.pendingEnvironmentDeletes.add(new WorkspaceState.EnvironmentDelete(dead,0));assertThrows(IllegalArgumentException.class,state::validate);
    }
    @Test public void environmentGroupLimitPersistsAfterAccountsAreRemoved(){
        WorkspaceState state=WorkspaceState.fresh();int count=0;
        for(String zone:java.time.ZoneId.getAvailableZoneIds()){
            WorkspaceState.Account account=add(state,"en-US",zone);state.removeAccount(account);if(++count==30)break;
        }
        assertEquals(30,state.environmentGroups.size());
        String unused=java.time.ZoneId.getAvailableZoneIds().stream().filter(zone->state.environmentGroups.stream().noneMatch(g->g.timeZone.equals(zone))).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,()->add(state,"en-US",unused));state.validate();
    }
    @Test public void existingAccountEnvironmentCannotBeReassigned()throws Exception{
        WorkspaceState before=WorkspaceState.fresh();WorkspaceState.Account account=add(before,"en-US","UTC");WorkspaceState after=before.copy();
        after.find(account.id).name="只修改备注";StateStore.ensureStableEnvironments(before,after);
        after.find(account.id).language="ja-JP";assertThrows(java.io.IOException.class,()->StateStore.ensureStableEnvironments(before,after));
        WorkspaceState removedGroup=before.copy();removedGroup.environmentGroups.clear();assertThrows(java.io.IOException.class,()->StateStore.ensureStableEnvironments(before,removedGroup));
        WorkspaceState convertedLegacy=before.copy();convertedLegacy.accounts.get(0).engine="gecko";assertThrows(java.io.IOException.class,()->StateStore.ensureStableEnvironments(before,convertedLegacy));
    }

}
