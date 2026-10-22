package com.forcemind.chatgpt.profiles;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageSelectionTest {
    @Test public void orderedPagesRangesAndChineseCommaArePreserved()throws Exception{
        assertEquals(Arrays.asList(5,1,2,3),PageSelection.parse("5，1 - 3"));assertEquals(Collections.singletonList(1),PageSelection.parse(null));
        assertEquals(Collections.singletonList(Integer.MAX_VALUE),PageSelection.parse("2147483647"));
    }
    @Test public void invalidDuplicateOverflowAndExcessiveSelectionsAreRejected(){
        for(String value:new String[]{"","0","-1","1.5","1,,2","1,","3-1","1,1","1-3,2","1-21","2147483648","1-2147483647","1;2"})assertThrows(value,Exception.class,()->PageSelection.parse(value));
    }
    @Test public void knownNamesWinOverIncorrectProviderMime()throws Exception{
        assertEquals("pdf",ConversionType.of(new org.json.JSONObject().put("name","page.PDF").put("mime","text/html")));
        assertEquals("svg",ConversionType.of(new org.json.JSONObject().put("name","chart.svg").put("mime","image/png")));
        assertEquals("csv",ConversionType.of(new org.json.JSONObject().put("name","data.csv").put("mime","application/pdf")));
        assertEquals("html",ConversionType.of(new org.json.JSONObject().put("name","unknown").put("mime","text/html")));
    }
}
