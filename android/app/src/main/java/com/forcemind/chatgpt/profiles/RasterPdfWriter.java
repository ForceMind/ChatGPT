package com.forcemind.chatgpt.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Lossless RGB strips streamed as standard PDF 1.4 image XObjects. */
final class RasterPdfWriter {
    interface Rows {void read(int row,byte[] rgb)throws IOException;}
    private final OutputStream output;
    private final int width,expectedPages;
    private final List<Long> offsets=new ArrayList<>();
    private final List<Integer> pageIds=new ArrayList<>();
    private long position;
    private int pageId,pageHeight,rowsWritten;
    private StringBuilder resources,commands;
    private boolean finished;
    RasterPdfWriter(OutputStream output,int width,int pages)throws IOException{
        if(width<1||width>8192||pages<1||pages>100)throw new IOException("PDF 尺寸或页数无效");this.output=output;this.width=width;expectedPages=pages;
        offsets.add(0L);reserve();reserve();write(new byte[]{'%','P','D','F','-','1','.','4','\n','%',(byte)0xe2,(byte)0xe3,(byte)0xcf,(byte)0xd3,'\n'});
        object(1,"<< /Type /Catalog /Pages 2 0 R >>\n");
    }
    private int reserve(){offsets.add(-1L);return offsets.size()-1;}
    private void write(byte[] bytes)throws IOException{output.write(bytes);position+=bytes.length;}
    private void text(String value)throws IOException{write(value.getBytes(StandardCharsets.US_ASCII));}
    private void beginObject(int id)throws IOException{if(offsets.get(id)>=0)throw new IOException("PDF 对象重复");offsets.set(id,position);text(id+" 0 obj\n");}
    private void object(int id,String value)throws IOException{beginObject(id);text(value);text("endobj\n");}
    void beginPage(int height)throws IOException{
        if(finished||pageId!=0||pageIds.size()>=expectedPages||height<1||height>Math.floor(762d*width/515d))throw new IOException("PDF 页面状态无效");
        pageId=reserve();pageIds.add(pageId);pageHeight=height;rowsWritten=0;resources=new StringBuilder();commands=new StringBuilder();
    }
    void strip(int height,Rows rows)throws IOException{
        if(finished||pageId==0||height<1||height>1024||rowsWritten+height>pageHeight)throw new IOException("PDF 条带范围无效");
        int image=reserve(),length=reserve();beginObject(image);
        text("<< /Type /XObject /Subtype /Image /Width "+width+" /Height "+height+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "+length+" 0 R >>\nstream\n");
        long start=position;Deflater deflater=new Deflater();
        OutputStream counting=new OutputStream(){
            @Override public void write(int value)throws IOException{output.write(value);position++;}
            @Override public void write(byte[] bytes,int off,int len)throws IOException{output.write(bytes,off,len);position+=len;}
        };
        try{
            DeflaterOutputStream encoded=new DeflaterOutputStream(counting,deflater,32768);byte[] rgb=new byte[width*3];
            for(int row=0;row<height;row++){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("PDF 转换已取消");rows.read(row,rgb);encoded.write(rgb);}encoded.finish();
        }finally{deflater.end();}
        long size=position-start;text("\nendstream\nendobj\n");object(length,size+"\n");
        resources.append("/I").append(image).append(' ').append(image).append(" 0 R ");
        double scaled=height*515d/width,bottom=802d-(rowsWritten+height)*515d/width;
        commands.append(String.format(Locale.ROOT,"q\n515 0 0 %.6f 40 %.6f cm\n/I%d Do\nQ\n",scaled,bottom,image));rowsWritten+=height;
    }
    void endPage()throws IOException{
        if(finished||pageId==0||rowsWritten!=pageHeight)throw new IOException("PDF 页面内容不完整");
        int content=reserve();byte[] bytes=commands.toString().getBytes(StandardCharsets.US_ASCII);beginObject(content);text("<< /Length "+bytes.length+" >>\nstream\n");write(bytes);text("endstream\nendobj\n");
        object(pageId,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << "+resources+">> >> /Contents "+content+" 0 R >>\n");pageId=0;resources=null;commands=null;
    }
    void finish()throws IOException{
        if(finished||pageId!=0||pageIds.size()!=expectedPages)throw new IOException("PDF 页面尚未全部完成");
        StringBuilder pages=new StringBuilder("<< /Type /Pages /Count ").append(pageIds.size()).append(" /Kids [");for(int id:pageIds)pages.append(id).append(" 0 R ");pages.append("] >>\n");object(2,pages.toString());
        long xref=position;text("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n");
        for(int id=1;id<offsets.size();id++){if(offsets.get(id)<0)throw new IOException("PDF 引用不完整");text(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(id)));}
        text("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");output.flush();finished=true;
    }
}
