package com.forcemind.chatgpt.profiles;

import java.io.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentFormatsTest {
    private String csv(String value)throws Exception{StringWriter output=new StringWriter();DocumentFormats.jsonToCsv(value,output);return output.toString();}
    private JSONArray json(String value,char delimiter)throws Exception{StringWriter output=new StringWriter();DocumentFormats.csvToJson(value,delimiter,output);return new JSONArray(output.toString());}
    @Test public void csvPreservesLeadingZerosUnicodeQuotedNewlinesAndEscapedQuotes()throws Exception{
        JSONArray result=json("\uFEFFcode,说明,empty\r\n00123,\"第一行\r\n第二行,含\"\"引号\"\"\",\r\n",',');
        assertEquals(1,result.length());JSONObject row=result.getJSONObject(0);
        assertEquals("00123",row.getString("code"));assertEquals("第一行\r\n第二行,含\"引号\"",row.getString("说明"));assertEquals("",row.getString("empty"));
    }
    @Test public void delimiterDetectionRespectsQuotedCellsAndExplicitSingleColumns()throws Exception{
        assertEquals('\t',DocumentFormats.delimiter("code\tvalue\n001\t\"a,b;c|d\"\n"));
        assertEquals(';',DocumentFormats.delimiter("code;value\n001;\"a,b\"\n"));
        assertEquals("x;y",json("description\nx;y\n",',').getJSONObject(0).getString("description"));
    }
    @Test public void malformedCsvAndAmbiguousHeadersNeverProduceSuccess() {
        for(String value:new String[]{"a,a\n1,2","a, \n1,2","a,b\n1","a,b\n1,2,3","a,b\n\"unterminated,2","a,b\n\"ok\"suffix,2"}){
            assertThrows(value,Exception.class,()->json(value,','));
        }
    }
    @Test public void whitespaceRowsAreSkippedAndHeaderOnlyCsvRemainsValid()throws Exception{
        assertEquals(0,json("a,b\n",',').length());
        assertEquals(1,json("\n  ,\n a,b\n1,2\n, \n",',').length());
    }
    @Test public void jsonExportsSameFieldsInFirstRecordOrderWithoutNumericPrecisionLoss()throws Exception{
        String result=csv("[{\"id\":9007199254740993,\"value\":-42,\"flag\":true,\"empty\":null},{\"flag\":false,\"id\":2,\"empty\":\"\",\"value\":1.2300}]");
        assertEquals("\uFEFF\"id\",\"value\",\"flag\",\"empty\"\r\n\"9007199254740993\",\"-42\",\"true\",\"\"\r\n\"2\",\"1.2300\",\"false\",\"\"\r\n",result);
    }
    @Test public void csvFormulaPrefixesAreProtectedInHeadersAndStringCells()throws Exception{
        String result=csv("[{\"=header\":\"=SUM(1,2)\",\"a\":\"  @A1\",\"b\":\"-42\",\"c\":\"line1\\nline2 \\\"q\\\"\"}]");
        assertTrue(result.startsWith("\uFEFF\"'=header\""));assertTrue(result.contains("\"'=SUM(1,2)\""));
        assertTrue(result.contains("\"'  @A1\""));assertTrue(result.contains("\"'-42\""));assertTrue(result.contains("line1\nline2"));
    }
    @Test public void strictJsonRejectsCommentsDuplicatesTrailingContentAndNestedValues(){
        for(String value:new String[]{"[]","{}","[1]","[{}]","[{\"a\":1},{\"b\":2}]","[{\"a\":[]}]","[{\"a\":{}}]","[{\"a\":1,\"a\":2}]","[{\"a\":NaN}]","[{\"a\":1e999}]","[{'a':1}]","[{a:1}]","[{\"a\":1,}]","[{\"a\":1}] trailing","[/* comment */{\"a\":1}]"}){
            assertThrows(value,Exception.class,()->csv(value));
        }
    }
    @Test public void markdownPreservesRichStructureAndEscapesActiveHtml()throws Exception{
        StringWriter output=new StringWriter();DocumentFormats.markdownHtml("# 标题\n\n- [x] 已完成\n\n|列一|列二|\n|---|---|\n|**粗体**|~~删除~~|\n\n```html\n<script>literal</script>\n```\n\n<script>alert(1)</script>\n\n[危险](javascript:alert(1))\n\nhttps://example.test/path\n",output);
        String html=output.toString();assertTrue(html.contains("<h1>标题</h1>"));assertTrue(html.contains("<table>"));assertTrue(html.contains("<strong>粗体</strong>"));
        assertTrue(html.contains("<del>删除</del>"));assertTrue(html.contains("type=\"checkbox\""));assertTrue(html.contains("checked"));
        assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertFalse(html.contains("href=\"javascript:"));
        assertTrue(html.contains("href=\"https://example.test/path\""));assertTrue(html.contains("script-src 'none'"));
    }
    @Test public void oversizedTablesAndInterruptedConversionsFailExplicitly()throws Exception{
        StringBuilder source=new StringBuilder("a\n");for(int i=0;i<DocumentFormats.MAX_ROWS+1;i++)source.append("x\n");
        assertThrows(IOException.class,()->json(source.toString(),','));
        String wide=String.join(",",java.util.Collections.nCopies(DocumentFormats.MAX_COLUMNS+1,"field"));
        assertThrows(IOException.class,()->json(wide,','));
        Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->csv("[{\"a\":1}]"));}finally{Thread.interrupted();}
    }
}
