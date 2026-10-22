package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class RasterPdfWriterTest {
    private void strip(RasterPdfWriter writer,int color)throws Exception{writer.strip(2,(row,rgb)->{for(int x=0;x<16;x++){rgb[x*3]=(byte)(color==0?255:0);rgb[x*3+1]=0;rgb[x*3+2]=(byte)(color==1?255:0);}});}
    @Test public void standardXrefOffsetsAndFlateRgbStreamsDescribeAllPages()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();RasterPdfWriter writer=new RasterPdfWriter(bytes,16,2);
        for(int page=0;page<2;page++){writer.beginPage(4);strip(writer,0);strip(writer,1);writer.endPage();}writer.finish();
        byte[] pdf=bytes.toByteArray();String text=new String(pdf,StandardCharsets.ISO_8859_1);assertTrue(text.startsWith("%PDF-1.4\n"));assertTrue(text.contains("/Count 2 /Kids"));
        Matcher start=Pattern.compile("startxref\\n([0-9]+)\\n%%EOF").matcher(text);assertTrue(start.find());int xref=Integer.parseInt(start.group(1));assertTrue(text.startsWith("xref\n",xref));
        String[] lines=text.substring(xref).split("\n");int count=Integer.parseInt(lines[1].split(" ")[1]);int[] offsets=new int[count];
        for(int id=1;id<count;id++){offsets[id]=Integer.parseInt(lines[id+2].substring(0,10));assertTrue(text.startsWith(id+" 0 obj\n",offsets[id]));}
        int images=0;
        for(int id=1;id<count;id++){
            int end=text.indexOf("endobj\n",offsets[id]);String object=text.substring(offsets[id],end);
            if(!object.contains("/Subtype /Image"))continue;
            assertTrue(object.contains("/Width 16 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"));
            Matcher length=Pattern.compile("/Length ([0-9]+) 0 R").matcher(object);assertTrue(length.find());int lengthId=Integer.parseInt(length.group(1));
            int lengthStart=text.indexOf('\n',offsets[lengthId])+1,lengthEnd=text.indexOf('\n',lengthStart),size=Integer.parseInt(text.substring(lengthStart,lengthEnd));
            int data=text.indexOf("stream\n",offsets[id])+7;ByteArrayOutputStream raw=new ByteArrayOutputStream();
            try(InputStream input=new InflaterInputStream(new ByteArrayInputStream(pdf,data,size))){byte[] buffer=new byte[128];int n;while((n=input.read(buffer))!=-1)raw.write(buffer,0,n);}
            byte[] rgb=raw.toByteArray();assertEquals(16*2*3,rgb.length);for(int i=0;i<rgb.length;i+=3){assertEquals(images%2==0?255:0,rgb[i]&255);assertEquals(0,rgb[i+1]&255);assertEquals(images%2==1?255:0,rgb[i+2]&255);}images++;
        }
        assertEquals(4,images);
    }
    @Test public void incompletePagesAndOutOfOrderOperationsNeverProduceFinishedPdf()throws Exception{
        RasterPdfWriter writer=new RasterPdfWriter(new ByteArrayOutputStream(),16,1);assertThrows(IOException.class,writer::finish);assertThrows(IOException.class,writer::endPage);
        writer.beginPage(4);assertThrows(IOException.class,()->writer.beginPage(4));assertThrows(IOException.class,()->writer.strip(5,(row,rgb)->{}));
        strip(writer,0);assertThrows(IOException.class,writer::endPage);assertThrows(IOException.class,writer::finish);strip(writer,1);writer.endPage();writer.finish();assertThrows(IOException.class,writer::finish);assertThrows(IOException.class,()->writer.beginPage(1));
    }
    @Test public void interruptionAndOutputFailuresPropagateToTheOwner()throws Exception{
        RasterPdfWriter writer=new RasterPdfWriter(new ByteArrayOutputStream(),16,1);writer.beginPage(2);Thread.currentThread().interrupt();
        try{assertThrows(InterruptedIOException.class,()->strip(writer,0));}finally{Thread.interrupted();}
        OutputStream full=new OutputStream(){int size;@Override public void write(int value)throws IOException{if(++size>300)throw new IOException("fixture disk full");}};
        assertThrows(IOException.class,()->{RasterPdfWriter limited=new RasterPdfWriter(full,16,1);limited.beginPage(4);strip(limited,0);strip(limited,1);limited.endPage();limited.finish();});
    }
}
