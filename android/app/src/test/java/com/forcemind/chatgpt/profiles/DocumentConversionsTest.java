package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DocumentConversionsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private AssetLedger ledger()throws Exception{return new AssetLedger(temporary.getRoot(),WorkspaceState.id());}
    private JSONObject put(AssetLedger ledger,String name,byte[] body)throws Exception{return ledger.importCopy(new ByteArrayInputStream(body),name,null,"import",null);}
    @Test public void conversionCopiesStayOwnedAndPreserveSourceBytesAndParent()throws Exception{
        AssetLedger ledger=ledger(),other=ledger();byte[] bytes="id,说明\n001,中文\n".getBytes(StandardCharsets.UTF_8);
        JSONObject source=put(ledger,"source.csv",bytes);String id=source.getString("id");
        JSONObject output=DocumentConversions.convert(temporary.getRoot(),ledger,id,"JSON",null,()->true);
        assertEquals(ledger.accountId,output.getString("accountId"));assertEquals(id,output.getString("parentId"));assertEquals("conversion",output.getString("source"));assertEquals("source-converted.json",output.getString("name"));
        JSONArray actual=new JSONArray(Files.readString(ledger.verifiedFile(output.getString("id")).toPath()));assertEquals("001",actual.getJSONObject(0).getString("id"));
        assertArrayEquals(bytes,Files.readAllBytes(ledger.verifiedFile(id).toPath()));assertThrows(Exception.class,()->other.get(output.getString("id")));
        assertEquals(0,Objects.requireNonNull(new File(temporary.getRoot(),"asset-conversions").list()).length);
    }
    @Test public void malformedEncodingUnsupportedTypesAndCancellationNeverPublishReadyCopies()throws Exception{
        assertEquals(Collections.singletonList("CSV"),DocumentConversions.formats(new JSONObject().put("name","data.json").put("mime","text/csv")));
        assertEquals(Collections.singletonList("HTML"),DocumentConversions.formats(new JSONObject().put("name","report.markdown").put("mime","application/json")));
        AssetLedger ledger=ledger();JSONObject source=put(ledger,"bad.csv",new byte[]{(byte)0xff});
        assertThrows(Exception.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,source.getString("id"),"JSON",null,()->true));
        JSONObject text=put(ledger,"note.txt","text".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,text.getString("id"),"HTML",null,()->true));
        JSONObject valid=put(ledger,"good.csv","a\nb\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,valid.getString("id"),"JSON",null,()->false));
        assertEquals(3,ledger.list().size());assertTrue(ledger.list().stream().noneMatch(a->a.has("parentId")));
    }
    @Test public void byteLimitsRejectLargeInputsAndExpandedOutputsWithoutPublishingOrLeavingTemps()throws Exception{
        AssetLedger ledger=ledger();byte[] markdown=new byte[DocumentConversions.MAX_MARKDOWN+1];Arrays.fill(markdown,(byte)'a');
        JSONObject source=put(ledger,"large.md",markdown);
        assertThrows(IOException.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,source.getString("id"),"HTML",null,()->true));
        // JSON escaping doubles each backslash; source fits, output exceeds its cap.
        byte[] expanded=new byte[DocumentConversions.MAX_INPUT];Arrays.fill(expanded,(byte)'\\');expanded[0]='a';expanded[1]='\n';
        JSONObject csv=put(ledger,"expanded.csv",expanded);
        assertThrows(IOException.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,csv.getString("id"),"JSON",',',()->true));
        assertEquals(2,ledger.list().size());assertFalse(ledger.list().stream().anyMatch(a->a.has("parentId")));
        assertEquals(0,Objects.requireNonNull(new File(temporary.getRoot(),"asset-conversions").list()).length);
        assertArrayEquals(expanded,Files.readAllBytes(ledger.verifiedFile(csv.getString("id")).toPath()));
    }
    @Test public void failedDocumentConversionCleansTemporaryOutputAndKeepsOriginal()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger,"bad.json","[{\"a\":1},{\"b\":2}]".getBytes(StandardCharsets.UTF_8));String id=source.getString("id");
        assertThrows(Exception.class,()->DocumentConversions.convert(temporary.getRoot(),ledger,id,"CSV",null,()->true));
        assertEquals(1,ledger.list().size());ledger.verifiedFile(id);
        assertEquals(0,Objects.requireNonNull(new File(temporary.getRoot(),"asset-conversions").list()).length);
    }
    @Test public void recycledOrRemovedSourceCannotPublishAConversionEvenMidCopy()throws Exception{
        AssetLedger ledger=ledger();JSONObject source=put(ledger,"source.csv","a\nb".getBytes(StandardCharsets.UTF_8));String id=source.getString("id");ledger.trash(id,true);
        assertThrows(Exception.class,()->ledger.importCopy(new ByteArrayInputStream(new byte[]{1}),"copy.json","application/json","conversion",id));
        ledger.trash(id,false);AtomicBoolean once=new AtomicBoolean();
        InputStream bytes=new ByteArrayInputStream(new byte[]{1,2,3}){
            @Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(once.compareAndSet(false,true))try{ledger.trash(id,true);}catch(Exception e){throw new RuntimeException(e);}return n;}
        };
        assertThrows(Exception.class,()->ledger.importCopy(bytes,"copy.json","application/json","conversion",id));
        assertEquals(1,ledger.list().stream().filter(a->a.has("parentId")&&a.optString("status").equals("failed")).count());
        assertFalse(ledger.list().stream().anyMatch(a->a.has("parentId")&&a.optString("status").equals("ready")));
    }
}
