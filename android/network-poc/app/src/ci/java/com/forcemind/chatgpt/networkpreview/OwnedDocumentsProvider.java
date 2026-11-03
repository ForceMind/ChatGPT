package com.forcemind.chatgpt.networkpreview;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** CI-only document storage. Every exposed file is created inside this fake app UID. */
public final class OwnedDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_COLUMNS = {DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_ICON};
    private static final String[] DOCUMENT_COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private File directory;
    @Override public boolean onCreate() {
        if (getContext() == null || !getContext().getPackageName().endsWith(".ci")) return false;
        directory = new File(getContext().getFilesDir(), "owned-joint-documents");
        return directory.isDirectory() || directory.mkdirs();
    }
    private File document(String id) throws FileNotFoundException {
        if (directory == null || !id.matches("d-[a-f0-9]{32}")) throw new FileNotFoundException("Unknown owned document");
        File file = new File(directory, id);
        if (!file.isFile()) throw new FileNotFoundException("Unknown owned document");
        return file;
    }
    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = "root".equals(id);
        File file = root ? directory : document(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String name : cursor.getColumnNames()) {
            Object value = null;
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(name)) value = id;
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(name)) value = root ? "Owned CI documents" : id;
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(name)) value = root ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(name)) value = root ? 0L : file.length();
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(name)) value = root ? 0L : file.lastModified();
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(name)) value = root ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String name : cursor.getColumnNames()) {
            Object value = null;
            if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(name)) value = "owned-ci-root";
            else if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(name)) value = "root";
            else if (DocumentsContract.Root.COLUMN_TITLE.equals(name)) value = "Owned CI documents";
            else if (DocumentsContract.Root.COLUMN_FLAGS.equals(name)) value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_LOCAL_ONLY;
            else if (DocumentsContract.Root.COLUMN_MIME_TYPES.equals(name)) value = "application/octet-stream";
            else if (DocumentsContract.Root.COLUMN_ICON.equals(name)) value = android.R.drawable.ic_menu_save;
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection); addDocument(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("Unknown owned parent");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile() && file.getName().matches("d-[a-f0-9]{32}")) addDocument(cursor, file.getName());
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("Unknown owned parent");
        File[] files = directory.listFiles();
        if (files == null || files.length >= 128) throw new FileNotFoundException("Owned document limit");
        String id = "d-" + UUID.randomUUID().toString().replace("-", "");
        try { if (!new File(directory, id).createNewFile()) throw new IOException("Owned document collision"); }
        catch (IOException failure) { throw new FileNotFoundException("Cannot create owned document"); }
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(document(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!document(id).delete()) throw new FileNotFoundException("Cannot remove owned document");
    }
}
