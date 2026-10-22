package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HandoffFilesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private JSONObject request(String account)throws Exception{return new JSONObject().put("requestId",WorkspaceState.id()).put("sourceAccountId",account).put("targetAccountId",account).put("sourceStorageId",WorkspaceState.id()).put("targetStorageId",WorkspaceState.id()).put("assetIds",new JSONArray()).put("crossAccountConfirmed",false).put("instructions","fixture").put("language","zh-CN");}
    @Test public void requestRequiresExplicitCrossAccountConfirmationAndUniqueBoundedFiles()throws Exception{
        JSONObject request=request(WorkspaceState.id());request.put("targetAccountId",WorkspaceState.id()).put("assetIds",new JSONArray().put(WorkspaceState.id()));assertThrows(Exception.class,()->HandoffRequest.normalize(request));request.put("crossAccountConfirmed",true);HandoffRequest.normalize(request);request.getJSONArray("assetIds").put(request.getJSONArray("assetIds").getString(0));assertThrows(Exception.class,()->HandoffRequest.normalize(request));
    }
    @Test public void htmlAliasesAndStagedBytesKeepSourceOwnershipAndDigest()throws Exception{
        AssetLedger ledger=new AssetLedger(temporary.getRoot(),WorkspaceState.id());byte[] bytes="<h1>原件</h1>".getBytes(StandardCharsets.UTF_8);JSONObject asset=ledger.importCopy(new ByteArrayInputStream(bytes),"page.html","text/html","import",null);JSONArray snapshot=HandoffFiles.snapshot(ledger,new JSONArray().put(asset.getString("id")));assertEquals("page.html.txt",snapshot.getJSONObject(0).getString("uploadName"));assertEquals("text/plain",snapshot.getJSONObject(0).getString("uploadMime"));String id=WorkspaceState.id();HandoffFiles.stage(temporary.getRoot(),id,ledger,snapshot,()->true);File file=new File(HandoffFiles.staging(temporary.getRoot(),id,false),snapshot.getJSONObject(0).getString("stageId")+".bin");assertArrayEquals(bytes,Files.readAllBytes(file.toPath()));assertThrows(Exception.class,()->HandoffFiles.stage(temporary.getRoot(),id,ledger,snapshot,()->true));assertArrayEquals(bytes,Files.readAllBytes(file.toPath()));HandoffFiles.cleanup(temporary.getRoot(),id);assertArrayEquals(bytes,Files.readAllBytes(ledger.verifiedFile(asset.getString("id")).toPath()));
    }
    @Test public void cancellationAndRecycledFilesNeverLeaveUsableStaging()throws Exception{
        AssetLedger ledger=new AssetLedger(temporary.getRoot(),WorkspaceState.id());JSONObject asset=ledger.importCopy(new ByteArrayInputStream(new byte[]{1,2,3}),"data.txt","text/plain","import",null);JSONArray snapshot=HandoffFiles.snapshot(ledger,new JSONArray().put(asset.getString("id")));String cancelled=WorkspaceState.id();assertThrows(Exception.class,()->HandoffFiles.stage(temporary.getRoot(),cancelled,ledger,snapshot,()->false));assertThrows(Exception.class,()->HandoffFiles.staging(temporary.getRoot(),cancelled,false));ledger.trash(asset.getString("id"),true);assertThrows(Exception.class,()->HandoffFiles.snapshot(ledger,new JSONArray().put(asset.getString("id"))));
    }
    @Test public void fileIdsAndStagingPathsCannotEscapeOwnedDirectories()throws Exception{
        assertThrows(Exception.class,()->HandoffFiles.staging(temporary.getRoot(),"../outside",true));File outside=temporary.newFolder("outside"),data=temporary.newFolder("data");Files.createSymbolicLink(new File(data,"handoff").toPath(),outside.toPath());assertThrows(Exception.class,()->HandoffFiles.staging(data,WorkspaceState.id(),true));assertEquals(0,outside.list().length);
    }
}
