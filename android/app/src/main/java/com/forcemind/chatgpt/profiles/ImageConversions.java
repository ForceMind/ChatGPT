package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Produces a new owned copy; the source bytes and metadata remain untouched. */
final class ImageConversions {
    static JSONObject convert(Context context,AssetLedger ledger,String id,String format,int maxSide,int quality,BooleanSupplier available)throws Exception{
        if(!format.equals("png")&&!format.equals("jpeg")&&!format.equals("webp"))throw new IOException("转换格式无效");
        if(maxSide!=0&&maxSide!=640&&maxSide!=1280&&maxSide!=2048&&maxSide!=4096)throw new IOException("目标尺寸无效");
        if(quality<1||quality>100)throw new IOException("图片质量无效");
        JSONObject source=ledger.get(id);File original=ledger.verifiedFile(id);
        if(!ConversionType.of(source).equals("image"))throw new IOException("请选择位图；SVG 使用页面渲染转换");
        File cache=new File(context.getCacheDir().getCanonicalFile(),"asset-conversions");
        if(Files.isSymbolicLink(cache.toPath())||!cache.getCanonicalFile().equals(cache.getAbsoluteFile()))throw new IOException("转换临时目录无效");
        if(!cache.exists()&&!cache.mkdir())throw new IOException("无法创建转换临时目录");
        File output=File.createTempFile("convert-","."+format,cache);Bitmap decoded=null,flattened=null;
        try{
            decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(original),(decoder,info,input)->{
                int width=info.getSize().getWidth(),height=info.getSize().getHeight();
                double scale=maxSide==0?1d:Math.min(1d,maxSide/(double)Math.max(width,height));
                int w=Math.max(1,(int)Math.round(width*scale)),h=Math.max(1,(int)Math.round(height*scale));
                if((long)w*h>16_000_000L)throw new IllegalArgumentException("目标图片过大，请选择较小尺寸");
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(w,h);
            });
            if(!available.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("转换已取消");
            Bitmap result=decoded;
            if(format.equals("jpeg")&&decoded.hasAlpha()){
                flattened=Bitmap.createBitmap(decoded.getWidth(),decoded.getHeight(),Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(flattened);canvas.drawColor(Color.WHITE);canvas.drawBitmap(decoded,0,0,null);result=flattened;
            }
            Bitmap.CompressFormat codec=format.equals("png")?Bitmap.CompressFormat.PNG:format.equals("jpeg")?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.WEBP;
            try(FileOutputStream stream=new FileOutputStream(output)){if(!result.compress(codec,quality,stream))throw new IOException("图片编码未完成");stream.getFD().sync();}
            String name=source.getString("name");int dot=name.lastIndexOf('.');if(dot>0)name=name.substring(0,dot);
            String extension=format.equals("jpeg")?"jpg":format;
            try(InputStream input=new FileInputStream(output)){return ledger.importCopy(input,name+"-converted."+extension,"image/"+format,"conversion",id,available);}
        }catch(OutOfMemoryError error){throw new IOException("图片内存不足，请选择较小尺寸或使用桌面版转换",error);}
        finally{if(flattened!=null)flattened.recycle();if(decoded!=null)decoded.recycle();Files.deleteIfExists(output.toPath());}
    }
}
