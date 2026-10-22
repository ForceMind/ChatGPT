package com.forcemind.chatgpt.profiles;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;

public class PageRecoveryStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private WorkspaceState state;private WorkspaceState.Account account;
    private EncryptedPayloads cipher(){return new EncryptedPayloads(()->new SecretKeySpec(new byte[32],"AES"),"page-recovery-v1/");}
    private void workspace()throws Exception{Files.write(new File(temporary.getRoot(),"workspace.json").toPath(),state.json().toString().getBytes(StandardCharsets.UTF_8));}
    @Before public void setup()throws Exception{state=WorkspaceState.fresh();account=state.current();workspace();}
    private PageRecoveryStore store()throws Exception{return new PageRecoveryStore(temporary.getRoot(),cipher());}
    private JSONObject record(String draft)throws Exception{return new JSONObject().put("schema",1).put("accountId",account.id).put("storageId",account.storageId).put("tabId",account.activeTab).put("url","https://chatgpt.com/c/fixture").put("draft",draft).put("scroll",450).put("ratio",0.25).put("capturedAt",1234).put("anchor",new JSONObject().put("kind","message").put("value","message-fixture").put("offset",12));}
    private File file(){return new File(temporary.getRoot(),"page-recovery/"+account.id+"/"+account.storageId+"/"+account.activeTab+".bin");}
    @Test public void encryptedRecordsRoundTripAndNeverCrossUrlAccountOrCipherDomain()throws Exception{
        PageRecoveryStore store=store();JSONObject r=record("不能以明文存储的草稿").put("alternativeDraft","旧的本地版本").put("pageHTML","must not be retained");store.save(r,store.generation(account.id,account.storageId));
        byte[] bytes=Files.readAllBytes(file().toPath());assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("不能以明文"));JSONObject read=store.load(account.id,account.storageId,account.activeTab,r.getString("url"));assertEquals(r.getString("draft"),read.getString("draft"));assertEquals("旧的本地版本",read.getString("alternativeDraft"));assertFalse(read.has("pageHTML"));assertNull(store.load(account.id,account.storageId,account.activeTab,"https://chatgpt.com/c/other"));
        assertThrows(Exception.class,()->new EncryptedPayloads(()->new SecretKeySpec(new byte[32],"AES")).open(account.activeTab,bytes));
        WorkspaceState.Account other=state.addAccount("other","",WorkspaceState.COLORS[1]);workspace();File copied=new File(temporary.getRoot(),"page-recovery/"+other.id+"/"+other.storageId+"/"+other.activeTab+".bin");assertTrue(copied.getParentFile().mkdirs());Files.copy(file().toPath(),copied.toPath());assertThrows(Exception.class,()->store.load(other.id,other.storageId,other.activeTab,r.getString("url")));
    }
    @Test public void schemaTextAndPositionsAreStrictAndTemporaryOrAuthPagesAreExcluded()throws Exception{
        assertNull(PageRecoveryRecord.url("https://chatgpt.com/?temporary-chat=%74rue"));assertNull(PageRecoveryRecord.url("https://chatgpt.com/api/session"));assertNull(PageRecoveryRecord.url("https://auth.openai.com/"));assertNull(PageRecoveryRecord.url("https://chatgpt.com.evil.test/"));assertNull(PageRecoveryRecord.url("https://chatgpt.com:444/"));assertEquals("https://chatgpt.com/c/fixture",PageRecoveryRecord.url("https://chatgpt.com/c/fixture?token=secret#value"));
        assertThrows(Exception.class,()->PageRecoveryRecord.normalize(record("x").put("schema",2)));assertThrows(Exception.class,()->PageRecoveryRecord.normalize(record("x").put("ratio",1.1)));assertThrows(Exception.class,()->PageRecoveryRecord.normalize(record("x").put("scroll",-1)));assertThrows(Exception.class,()->PageRecoveryRecord.normalize(record("x").put("capturedAt","123")));assertThrows(Exception.class,()->PageRecoveryRecord.normalize(record("x".repeat(100001))));
    }
    @Test public void bothLargeChineseDraftsFitAndUnavailableKeysNeverWritePlaintext()throws Exception{
        PageRecoveryStore store=store();JSONObject r=record("中".repeat(100000)).put("alternativeDraft","文".repeat(100000));store.save(r,0);assertEquals(100000,store.load(account.id,account.storageId,account.activeTab,r.getString("url")).getString("alternativeDraft").length());assertTrue(file().length()<1024*1024);
        PageRecoveryStore locked=new PageRecoveryStore(temporary.getRoot(),new EncryptedPayloads(()->{throw new IOException("fixture unavailable");},"page-recovery-v1/"));assertThrows(Exception.class,()->locked.save(record("new secret"),0));assertEquals(r.getString("draft"),store.load(account.id,account.storageId,account.activeTab,r.getString("url")).getString("draft"));
    }
    @Test public void backupRecoveryPreservesCorruptOriginalAndBothCorruptDoNotBecomeEmpty()throws Exception{
        PageRecoveryStore store=store();store.save(record("first"),0);store.save(record("second"),0);Files.write(file().toPath(),new byte[]{1,2,3});assertEquals("first",store.load(account.id,account.storageId,account.activeTab,"https://chatgpt.com/c/fixture").getString("draft"));File[] preserved=file().getParentFile().listFiles((d,n)->n.contains(".corrupt-"));assertEquals(1,preserved.length);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(preserved[0].toPath()));
        store.save(record("second"),0);assertEquals("second",store.load(account.id,account.storageId,account.activeTab,"https://chatgpt.com/c/fixture").getString("draft"));
        Files.write(file().toPath(),new byte[]{4});Files.write(new File(file()+".bak").toPath(),new byte[]{5});assertThrows(Exception.class,()->store.load(account.id,account.storageId,account.activeTab,"https://chatgpt.com/c/fixture"));assertArrayEquals(new byte[]{4},Files.readAllBytes(file().toPath()));
    }
    @Test public void clearFencesDelayedWritesAndRevocationSurvivesStaleWorkspace()throws Exception{
        PageRecoveryStore store=store();JSONObject r=record("prior");long generation=store.generation(account.id,account.storageId);store.save(r,generation);File unknown=new File(file().getParentFile(),"keep-unknown.txt");Files.write(unknown.toPath(),new byte[]{8});store.clear(account.id,account.storageId);store.save(r,generation);assertFalse(file().exists());assertTrue(unknown.isFile());
        Files.write(new File(temporary.getRoot(),"revoked-profiles.json").toPath(),new JSONArray().put(account.storageId).toString().getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->store.load(account.id,account.storageId,account.activeTab,r.getString("url")));assertThrows(Exception.class,()->store.save(r,store.generation(account.id,account.storageId)));
    }
    @Test public void symlinksAndForeignStorageAreNeverReadOrOverwritten()throws Exception{
        PageRecoveryStore store=store();JSONObject r=record("safe");store.save(r,0);File outside=temporary.newFile("outside");Files.write(outside.toPath(),new byte[]{7});Files.delete(file().toPath());Files.createSymbolicLink(file().toPath(),outside.toPath());assertThrows(Exception.class,()->store.load(account.id,account.storageId,account.activeTab,r.getString("url")));assertThrows(Exception.class,()->store.save(record("must not write"),0));assertArrayEquals(new byte[]{7},Files.readAllBytes(outside.toPath()));
        assertThrows(Exception.class,()->store.save(r.put("storageId",WorkspaceState.id()),0));
    }
}
