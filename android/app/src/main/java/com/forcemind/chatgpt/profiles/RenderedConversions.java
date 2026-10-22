package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class RenderedConversions {
    private static void active(BooleanSupplier available)throws IOException{if(Thread.currentThread().isInterrupted()||!available.getAsBoolean())throw new InterruptedIOException("转换已取消，原文件保留");}
    static JSONObject accept(Context context,AssetLedger ledger,JSONObject pending,BooleanSupplier available)throws Exception{
        active(available);String token=pending.getString("task");JSONObject request=RenderTasks.request(context.getCacheDir(),token);File result=RenderTasks.result(context.getCacheDir(),token);
        String format=request.getString("format");
        if(format.equals("PDF")){
            try(ParcelFileDescriptor file=ParcelFileDescriptor.open(result,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(file)){if(pdf.getPageCount()<1||pdf.getPageCount()>100)throw new IOException("PDF 页面无效或超过 100 页");}
        }else{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(result.getPath(),bounds);
            String expected=format.equals("JPEG")?"image/jpeg":format.equals("WEBP")?"image/webp":"image/png";
            if(!expected.equals(bounds.outMimeType)||bounds.outWidth!=request.getInt("width")||bounds.outHeight<1||bounds.outHeight>RenderTasks.MAX_HEIGHT||(long)bounds.outWidth*bounds.outHeight>RenderTasks.MAX_PIXELS)throw new IOException("图片内容、尺寸或格式与转换请求不一致");
        }
        active(available);return RenderTasks.archive(context.getCacheDir(),ledger,pending,available);
    }
    static List<JSONObject> pdfPages(Context context,AssetLedger ledger,String id,List<Integer> pages,String format,int width,int quality,BooleanSupplier available)throws Exception{
        if(!format.equals("PNG")&&!format.equals("JPEG"))throw new IOException("PDF 仅支持转为 PNG / JPEG");
        if(width!=640&&width!=1280&&width!=2048&&width!=4096||quality<1||quality>100)throw new IOException("输出尺寸或质量无效");
        if(pages==null||pages.isEmpty()||pages.size()>20||new HashSet<>(pages).size()!=pages.size()||pages.stream().anyMatch(p->p==null||p<1))throw new IOException("一次请选择 1–20 个不同页码");
        active(available);JSONObject source=ledger.get(id);if(!ConversionType.of(source).equals("pdf"))throw new IOException("请选择 PDF 文件");File original=ledger.verifiedFile(id);
        File cache=new File(context.getCacheDir().getCanonicalFile(),"asset-conversions");if(Files.isSymbolicLink(cache.toPath())||!cache.getCanonicalFile().equals(cache.getAbsoluteFile()))throw new IOException("转换临时目录无效");if(!cache.exists()&&!cache.mkdir())throw new IOException("无法创建转换临时目录");
        List<JSONObject> completed=new ArrayList<>();String name=source.getString("name");int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(descriptor)){
            for(int page:pages)if(page>pdf.getPageCount())throw new IOException("页码超出范围，此 PDF 共 "+pdf.getPageCount()+" 页");
            for(int number:pages){
                active(available);File output=File.createTempFile("pdf-page-",".tmp",cache);Bitmap image=null;
                try{
                    try(PdfRenderer.Page page=pdf.openPage(number-1)){
                        if(page.getWidth()<1||page.getHeight()<1)throw new IOException("PDF 页面尺寸无效");
                        double target=width*(double)page.getHeight()/page.getWidth();
                        if(!Double.isFinite(target)||target>RenderTasks.MAX_HEIGHT)throw new IOException("目标图片过长，请选择较小宽度");
                        int height=Math.max(1,(int)Math.round(target));
                        if((long)width*height>RenderTasks.MAX_PIXELS)throw new IOException("目标图片超过 1600 万像素，请选择较小宽度");
                        image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                    active(available);try(FileOutputStream stream=new FileOutputStream(output)){if(!image.compress(format.equals("PNG")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,quality,stream))throw new IOException("图片编码未完成");stream.getFD().sync();}
                    image.recycle();image=null;
                    try(InputStream input=Files.newInputStream(output.toPath(),LinkOption.NOFOLLOW_LINKS)){completed.add(ledger.importCopy(input,name+"-p"+number+"."+(format.equals("PNG")?"png":"jpg"),format.equals("PNG")?"image/png":"image/jpeg","conversion",id,available));}
                }finally{if(image!=null)image.recycle();Files.deleteIfExists(output.toPath());}
            }
            return completed;
        }catch(Exception|OutOfMemoryError e){throw new IOException("已生成 "+completed.size()+" 页，其余未完成，原文件保留。"+(e.getMessage()==null?"请缩小尺寸或重试":e.getMessage()),e);}
    }
}
