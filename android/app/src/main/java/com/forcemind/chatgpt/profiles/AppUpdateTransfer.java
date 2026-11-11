package com.forcemind.chatgpt.profiles;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.LongConsumer;

/** A single credential-free transfer, bound to the route selected at its start. */
final class AppUpdateTransfer implements AutoCloseable {
    private final NetworkRoute route;
    private final NetworkRoute.Snapshot snapshot;
    private final long started = SystemClock.elapsedRealtime();
    private volatile boolean cancelled;
    private HttpURLConnection connection;
    private final String acceptanceKey;

    AppUpdateTransfer(NetworkRoute route, String acceptanceKey) {
        if (!acceptanceKey.isEmpty() && (!BuildConfig.APP_UPDATE_ACCEPTANCE || !AppUpdateAcceptance.validKey(acceptanceKey)))
            throw new IllegalArgumentException("验收更新源访问码无效");
        this.acceptanceKey = acceptanceKey;
        this.route = route;
        snapshot = route.beginTransfer();
    }

    void cancel() { cancelled = true; }
    boolean cancelled() { return cancelled; }

    void check() throws IOException {
        if (cancelled) throw new IOException("下载已取消");
        if (!snapshot.ready() || route.snapshot() != snapshot) throw new IOException("线路已改变，请重试");
        if (SystemClock.elapsedRealtime() - started >= 30 * 60 * 1000L) throw new IOException("更新任务已超时");
    }

    private HttpURLConnection open(String url) throws Exception {
        check();
        // The app's WebView cookies are per account. Do not let any process-wide
        // Java cookie handler attach credentials to these unrelated requests.
        if (CookieHandler.getDefault() != null) throw new IOException("更新连接配置不安全");
        HttpURLConnection next = snapshot.openConnection(new URL(url));
        next.setConnectTimeout(12000);
        next.setReadTimeout(8000);
        next.setUseCaches(false);
        next.setInstanceFollowRedirects(false);
        next.setRequestProperty("Accept-Encoding", "identity");
        if (BuildConfig.APP_UPDATE_ACCEPTANCE && !acceptanceKey.isEmpty()
                && (AppUpdateAcceptance.FEED.equals(url) || AppUpdateAcceptance.APK.equals(url)))
            next.setRequestProperty(AppUpdateAcceptance.HEADER, acceptanceKey);
        connection = next;
        return next;
    }

    private void closeConnection() {
        HttpURLConnection previous = connection;
        connection = null;
        if (previous != null) previous.disconnect();
    }

    @Override public void close() {
        try { closeConnection(); }
        finally { route.endTransfer(); }
    }

    String feed(boolean fixture) throws Exception {
        String source = BuildConfig.APP_UPDATE_ACCEPTANCE && !acceptanceKey.isEmpty()
                ? AppUpdateAcceptance.FEED : AppUpdateRelease.FEED;
        HttpURLConnection request = open(fixture ? AppUpdateRelease.FIXTURE + "/android.json" : source);
        try {
            request.setRequestProperty("Accept", "application/json");
            if (request.getResponseCode() != 200) throw new IOException("无法读取版本清单");
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    check();
                    if (bytes.size() + count > 65536) throw new IOException("版本清单过大");
                    bytes.write(buffer, 0, count);
                }
                check();
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            closeConnection();
        }
    }

    void download(AppUpdateRelease release, File part, boolean accelerated, boolean fixture, LongConsumer progress)
            throws Exception {
        downloadAsset(release.url, release.size, release.sha256, part, accelerated, fixture || release.privateSource, progress);
    }

    void downloadDelta(AppUpdateRelease.Delta delta, File part, boolean accelerated, boolean fixture,
                       LongConsumer progress) throws Exception {
        downloadAsset(delta.url(), delta.size(), delta.sha256(), part, accelerated, fixture, progress);
    }

    private void downloadAsset(String address, long size, String digest, File part, boolean accelerated,
                               boolean fixture, LongConsumer progress) throws Exception {
        String url = accelerated && !fixture ? "https://gh-proxy.org/" + address : address;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!AppUpdateRelease.allowsAsset(address, url, accelerated, fixture))
                throw new IOException("更新包下载地址不受支持");
            HttpURLConnection request = open(url);
            try {
                request.setRequestProperty("Accept", "application/octet-stream");
                int status = request.getResponseCode();
                check();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = request.getHeaderField("Location");
                    if (location == null || location.length() > 8192) throw new IOException("下载重定向无效");
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                if (status != 200) throw new IOException("下载源暂不可用");
                long length = request.getContentLengthLong();
                if (length >= 0 && length != size) throw new IOException("更新文件大小不符");
                String encoding = request.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("更新文件编码不符");
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long bytes = 0, notified = 0;
                progress.accept(0);
                try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        check();
                        bytes += count;
                        if (bytes > size) throw new IOException("更新文件超出声明大小");
                        sha.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        long now = SystemClock.elapsedRealtime();
                        if (bytes == size || now - notified >= 500) {
                            progress.accept(bytes);
                            notified = now;
                        }
                    }
                    check();
                    if (bytes != size || !digest.equals(hex(sha.digest())))
                        throw new IOException("更新文件完整性校验失败");
                    output.getFD().sync();
                }
                return;
            } finally {
                closeConnection();
            }
        }
        throw new IOException("更新下载重定向过多");
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
