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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native requests always capture the same explicit proxy as WebView; switches wait for release. */
public final class NativeDownloads {
    public interface Result { void complete(File file, String error); }
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
    public NativeDownloads(Context context, RouteManager routes) { this.context = context.getApplicationContext(); this.routes = routes; }
    private static URL checkedUrl(String address) throws Exception {
        URL url = new URL(address);
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) throw new IllegalArgumentException("仅支持 HTTP/HTTPS 文件地址");
        if (url.getUserInfo() != null) throw new IllegalArgumentException("文件地址不允许嵌入凭据");
        return url;
    }
    public Response request(String address, String method, byte[] body) throws Exception {
        RouteManager.Snapshot route = routes.beginTransfer();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) checkedUrl(address).openConnection(route.proxy());
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestMethod(method);
            if (body != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); connection.setRequestProperty("Content-Type", "application/octet-stream"); try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); } }
            int status = connection.getResponseCode();
            if (status >= 300) throw new java.io.IOException("目标返回 HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) >= 0) { if (bytes.size() + count > 4 * 1024 * 1024) throw new java.io.IOException("检查响应过大"); bytes.write(buffer, 0, count); } }
            return new Response(bytes.toByteArray(), connection.getHeaderField("X-Fixture-Route"), status);
        } finally { if (connection != null) connection.disconnect(); routes.endTransfer(); }
    }
    public Future<?> download(String address, String userAgent, Result result) {
        final RouteManager.Snapshot route;
        try {
            synchronized (this) { if (active != null || routes.activeTransfers() != 0) throw new IllegalStateException("已有文件传输进行中"); cancelled = false; route = routes.beginTransfer(); }
        } catch (Exception e) { result.complete(null, e.getMessage()); return null; }
        return worker.submit(() -> {
            File target = new File(context.getFilesDir(), "download-" + UUID.randomUUID());
            String error = null;
            try {
                URL url = checkedUrl(address);
                for (int redirects = 0; ; redirects++) {
                    if (cancelled) throw new java.io.IOException("下载已取消");
                    active = (HttpURLConnection) url.openConnection(route.proxy());
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
        });
    }
    public void cancel() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    public void close() { cancel(); worker.shutdownNow(); }
}
