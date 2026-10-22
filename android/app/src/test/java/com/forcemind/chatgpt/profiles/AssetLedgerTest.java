package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AssetLedgerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private AssetLedger ledger(String id) throws Exception { return new AssetLedger(temporary.getRoot(),id); }
    private JSONObject put(AssetLedger ledger,String name,String text) throws Exception {
        return ledger.importCopy(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),name,"text/plain","import",null);
    }
    @Test public void copiesBytesAndPersistsDigestWithoutChangingOriginal() throws Exception {
        String account=WorkspaceState.id();AssetLedger ledger=ledger(account);
        File source=temporary.newFile("original.txt");Files.write(source.toPath(),"原件".getBytes(StandardCharsets.UTF_8));
        JSONObject a;
        try(InputStream input=Files.newInputStream(source.toPath())) { a=ledger.importCopy(input,"原件.txt","text/plain","import",null); }
        assertEquals("ready",a.getString("status"));assertEquals(64,a.getString("sha256").length());
        assertArrayEquals(Files.readAllBytes(source.toPath()),Files.readAllBytes(ledger.verifiedFile(a.getString("id")).toPath()));
        assertEquals(1,ledger(account).list().size());assertEquals(account,a.getString("accountId"));
    }
    @Test public void sameNamesAndDifferentAccountsNeverShareFiles() throws Exception {
        AssetLedger a=ledger(WorkspaceState.id()),b=ledger(WorkspaceState.id());
        JSONObject one=put(a,"same.txt","A"),two=put(a,"same.txt","AA"),other=put(b,"same.txt","B");
        assertNotEquals(one.getString("id"),two.getString("id"));assertEquals(2,a.list().size());assertEquals(1,b.list().size());
        assertThrows(Exception.class,()->b.get(one.getString("id")));
        assertEquals("B",new String(Files.readAllBytes(b.verifiedFile(other.getString("id")).toPath()),StandardCharsets.UTF_8));
    }
    @Test public void renameFavoritesTrashAndDerivedCopiesKeepOriginalBytes() throws Exception {
        AssetLedger a=ledger(WorkspaceState.id());JSONObject original=put(a,"original.txt","original");String id=original.getString("id");
        JSONObject derived=a.importCopy(new ByteArrayInputStream("derived".getBytes(StandardCharsets.UTF_8)),"derived.txt","text/plain","conversion",id);
        a.rename(id,"renamed.txt");a.favorite(id,true);a.trash(id,true);
        assertThrows(Exception.class,()->a.verifiedFile(id));a.trash(id,false);
        assertTrue(a.get(id).getBoolean("favorite"));assertEquals("renamed.txt",a.get(id).getString("name"));
        assertEquals(id,derived.getString("parentId"));assertEquals("original",new String(Files.readAllBytes(a.verifiedFile(id).toPath()),StandardCharsets.UTF_8));
        a.trash(id,true);a.remove(id);assertThrows(Exception.class,()->a.get(id));assertTrue(a.verifiedFile(derived.getString("id")).isFile());
    }
    @Test public void corruptIndexRecoversBackupAndKeepsCorruptOriginal() throws Exception {
        String account=WorkspaceState.id();AssetLedger a=ledger(account);JSONObject one=put(a,"one.txt","one");put(a,"two.txt","two");
        File dir=new File(new File(temporary.getRoot(),"assets"),account);
        Files.write(new File(dir,"index.json").toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        AssetLedger recovered=ledger(account);assertEquals("one",new String(Files.readAllBytes(recovered.verifiedFile(one.getString("id")).toPath()),StandardCharsets.UTF_8));
        assertTrue(Arrays.stream(Objects.requireNonNull(dir.list())).anyMatch(n->n.startsWith("index.corrupt-")));
    }
    @Test public void changedBytesAndSymlinkFilesCannotBePreviewed() throws Exception {
        AssetLedger a=ledger(WorkspaceState.id());JSONObject one=put(a,"one.txt","kept");File file=a.verifiedFile(one.getString("id"));
        Files.write(file.toPath(),"changed".getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->a.verifiedFile(one.getString("id")));
        File outside=temporary.newFile("outside.txt");Files.write(outside.toPath(),"private".getBytes(StandardCharsets.UTF_8));
        Files.delete(file.toPath());Files.createSymbolicLink(file.toPath(),outside.toPath());assertThrows(Exception.class,()->a.verifiedFile(one.getString("id")));
        assertEquals("private",new String(Files.readAllBytes(outside.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void failedCopyLeavesAnExplicitFailureAndNoPartialSuccess() throws Exception {
        AssetLedger a=ledger(WorkspaceState.id());
        InputStream broken=new InputStream(){@Override public int read() throws IOException {throw new IOException("local fixture interruption");}};
        assertThrows(Exception.class,()->a.importCopy(broken,"bad.txt","text/plain","import",null));
        assertEquals("failed",a.list().get(0).getString("status"));
        assertThrows(Exception.class,()->a.verifiedFile(a.list().get(0).getString("id")));
    }
    @Test public void aMissingIndexNeverSilentlyHidesRetainedBytes() throws Exception {
        String account=WorkspaceState.id();AssetLedger a=ledger(account);put(a,"one.txt","one");
        File dir=new File(new File(temporary.getRoot(),"assets"),account);
        Files.delete(new File(dir,"index.json").toPath());Files.delete(new File(dir,"index.backup.json").toPath());
        assertThrows(Exception.class,()->ledger(account));
    }
    @Test public void filenamesArePortableBoundedAndKeepTheirExtension() {
        assertEquals("_CON.txt",AssetLedger.filename("CON.txt"));assertEquals("x_stream.txt",AssetLedger.filename("x:stream.txt"));
        String name=AssetLedger.filename(String.join("",Collections.nCopies(200,"图"))+".png");
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length<=180);assertTrue(name.endsWith(".png"));
        assertEquals("file.txt",AssetLedger.filename("../file.txt"));
    }

    @Test public void aCancelledOwnerCannotPublishAReadyFile() throws Exception {
        AssetLedger a=ledger(WorkspaceState.id());java.util.concurrent.atomic.AtomicBoolean available=new java.util.concurrent.atomic.AtomicBoolean(true);
        InputStream source=new ByteArrayInputStream("local cancellation fixture".getBytes(StandardCharsets.UTF_8)){
            @Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);available.set(false);return n;}
        };
        assertThrows(Exception.class,()->a.importCopy(source,"cancel.txt","text/plain","import",null,available::get));
        assertEquals("failed",a.list().get(0).getString("status"));assertFalse(a.list().get(0).getString("status").equals("ready"));
        java.util.concurrent.atomic.AtomicBoolean removed=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(Exception.class,()->AssetLedger.withEmptyAccount(temporary.getRoot(),a.accountId,()->removed.set(true)));
        assertFalse(removed.get());
    }
}
