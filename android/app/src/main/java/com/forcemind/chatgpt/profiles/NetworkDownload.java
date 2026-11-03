package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One owned GET, one account and route snapshot, a private staged file, no retries. */
final class NetworkDownload {
    interface Result { void complete(File file, String error); }
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final class Handle {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) new Thread(active::disconnect, "studio-file-cancel").start();
        }
    }
    static File directory(Context context) throws Exception {
        File dir = new File(context.getNoBackupFilesDir(), "network-transfers-v1");
        if (Files.isSymbolicLink(dir.toPath()) || (!dir.isDirectory() && !dir.mkdir())) throw new java.io.IOException("下载暂存不可用");
        return dir;
    }
    static void clearPreviousProcessParts(Context context) throws Exception {
        File dir = directory(context); File[] parts = dir.listFiles();
        if (parts == null) throw new java.io.IOException("下载暂存不可读");
        for (File part : parts) if (part.getName().matches("[a-f0-9-]{36}\\.part") && !Files.isSymbolicLink(part.toPath()) && part.isFile())
            if (!part.delete()) throw new java.io.IOException("上次未完成下载暂存无法清理");
    }
    static Handle start(Context context, NetworkSession session, NetworkSession.FileJob job, String address, String agent, Result callback) {
        Handle handle = new Handle(); job.download = handle;
        if (job.cancelled) handle.cancel();
        IO.execute(() -> {
            File file = null; String error = null; boolean lease = false, owned = false;
            try {
                if (handle.cancelled || !job.valid()) throw new java.io.IOException("任务已失效");
                session.routes.beginTransfer(job.route); lease = true;
                file = new File(directory(context), job.id + ".part");
                if (!file.createNewFile()) throw new java.io.IOException("任务暂存已存在");
                owned = true;
                URL url = new URL(address);
                for (int redirects = 0; ; redirects++) {
                    if (handle.cancelled || !job.valid() || !NavigationPolicy.https(url.toString())) throw new java.io.IOException("任务已取消或目标不可用");
                    HttpURLConnection request = job.route.openConnection(url); handle.connection = request;
                    try {
                        if (handle.cancelled || !job.valid()) throw new java.io.IOException("任务已取消");
                        request.setConnectTimeout(20000); request.setReadTimeout(30000); request.setUseCaches(false); request.setInstanceFollowRedirects(false);
                        if (agent != null) request.setRequestProperty("User-Agent", agent);
                        String cookie = job.cookies.getCookie(url.toString());
                        request.setRequestProperty("Cookie", cookie == null ? "" : cookie);
                        int status = request.getResponseCode();
                        if (status >= 300 && status < 400) {
                            String location = request.getHeaderField("Location");
                            if (redirects >= 5 || location == null) throw new java.io.IOException("重定向无效或过多");
                            url = new URL(url, location); continue;
                        }
                        if (status != 200 || request.getContentLengthLong() > AssetLedger.MAX_FILE) throw new java.io.IOException("文件响应不可用或过大");
                        long received = 0;
                        try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(file)) {
                            byte[] bytes = new byte[32768]; int n;
                            while ((n = input.read(bytes)) != -1) {
                                if (handle.cancelled || !job.valid()) throw new java.io.IOException("任务已取消");
                                received += n; if (received > AssetLedger.MAX_FILE) throw new java.io.IOException("文件过大");
                                output.write(bytes, 0, n);
                            }
                            output.getFD().sync();
                        }
                        long expected = request.getContentLengthLong();
                        if (expected >= 0 && received != expected) throw new java.io.IOException("文件未完整接收");
                        if (handle.cancelled || !job.publishAllowed()) throw new java.io.IOException("文件归属已失效");
                        break;
                    } finally { request.disconnect(); handle.connection = null; }
                }
            } catch (Exception failure) {
                error = handle.cancelled || !job.valid() ? "下载已取消或账号已改变" : "下载失败；没有自动重试";
                if (owned && file != null && file.exists() && !file.delete()) error += "，本任务暂存需清理";
                file = null;
            } finally { if (lease) session.routes.endTransfer(); }
            final File staged = file; final String problem = error;
            MAIN.post(() -> {
                if (staged != null && !job.publishAllowed()) {
                    if (!staged.delete()) android.util.Log.w("StudioNetwork", "Owned download staging cleanup failed");
                    callback.complete(null, "账号或线路已改变，旧下载未发布");
                } else callback.complete(staged, problem);
            });
        });
        return handle;
    }
    private NetworkDownload() {}
}
