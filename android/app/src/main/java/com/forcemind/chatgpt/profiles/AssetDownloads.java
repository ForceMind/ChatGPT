package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.URLUtil;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account and route remain captured while the user switches tabs/windows. */
final class AssetDownloads {
    private final Context context;
    private final NetworkSession network;
    private final ExecutorService publish = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    AssetDownloads(Context context) { this.context = context.getApplicationContext(); network = BrowserMemory.get(context).network(); }
    void start(WebView web, String accountId, String accountName, String url, String agent, String disposition, String mime) {
        if (closed || !NavigationPolicy.https(url)) { notice("当前链接暂不支持归档"); return; }
        final NetworkSession.FileJob job;
        try { job = network.begin(web, accountId); } catch (Exception failure) { notice(failure.getMessage()); return; }
        String name = URLUtil.guessFileName(url, disposition, mime);
        notice(accountName + " · 正在下载到资产库…");
        NetworkDownload.start(context, network, job, url, agent, (file, error) -> {
            if (file == null) { job.finish(); notice(error); return; }
            try { publish.execute(() -> {
                boolean success = false;
                try (FileInputStream input = new FileInputStream(file)) {
                    if (closed || !job.publishAllowed()) throw new java.io.IOException("任务已失效");
                    new AssetLedger(context.getFilesDir(), accountId).importCopy(input, name, mime, "download", null,
                            () -> !closed && job.valid());
                    success = true;
                } catch (Exception failure) { /* Only terminal state, never URLs/cookies. */ }
                finally { if (file.exists() && !file.delete()) android.util.Log.w("StudioNetwork", "Owned asset staging cleanup failed"); job.finish(); }
                final boolean published = success;
                new Handler(Looper.getMainLooper()).post(() -> { if (job.valid()) notice(published ? accountName + " · 文件已归档" : "归档失败或已取消，原文件保留"); });
            }); } catch (java.util.concurrent.RejectedExecutionException stopped) { if (file.exists()) file.delete(); job.cancel(); job.finish(); }
        });
    }
    void cancel(String accountId) { network.invalidateAccount(accountId); }
    void close() { closed = true; publish.shutdown(); }
    private void notice(String text) { new Handler(Looper.getMainLooper()).post(() -> { if (!closed && text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }); }
}
