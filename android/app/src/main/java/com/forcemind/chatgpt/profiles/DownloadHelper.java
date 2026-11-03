package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads completely before asking the system to create a new user-owned copy. */
final class DownloadHelper {
    static final int REQUEST_SAVE = 11;
    private final Context context;
    private final NetworkSession network;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> owner;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Selection pending;
    private volatile boolean closed;
    private static final class Selection {
        final NetworkSession.FileJob job;
        final String name, mime;
        File file;
        boolean launched, publishing;
        int resultFlags;
        Selection(NetworkSession.FileJob job, String name, String mime) { this.job = job; this.name = name; this.mime = mime; }
    }
    DownloadHelper(Activity activity) { context = activity.getApplicationContext(); owner = new WeakReference<>(activity); network = BrowserMemory.get(context).network(); }
    void attach(Activity activity) { owner = new WeakReference<>(activity); if (activity != null) main.post(this::offerSave); }
    void choose(WebView web, String url, String agent, String disposition, String mime) {
        if (closed || !NavigationPolicy.https(url)) { notifyUser("当前链接暂不支持保存"); return; }
        if (pending != null) { if (pending.file != null && !pending.launched) offerSave(); else notifyUser("请先完成当前文件任务"); return; }
        try {
            NetworkSession.FileJob job = network.begin(web, BrowserMemory.get(context).accountOf(web));
            Selection selected = new Selection(job, URLUtil.guessFileName(url, disposition, mime), mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime);
            pending = selected;
            job.cancellation = () -> { if (pending == selected && !selected.publishing) finish(selected); };
            notifyUser("正在下载文件…");
            NetworkDownload.start(context, network, job, url, agent, (file, error) -> {
                if (pending != selected || closed || !job.publishAllowed()) { if (file != null) file.delete(); job.finish(); return; }
                if (file == null) { finish(selected); notifyUser(error); return; }
                selected.file = file; offerSave();
            });
        } catch (Exception failure) { notifyUser(failure.getMessage()); }
    }
    private void offerSave() {
        Selection selected = pending;
        if (closed || selected == null || selected.file == null || selected.launched || selected.publishing) return;
        if (!selected.job.publishAllowed()) { finish(selected); notifyUser("账号或线路已改变，旧保存任务已停止"); return; }
        Activity activity = owner.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(selected.mime).putExtra(Intent.EXTRA_TITLE, selected.name);
        if (BuildConfig.NETWORK_TEST) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(BuildConfig.APPLICATION_ID + ".network-docs", "owned-ci-root"));
        try { selected.launched = true; activity.startActivityForResult(intent, REQUEST_SAVE); }
        catch (RuntimeException unavailable) { selected.launched = false; notifyUser("文件已下载，无法打开保存窗口。重新选择另存为可重试"); }
    }
    void cancelSelection() { Selection selected = pending; if (selected != null) { selected.job.cancel(); if (!selected.publishing) finish(selected); } }
    private void finish(Selection selected) {
        if (selected.file != null && selected.file.exists() && !selected.file.delete()) android.util.Log.w("StudioNetwork", "Owned document staging cleanup failed");
        selected.job.finish(); if (pending == selected) pending = null;
    }
    void onResult(int result, Intent data) {
        Selection selected = pending;
        if (selected == null || !selected.launched || selected.publishing) return;
        selected.launched = false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) { finish(selected); return; }
        Uri destination = data.getData(); selected.resultFlags=data.getFlags();ciEvidence(selected,destination,"returned",null);
        if (!"content".equals(destination.getScheme()) || (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0 || !selected.job.publishAllowed()) {
            ciEvidence(selected,destination,"result-unusable",null);finish(selected); notifyUser("保存授权或账号已改变，没有写入或删除目标文件"); return;
        }
        selected.publishing = true; notifyUser("正在保存新副本…");
        writer.execute(() -> {
            boolean success = false, ownedNewDocument = false;
            String problem = "保存失败，已保留原有文档";
            try {
                if (closed || !selected.job.publishAllowed() || !DocumentsContract.isDocumentUri(context, destination)) throw new java.io.IOException("保存目标不可用");
                // ACTION_CREATE_DOCUMENT creates a new file. Refuse nonempty or
                // unqueryable results; never truncate an existing user document.
                try (Cursor row = context.getContentResolver().query(destination, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                    if (row == null || !row.moveToFirst() || row.isNull(0) || row.getLong(0) != 0) throw new java.io.IOException("Cannot establish a newly created empty destination");
                }
                ownedNewDocument = true;ciEvidence(selected,destination,"new-empty-target",null);
                try (FileInputStream input = new FileInputStream(selected.file); OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new java.io.IOException("Cannot write new document");
                    byte[] bytes = new byte[32768]; int n;
                    while ((n = input.read(bytes)) != -1) { if (closed || !selected.job.valid()) throw new java.io.IOException("保存任务已失效"); output.write(bytes, 0, n); }
                    output.flush();
                }
                if (closed || !selected.job.publishAllowed()) throw new java.io.IOException("保存任务已失效");
                success = true;ciEvidence(selected,destination,"saved",null);
            } catch (Exception failure) {
                ciEvidence(selected,destination,"write-failed",failure);
                if (ownedNewDocument) try { DocumentsContract.deleteDocument(context.getContentResolver(), destination); }
                catch (Exception cleanup) { problem = "本次新建副本可能不完整，请在所选位置检查；既有文件未删除"; }
            }
            final boolean saved = success; final String message = problem;
            main.post(() -> { boolean current = selected.job.publishAllowed(); finish(selected); if (!closed) notifyUser(saved && current ? "文件已保存" : message); });
        });
    }
    private void ciEvidence(Selection selected,Uri destination,String stage,Exception failure) {
        if(!BuildConfig.NETWORK_TEST)return;
        try{org.json.JSONObject value=new org.json.JSONObject().put("stage",stage).put("authority",destination.getAuthority()).put("flags",selected.resultFlags).put("taskId",selected.job.id).put("available",selected.job.publishAllowed()).put("failureClass",failure==null?org.json.JSONObject.NULL:failure.getClass().getSimpleName());java.nio.file.Files.write(new File(context.getFilesDir(),"network-document-evidence.json").toPath(),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
    }
    void close() { closed = true; cancelSelection(); writer.shutdown(); owner.clear(); }
    private void notifyUser(String message) { main.post(() -> { if (!closed && message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }); }
}
