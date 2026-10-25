package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-selected document destinations; cookies always come from the initiating profile. */
final class DownloadHelper {
    static final int REQUEST_SAVE = 11;
    private final Context context;
    private WeakReference<Activity> owner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Selection pending;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private static final class Selection {
        final String url, agent;
        final CookieManager cookies;
        Selection(String url, String agent, CookieManager cookies) { this.url = url; this.agent = agent; this.cookies = cookies; }
    }
    DownloadHelper(Activity activity) { context=activity.getApplicationContext();owner=new WeakReference<>(activity); }
    void attach(Activity activity){owner=new WeakReference<>(activity);}
    void choose(WebView web, String url, String agent, String disposition, String mime) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { notifyUser("WebView 独立空间能力不可用"); return; }
        if (!NavigationPolicy.https(url)) { notifyUser("当前仅支持 HTTPS 文件下载；blob 文件请在系统浏览器保存。"); return; }
        if (pending != null) { notifyUser("请先完成当前文件选择"); return; }
        pending = new Selection(url, agent, WebViewCompat.getProfile(web).getCookieManager());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime)
                .putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, disposition, mime));
        try { Activity activity=owner.get();if(activity==null||activity.isFinishing())throw new IllegalStateException("窗口不可用");activity.startActivityForResult(intent, REQUEST_SAVE); }
        catch (RuntimeException e) { pending = null; notifyUser("无法打开文件保存器"); }
    }
    void cancelSelection() { pending = null; }
    void onResult(int result, Intent data) {
        Selection selected = pending; pending = null;
        if (selected == null || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData(); if (!"content".equals(destination.getScheme())) return;
        notifyUser("正在下载文件…");
        executor.execute(() -> {
            boolean success = false;
            try {
                String target = selected.url;
                for (int redirects = 0; redirects <= 5; redirects++) {
                    if (closed || Thread.currentThread().isInterrupted() || !NavigationPolicy.https(target)) throw new IllegalStateException();
                    HttpURLConnection request = (HttpURLConnection) new URL(target).openConnection(); connection = request;
                    try {
                        request.setConnectTimeout(20000); request.setReadTimeout(30000); request.setInstanceFollowRedirects(false);
                        request.setRequestProperty("User-Agent", selected.agent);
                        // Recompute for each redirect; never forward another origin's Cookie header.
                        String cookie = selected.cookies.getCookie(target); if (cookie != null) request.setRequestProperty("Cookie", cookie);
                        int status = request.getResponseCode();
                        if (status >= 300 && status < 400) { String location = request.getHeaderField("Location"); if (location == null) throw new IllegalStateException(); target = new URL(new URL(target), location).toString(); continue; }
                        if (status != 200) throw new IllegalStateException();
                        try (InputStream input = request.getInputStream(); OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                            if (output == null) throw new IllegalStateException();
                            byte[] buffer = new byte[32768]; int n;
                            while ((n = input.read(buffer)) != -1) { if (closed || Thread.currentThread().isInterrupted()) throw new IllegalStateException(); output.write(buffer, 0, n); }
                        }
                        success = true; break;
                    } finally { request.disconnect(); connection = null; }
                }
            } catch (Exception ignored) { /* Surface a bounded error, never log URLs or login cookies. */ }
            if (!success) try { DocumentsContract.deleteDocument(context.getContentResolver(), destination); } catch (Exception ignored) { }
            notifyUser(success ? "文件已保存" : "下载失败或已取消，请重试");
        });
    }
    void close() { closed = true; executor.shutdownNow(); HttpURLConnection active = connection; if (active != null) active.disconnect(); }
    private void notifyUser(String message) { new Handler(Looper.getMainLooper()).post(() -> { if (!closed) Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }); }
}
