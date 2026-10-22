package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HandoffStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final EncryptedPayloads cipher=new EncryptedPayloads(()->new SecretKeySpec(new byte[32],"AES"));
    private JSONObject request()throws Exception{String account=WorkspaceState.id(),storage=WorkspaceState.id();return new JSONObject().put("requestId",WorkspaceState.id()).put("sourceAccountId",account).put("targetAccountId",account).put("sourceStorageId",storage).put("targetStorageId",storage).put("assetIds",new JSONArray()).put("crossAccountConfirmed",false).put("skillId","").put("instructions","Sensitive fixture request").put("language","简体中文");}
    private HandoffStore store()throws Exception{return new HandoffStore(temporary.getRoot(),cipher);}
    private JSONObject create(HandoffStore store,JSONObject request)throws Exception{return store.create(request,new JSONArray(),"source","target","自定义任务","Sensitive fixture prompt never in metadata");}
    private void draft(HandoffStore store,String id)throws Exception{store.transition(id,"ready","ready");store.claim(id,store.payload(id).getString("launchToken"));store.bindTab(id,WorkspaceState.id());store.transition(id,"drafting","drafting");}
    @Test public void encryptedPayloadBindsJobAndDoesNotAppearInJournal()throws Exception{
        HandoffStore store=store();JSONObject request=request(),job=create(store,request);String id=job.getString("id");
        assertEquals("Sensitive fixture prompt never in metadata",store.payload(id).getString("prompt"));assertFalse(new String(Files.readAllBytes(new File(temporary.getRoot(),"handoff/jobs.json").toPath()),StandardCharsets.UTF_8).contains("Sensitive fixture"));
        byte[] encrypted=Files.readAllBytes(new File(temporary.getRoot(),"handoff/payloads/"+id+".bin").toPath());assertFalse(new String(encrypted,StandardCharsets.UTF_8).contains("Sensitive fixture"));assertThrows(Exception.class,()->cipher.open(WorkspaceState.id(),encrypted));encrypted[encrypted.length-1]^=1;assertThrows(Exception.class,()->cipher.open(id,encrypted));
        assertEquals(id,create(store,request).getString("id"));request.put("instructions","different");assertThrows(Exception.class,()->create(store,request));
    }
    @Test public void authorizationAndSendFencePreventEveryReplay()throws Exception{
        HandoffStore store=store();String id=create(store,request()).getString("id");store.transition(id,"ready","ready");assertThrows(Exception.class,()->store.claim(id,WorkspaceState.id()));store.claim(id,store.payload(id).getString("launchToken"));assertThrows(Exception.class,()->store.claim(id,store.payload(id).getString("launchToken")));store.bindTab(id,WorkspaceState.id());store.transition(id,"drafting","drafting");assertThrows(Exception.class,()->store.transition(id,"sending","bad"));store.beginSend(id);assertThrows(Exception.class,()->store.beginSend(id));store.failure(id,"cancel",true);assertEquals("uncertain",store.get(id).getString("status"));assertThrows(Exception.class,()->store.claim(id,store.payload(id).getString("launchToken")));
    }
    @Test public void oldBackupCannotRollBackAnIrreversibleSendAttempt()throws Exception{
        HandoffStore store=store();String id=create(store,request()).getString("id");store.transition(id,"ready","ready");byte[] ready=Files.readAllBytes(new File(temporary.getRoot(),"handoff/jobs.json").toPath());store.claim(id,store.payload(id).getString("launchToken"));String tab=WorkspaceState.id();store.bindTab(id,tab);store.transition(id,"drafting","drafting");store.beginSend(id);
        Files.write(new File(temporary.getRoot(),"handoff/jobs.backup.json").toPath(),ready);Files.write(new File(temporary.getRoot(),"handoff/jobs.json").toPath(),"{broken".getBytes(StandardCharsets.UTF_8));HandoffStore recovered=store();recovered.recover();assertEquals("uncertain",recovered.get(id).getString("status"));assertEquals(tab,recovered.get(id).getString("tabId"));assertThrows(Exception.class,()->recovered.claim(id,recovered.payload(id).getString("launchToken")));
    }
    @Test public void submittedTaskIsTerminalAndConversationQueryIsNeverStored()throws Exception{
        HandoffStore store=store();String id=create(store,request()).getString("id");draft(store,id);store.beginSend(id);store.submitted(id,"https://chatgpt.com/c/local-fixture?secret=hidden#private");assertEquals("https://chatgpt.com/c/local-fixture",store.get(id).getString("conversationURL"));store.failure(id,"late error",false);assertEquals("submitted",store.get(id).getString("status"));assertThrows(Exception.class,()->store.transition(id,"ready","retry"));
    }
    @Test public void missingIndexesOrUnavailableEncryptionNeverBecomePlaintextFallback()throws Exception{
        HandoffStore store=store();String id=create(store,request()).getString("id");Files.delete(new File(temporary.getRoot(),"handoff/jobs.json").toPath());Files.delete(new File(temporary.getRoot(),"handoff/jobs.backup.json").toPath());assertThrows(Exception.class,this::store);assertTrue(new File(temporary.getRoot(),"handoff/payloads/"+id+".bin").isFile());
        File other=temporary.newFolder("unavailable");HandoffStore locked=new HandoffStore(other,new EncryptedPayloads(()->{throw new IOException("fixture key unavailable");}));assertThrows(Exception.class,()->create(locked,request()));assertEquals(0,new File(other,"handoff/payloads").list().length);
    }
    @Test public void metadataAccountCannotOverrideSealedAuthorization()throws Exception{
        HandoffStore store=store();JSONObject job=create(store,request());File index=new File(temporary.getRoot(),"handoff/jobs.json");JSONObject data=new JSONObject(new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8));data.getJSONArray("jobs").getJSONObject(0).put("targetAccountId",WorkspaceState.id());Files.write(index.toPath(),data.toString().getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->store.payload(job.getString("id")));
    }
}
