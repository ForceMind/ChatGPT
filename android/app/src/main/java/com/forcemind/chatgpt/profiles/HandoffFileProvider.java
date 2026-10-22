package com.forcemind.chatgpt.profiles;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;

public final class HandoffFileProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private HandoffUploads.FileInfo resolve(Uri uri)throws FileNotFoundException{
        if(Binder.getCallingUid()!=android.os.Process.myUid())throw new FileNotFoundException("附件仅供本应用任务使用");try{return HandoffUploads.resolve(getContext(),uri);}catch(Exception e){throw new FileNotFoundException("附件不可用或授权已撤销");}
    }
    @Override public String getType(Uri uri){try{return resolve(uri).metadata.getString("uploadMime");}catch(Exception e){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{
        HandoffUploads.FileInfo info=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?info.metadata.getString("uploadName"):columns[i].equals(OpenableColumns.SIZE)?info.metadata.getLong("size"):null;result.addRow(values);return result;
    }catch(Exception e){return null;}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("附件只读");return ParcelFileDescriptor.open(resolve(uri).file,ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读附件");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("只读附件");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("只读附件");}
}
