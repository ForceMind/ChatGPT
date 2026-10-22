package com.forcemind.chatgpt.profiles;

import android.content.*;
import android.database.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Test APK only. Exactly three public, non-sensitive fixture files; no path input. */
public final class FixtureFilesProvider extends ContentProvider {
    static final String AUTHORITY="com.forcemind.chatgpt.profiles.testfiles";
    private File directory;
    @Override public boolean onCreate(){
        try{
            directory=new File(getContext().getCacheDir(),"fixed-files");if(!directory.exists()&&!directory.mkdir())return false;
            try(FileOutputStream out=new FileOutputStream(new File(directory,"page.html"))){out.write("<!doctype html><html><head><meta charset=\"utf-8\"></head><body><h1>本地 HTML 预览</h1><p>仅用于文件预览验收。</p><script>window.shouldNotExecute=true</script></body></html>".getBytes(StandardCharsets.UTF_8));}
            Bitmap image=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888);image.eraseColor(Color.rgb(70,70,70));
            try(FileOutputStream out=new FileOutputStream(new File(directory,"image.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            PdfDocument pdf=new PdfDocument();PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(240,320,1).create());Paint paint=new Paint();paint.setTextSize(18);page.getCanvas().drawText("Local PDF preview",20,50,paint);pdf.finishPage(page);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"page.pdf"))){pdf.writeTo(out);}pdf.close();return true;
        }catch(Exception e){return false;}
    }
    private File file(Uri uri)throws FileNotFoundException{
        String path=uri.getPath();String name="/html".equals(path)?"page.html":"/image".equals(path)?"image.png":"/pdf".equals(path)?"page.pdf":null;
        if(name==null||!AUTHORITY.equals(uri.getAuthority()))throw new FileNotFoundException("Unknown fixture");return new File(directory,name);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{
            File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?f.getName():OpenableColumns.SIZE.equals(columns[i])?f.length():null;
            cursor.addRow(values);return cursor;
        }catch(Exception e){return null;}
    }
    @Override public String getType(Uri uri){String p=uri.getPath();return "/html".equals(p)?"text/html":"/image".equals(p)?"image/png":"/pdf".equals(p)?"application/pdf":"application/octet-stream";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read-only fixtures");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
