package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Protocol/ownership fixtures only; device tests separately verify actual codecs. */
public class RenderTasksTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private AssetLedger ledger()throws Exception{return new AssetLedger(temporary.getRoot(),WorkspaceState.id());}
    private JSONObject put(AssetLedger ledger)throws Exception{return ledger.importCopy(new ByteArrayInputStream("<h1>本地文件</h1>".getBytes(StandardCharsets.UTF_8)),"source.html","text/html","import",null);}
    private JSONObject prepare(AssetLedger ledger,JSONObject source)throws Exception{return RenderTasks.prepare(temporary.getRoot(),ledger,source.getString("id"),"PDF",1280,90,"测试账号",()->true);}
    private void opaqueResult(JSONObject pending)throws Exception{Files.write(new File(RenderTasks.directory(temporary.getRoot(),pending.getString("task"),false),"result.bin").toPath(),"opaque protocol fixture; not an encoded PDF".getBytes(StandardCharsets.UTF_8));}
    @Test public void inputCopyAndRequestNeverExposeAccountStoragePathsOrIdentityIds()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger),pending=prepare(ledger,source),request=RenderTasks.request(temporary.getRoot(),pending.getString("task"));
        assertFalse(request.has("accountId"));assertFalse(request.has("sourceId"));assertFalse(request.toString().contains(temporary.getRoot().getPath()));
        assertEquals("<h1>本地文件</h1>",RenderTasks.source(temporary.getRoot(),pending.getString("task"),request));assertEquals("测试账号",request.getString("owner"));
        RenderTasks.cleanup(temporary.getRoot(),pending.getString("task"));ledger.verifiedFile(source.getString("id"));
    }
    @Test public void publicationRequiresCapturedOwnerLiveSourceAndAvailableTask()throws Exception{
        AssetLedger owner=ledger(),other=ledger();JSONObject source=put(owner),pending=prepare(owner,source);opaqueResult(pending);
        assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),other,pending,()->true));assertEquals(0,other.list().size());
        owner.trash(source.getString("id"),true);assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),owner,pending,()->true));owner.trash(source.getString("id"),false);
        assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),owner,pending,()->false));
        JSONObject result=RenderTasks.archive(temporary.getRoot(),owner,pending,()->true);assertEquals(owner.accountId,result.getString("accountId"));assertEquals(source.getString("id"),result.getString("parentId"));
        assertEquals(source.getString("sha256"),owner.get(source.getString("id")).getString("sha256"));RenderTasks.cleanup(temporary.getRoot(),pending.getString("task"));
    }
    @Test public void mutatedCopySourceOrPendingMetadataCannotBePublished()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger),pending=prepare(ledger,source);String token=pending.getString("task");opaqueResult(pending);
        JSONObject altered=new JSONObject(pending.toString()).put("format","PNG");assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),ledger,altered,()->true));
        File requestFile=new File(RenderTasks.directory(temporary.getRoot(),token,false),"request.json");String originalRequest=new String(Files.readAllBytes(requestFile.toPath()),StandardCharsets.UTF_8);
        Files.write(requestFile.toPath(),new JSONObject(originalRequest).put("width",640).toString().getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),ledger,pending,()->true));
        Files.write(requestFile.toPath(),new JSONObject(originalRequest).put("width",1280.5).toString().getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->RenderTasks.request(temporary.getRoot(),token));
        Files.write(requestFile.toPath(),originalRequest.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(RenderTasks.directory(temporary.getRoot(),token,false),"source.utf8").toPath(),"changed".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,()->RenderTasks.source(temporary.getRoot(),token,RenderTasks.request(temporary.getRoot(),token)));
        Files.write(ledger.verifiedFile(source.getString("id")).toPath(),"changed original".getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->RenderTasks.archive(temporary.getRoot(),ledger,pending,()->true));assertEquals(1,ledger.list().size());
    }
    @Test public void traversalAndSymlinkCacheCannotReachOutsideTaskDirectory()throws Exception{
        assertThrows(Exception.class,()->RenderTasks.directory(temporary.getRoot(),"../outside",true));
        File outside=temporary.newFolder("outside"),cache=temporary.newFolder("cache");File sentinel=new File(outside,"sentinel");Files.write(sentinel.toPath(),new byte[]{42});
        Files.createSymbolicLink(new File(cache,"asset-render-tasks").toPath(),outside.toPath());AssetLedger ledger=ledger();JSONObject source=put(ledger);
        assertThrows(Exception.class,()->RenderTasks.prepare(cache,ledger,source.getString("id"),"PDF",1280,90,"test",()->true));assertArrayEquals(new byte[]{42},Files.readAllBytes(sentinel.toPath()));assertEquals(1,Objects.requireNonNull(outside.list()).length);
    }
    @Test public void cleanupDoesNotRecurseOrDeleteOriginalAssets()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger),pending=prepare(ledger,source);String token=pending.getString("task");File unknown=new File(RenderTasks.directory(temporary.getRoot(),token,false),"unknown.keep");Files.write(unknown.toPath(),new byte[]{7});
        assertThrows(IOException.class,()->RenderTasks.cleanup(temporary.getRoot(),token));assertTrue(unknown.isFile());ledger.verifiedFile(source.getString("id"));
    }
    @Test public void invalidOptionsAndOversizedInputsFailWithoutCreatingAnOutput()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger);
        assertThrows(Exception.class,()->RenderTasks.prepare(temporary.getRoot(),ledger,source.getString("id"),"JPEG",1280,90,"",()->true));
        assertThrows(Exception.class,()->RenderTasks.prepare(temporary.getRoot(),ledger,source.getString("id"),"PDF",123,90,"",()->true));
        byte[] bytes=new byte[RenderTasks.MAX_SOURCE+1];Arrays.fill(bytes,(byte)'a');JSONObject large=ledger.importCopy(new ByteArrayInputStream(bytes),"large.html","text/html","import",null);
        assertThrows(Exception.class,()->prepare(ledger,large));assertEquals(2,ledger.list().size());
    }
    @Test public void expiredAbandonedTasksAreClearedWhileRecentCopiesAndOriginalRemain()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger),old=prepare(ledger,source),recent=prepare(ledger,source);
        File oldDirectory=RenderTasks.directory(temporary.getRoot(),old.getString("task"),false);assertTrue(oldDirectory.setLastModified(System.currentTimeMillis()-25L*60*60*1000));
        prepare(ledger,source);assertFalse(oldDirectory.exists());assertTrue(RenderTasks.directory(temporary.getRoot(),recent.getString("task"),false).isDirectory());ledger.verifiedFile(source.getString("id"));
    }
    @Test public void invalidUtf8CannotReachTheRendererDespiteAMatchingByteDigest()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=ledger.importCopy(new ByteArrayInputStream(new byte[]{(byte)0xff}),"broken.html","text/html","import",null),pending=prepare(ledger,source);String token=pending.getString("task");
        assertThrows(IOException.class,()->RenderTasks.source(temporary.getRoot(),token,RenderTasks.request(temporary.getRoot(),token)));RenderTasks.cleanup(temporary.getRoot(),token);assertEquals(1,ledger.list().size());
    }
}
