package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native requests always capture the same explicit proxy as WebView; switches wait for release. */
public final class NativeDownloads {
    public interface Result { void complete(File file, String error); }
    /** A request body may have reached the server without a complete success response. */
    public static final class RequestFailure extends java.io.IOException {
        public final boolean outcomeUncertain;
        RequestFailure(Exception cause) {
            super("上传结果不明；未自动重发", cause);
            outcomeUncertain = true;
        }
    }
    public static final class Response {
        public final byte[] bytes; public final String route; public final int status;
        Response(byte[] bytes, String route, int status) { this.bytes = bytes; this.route = route; this.status = status; }
    }
    private final RouteManager routes;
    private final Context context;
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    private volatile boolean closed;
    private final Set<HttpURLConnection> requests = new HashSet<>();
    public NativeDownloads(Context context, RouteManager routes) { this.context = context.getApplicationContext(); this.routes = routes; }
    private static URL checkedUrl(String address) throws Exception {
        URL url = new URL(address);
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) throw new IllegalArgumentException("仅支持 HTTP/HTTPS 文件地址");
        if (url.getUserInfo() != null) throw new IllegalArgumentException("文件地址不允许嵌入凭据");
        return url;
    }
    public Response request(String address, String method, byte[] body) throws Exception {
        final RouteManager.Snapshot route;
        synchronized (this) {
            if (closed) throw new IllegalStateException("下载器已关闭");
            route = routes.beginTransfer();
        }
        HttpURLConnection connection = null;
        boolean uploadAttempted = false;
        try {
            connection = route.openConnection(checkedUrl(address));
            synchronized (this) {
                if (closed) throw new IllegalStateException("下载器已关闭");
                requests.add(connection);
            }
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestMethod(method);
            if (body != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); connection.setRequestProperty("Content-Type", "application/octet-stream"); uploadAttempted = true; try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); } }
            int status = connection.getResponseCode();
            if (status >= 300) throw new java.io.IOException("目标返回 HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) >= 0) { if (bytes.size() + count > 4 * 1024 * 1024) throw new java.io.IOException("检查响应过大"); bytes.write(buffer, 0, count); } }
            return new Response(bytes.toByteArray(), connection.getHeaderField("X-Fixture-Route"), status);
        } catch (Exception failure) {
            // Never infer that a POST was not processed merely because its reply was lost.
            if (uploadAttempted) throw new RequestFailure(failure);
            throw failure;
        } finally {
            try {
                if (connection != null) {
                    synchronized (this) { requests.remove(connection); }
                    connection.disconnect();
                }
            } finally { routes.endTransfer(); }
        }
    }
    public Future<?> download(String address, String userAgent, Result result) {
        final RouteManager.Snapshot route;
        try {
            synchronized (this) { if (closed) throw new IllegalStateException("下载器已关闭"); if (active != null || routes.activeTransfers() != 0) throw new IllegalStateException("已有文件传输进行中"); cancelled = false; route = routes.beginTransfer(); }
        } catch (Exception e) { result.complete(null, e.getMessage()); return null; }
        try { return worker.submit(() -> {
            File target = new File(context.getFilesDir(), "download-" + UUID.randomUUID());
            String error = null;
            try {
                URL url = checkedUrl(address);
                for (int redirects = 0; ; redirects++) {
                    if (cancelled) throw new java.io.IOException("下载已取消");
                    HttpURLConnection nextConnection = route.openConnection(url);
                    synchronized (this) {
                        if (closed || cancelled) { nextConnection.disconnect(); throw new java.io.IOException("下载已取消"); }
                        active = nextConnection;
                    }
                    active.setConnectTimeout(15000); active.setReadTimeout(20000); active.setInstanceFollowRedirects(false); active.setUseCaches(false);
                    if (userAgent != null) active.setRequestProperty("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url.toString());
                    if (cookie != null) active.setRequestProperty("Cookie", cookie);
                    int status = active.getResponseCode();
                    if (status >= 300 && status < 400) {
                        if (redirects == 5) throw new java.io.IOException("重定向过多");
                        URL next = checkedUrl(new URL(url, active.getHeaderField("Location")).toString());
                        if ("https".equals(url.getProtocol()) && "http".equals(next.getProtocol())) throw new java.io.IOException("拒绝不安全的下载重定向");
                        active.disconnect(); url = next; continue;
                    }
                    if (status < 200 || status >= 300) throw new java.io.IOException("目标返回 HTTP " + status);
                    try (InputStream in = active.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = in.read(buffer)) >= 0) { if (cancelled || Thread.currentThread().isInterrupted()) throw new java.io.IOException("下载已取消"); out.write(buffer, 0, count); }
                        out.getFD().sync();
                    }
                    break;
                }
            } catch (Exception e) { error = cancelled ? "下载已取消" : "下载失败；未自动重试"; if (target.exists() && !target.delete()) error += "，临时文件需稍后清理"; }
            finally { if (active != null) active.disconnect(); active = null; routes.endTransfer(); }
            String finalError = error; main.post(() -> result.complete(finalError == null ? target : null, finalError));
        }); } catch (java.util.concurrent.RejectedExecutionException e) {
            routes.endTransfer(); result.complete(null, "下载已停止"); return null;
        }
    }
    private void disconnectConnections(Set<HttpURLConnection> connections) {
        Runnable release = () -> {
            for (HttpURLConnection connection : connections) {
                try { connection.disconnect(); }
                catch (RuntimeException failure) {
                    android.util.Log.w("NetworkPreview", "Connection cleanup failed: " + failure.getClass().getSimpleName());
                }
            }
        };
        if (connections.isEmpty()) return;
        // A blocked response must not hold up Activity teardown or the cancel UI.
        if (Looper.myLooper() == main.getLooper()) new Thread(release, "network-file-close").start();
        else release.run();
    }
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) disconnectConnections(java.util.Collections.singleton(connection));
    }
    public void close() {
        final Set<HttpURLConnection> connections;
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancelled = true;
            connections = new HashSet<>(requests);
            if (active != null) connections.add(active);
        }
        disconnectConnections(connections);
        worker.shutdownNow();
    }
}
