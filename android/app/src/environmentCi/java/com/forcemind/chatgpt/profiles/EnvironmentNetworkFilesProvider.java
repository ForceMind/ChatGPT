package com.forcemind.chatgpt.profiles;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only owned picker input. This provider is absent from every human APK. */
public final class EnvironmentNetworkFilesProvider extends DocumentsProvider {
    private static final String[] ROOT={DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOCUMENT={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
    private File payload;
    @Override public boolean onCreate(){
        if(!BuildConfig.ENVIRONMENT_NETWORK_TEST||!BuildConfig.ENVIRONMENT_TEST||getContext()==null||!getContext().getPackageName().equals("com.forcemind.chatgpt.profiles.networkbeta"))return false;
        try{
            payload=new File(getContext().getFilesDir().getCanonicalFile(),"gecko-network-owned-upload.bin");
            byte[] unit="owned-gecko-network-upload\n".getBytes(StandardCharsets.UTF_8),bytes=new byte[unit.length*1024];for(int i=0;i<1024;i++)System.arraycopy(unit,0,bytes,i*unit.length,unit.length);
            if(payload.exists()){if(!java.util.Arrays.equals(Files.readAllBytes(payload.toPath()),bytes))throw new IllegalStateException("Owned picker fixture changed");}else Files.write(payload.toPath(),bytes);
            return true;
        }catch(Exception error){throw new IllegalStateException("Cannot initialize owned picker input",error);}
    }
    private void add(MatrixCursor cursor,boolean root){
        MatrixCursor.RowBuilder row=cursor.newRow();for(String key:cursor.getColumnNames()){
            Object value=null;
            if(DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(key))value=root?"root":"upload";
            else if(DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(key))value=root?"Owned Gecko files":"gecko-upload.bin";
            else if(DocumentsContract.Document.COLUMN_MIME_TYPE.equals(key))value=root?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";
            else if(DocumentsContract.Document.COLUMN_FLAGS.equals(key))value=0;
            else if(DocumentsContract.Document.COLUMN_SIZE.equals(key))value=root?0L:payload.length();
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection){MatrixCursor cursor=new MatrixCursor(projection==null?ROOT:projection);MatrixCursor.RowBuilder row=cursor.newRow();for(String key:cursor.getColumnNames()){Object value=null;if(DocumentsContract.Root.COLUMN_ROOT_ID.equals(key))value="owned-gecko-files";else if(DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(key))value="root";else if(DocumentsContract.Root.COLUMN_TITLE.equals(key))value="Owned Gecko files";else if(DocumentsContract.Root.COLUMN_FLAGS.equals(key))value=DocumentsContract.Root.FLAG_LOCAL_ONLY;else if(DocumentsContract.Root.COLUMN_MIME_TYPES.equals(key))value="*/*";row.add(value);}return cursor;}
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{if(!"root".equals(id)&&!"upload".equals(id))throw new FileNotFoundException("Unknown owned document");MatrixCursor cursor=new MatrixCursor(projection==null?DOCUMENT:projection);add(cursor,"root".equals(id));return cursor;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sortOrder)throws FileNotFoundException{if(!"root".equals(parent))throw new FileNotFoundException("Unknown owned root");MatrixCursor cursor=new MatrixCursor(projection==null?DOCUMENT:projection);add(cursor,false);return cursor;}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal cancellation)throws FileNotFoundException{if(!"upload".equals(id)||!"r".equals(mode)||payload==null)throw new FileNotFoundException("Read-only owned input");if(cancellation!=null)cancellation.throwIfCanceled();return ParcelFileDescriptor.open(payload,ParcelFileDescriptor.MODE_READ_ONLY);}
}
