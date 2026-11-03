package com.forcemind.chatgpt.networkpreview;

import android.os.Handler;
import android.os.Looper;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import studio.networkcore.Networkcore;

/** Serial core ownership. No fallback, navigation, reload, or request replay on route changes. */
public final class RouteManager {
    public interface Listener { void complete(boolean success, String message); }
    public interface StateListener { void changed(Snapshot snapshot); }
    public enum State { DIRECT, SYSTEM, CONNECTING, CONNECTED, FAILED, CLOSED }
    public static final class Snapshot {
        public final State state; public final VlessNode node; public final int port; public final long generation;
        Snapshot(State state, VlessNode node, int port, long generation) { this.state = state; this.node = node; this.port = port; this.generation = generation; }
        public boolean ready() { return state == State.DIRECT || state == State.SYSTEM || state == State.CONNECTED; }
        public Proxy proxy() {
            if (state != State.DIRECT && state != State.CONNECTED) throw new IllegalStateException("线路尚未就绪");
            return node == null ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
        }
        public java.net.HttpURLConnection openConnection(java.net.URL url) throws java.io.IOException {
            if (!ready()) throw new IllegalStateException("线路尚未就绪");
            if (state == State.SYSTEM) return (java.net.HttpURLConnection) url.openConnection();
            Proxy selected = proxy();
            // Legacy WebView follows Android's system proxy; native files must do the same.
            return (java.net.HttpURLConnection) (state == State.DIRECT && !supportsWebViewProxy()
                    ? url.openConnection() : url.openConnection(selected));
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    // Process-wide serialization also orders old-Activity shutdown before a new Activity starts the core.
    private static final ExecutorService CORE_WORKER = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = CORE_WORKER;
    private volatile Snapshot current = new Snapshot(State.CONNECTING, null, 0, 0);
    private volatile String error = "";
    private int transfers;
    private volatile boolean closed;
    // Monitor bookkeeping and observers belong to the main Looper. Native reads
    // run on CORE_WORKER, so Start/Stop's Go mutex never blocks the UI thread.
    private static final long CORE_CHECK_INTERVAL_MS = 2000;
    private StateListener stateListener;
    private HealthSession healthSession;
    private final class HealthSession {
        final Snapshot expected;
        final Runnable check;
        boolean inFlight;
        HealthSession(Snapshot expected) {
            this.expected = expected;
            check = () -> checkCoreState(this);
        }
    }
    public static boolean supportsWebViewProxy() { return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE); }
    public Snapshot snapshot() { return current; }
    public String error() { return error; }
    public void setStateListener(StateListener listener) {
        if (Looper.myLooper() != main.getLooper()) { main.post(() -> setStateListener(listener)); return; }
        stateListener = closed ? null : listener;
        if (stateListener != null) stateListener.changed(current);
    }
    private void cancelCoreMonitor() {
        if (healthSession != null) main.removeCallbacks(healthSession.check);
        healthSession = null;
    }
    private void stateChanged(Snapshot snapshot) {
        if (Looper.myLooper() != main.getLooper()) { main.post(() -> stateChanged(snapshot)); return; }
        if (current != snapshot) return;
        cancelCoreMonitor();
        if (!closed && snapshot.state == State.CONNECTED) {
            healthSession = new HealthSession(snapshot);
            main.postDelayed(healthSession.check, CORE_CHECK_INTERVAL_MS);
        }
        if (closed) stateListener = null;
        else if (stateListener != null) stateListener.changed(snapshot);
    }
    private boolean isCurrentSession(HealthSession session) {
        // Snapshot identity includes state, generation, selected node and port;
        // a result from any previous route/session cannot modify or reschedule it.
        return !closed && healthSession == session && current == session.expected
                && current.state == State.CONNECTED;
    }
    /** Coalesces with any pending/in-flight local check, including on Activity resume. */
    public void recheckCoreState() {
        if (Looper.myLooper() != main.getLooper()) { main.post(this::recheckCoreState); return; }
        HealthSession session = healthSession;
        if (session == null || !isCurrentSession(session)) return;
        main.removeCallbacks(session.check);
        session.check.run();
    }
    private void checkCoreState(HealthSession session) {
        if (!isCurrentSession(session) || session.inFlight) return;
        session.inFlight = true;
        worker.execute(() -> {
            boolean running;
            try { running = Networkcore.isRunning(); }
            catch (Exception e) { running = false; }
            final boolean alive = running;
            main.post(() -> {
                session.inFlight = false;
                if (!isCurrentSession(session)) return;
                if (!alive) {
                    Snapshot previous = session.expected;
                    error = "本地代理核心已停止或状态不可用；未切换直连，请手动重新连接线路";
                    current = new Snapshot(State.FAILED, previous.node, previous.port, previous.generation);
                    stateChanged(current);
                } else main.postDelayed(session.check, CORE_CHECK_INTERVAL_MS);
            });
        });
    }
    public synchronized int activeTransfers() { return transfers; }
    public synchronized Snapshot beginTransfer() {
        Snapshot s = current;
        if (!s.ready()) throw new IllegalStateException("线路尚未就绪");
        if (closed) throw new IllegalStateException("线路已关闭");
        transfers++; return s;
    }
    public synchronized void endTransfer() { if (transfers > 0) transfers--; }
    public void useDirect(Listener listener) { switchTo(null, listener); }
    public void useSystem(Listener listener) { switchTo(null, true, listener); }
    public void switchTo(VlessNode node, Listener listener) {
        switchTo(node, false, listener);
    }
    private void switchTo(VlessNode node, boolean system, Listener listener) {
        final Snapshot pending;
        synchronized (this) {
            if (closed || current.state == State.CLOSED) { listener.complete(false, "线路已关闭"); return; }
            if (transfers != 0) { listener.complete(false, "文件传输进行中，请完成或取消后切换"); return; }
            if (current.state == State.CONNECTING && current.generation != 0) { listener.complete(false, "正在切换线路"); return; }
            if (node != null && !supportsWebViewProxy()) { listener.complete(false, "当前 WebView 不支持应用内代理，请更新 Android System WebView"); return; }
            current = new Snapshot(State.CONNECTING, node, 0, current.generation + 1); error = "";
            pending = current;
        }
        stateChanged(pending);
        final long generation = pending.generation;
        worker.execute(() -> {
            if (closed || current != pending) return;
            int port = 0;
            String stage = "stop-previous-core";
            try {
                Networkcore.stop();
                if (node != null) {
                    stage = "start-core";
                    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
                    Networkcore.start(CoreConfig.build(node, port));
                    stage = "check-local-listener";
                    try (Socket check = new Socket()) { check.connect(new InetSocketAddress("127.0.0.1", port), 3000); }
                    java.net.HttpURLConnection probe = null;
                    stage = "probe-owned-target";
                    try {
                        java.net.Proxy explicitProxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
                        probe = (java.net.HttpURLConnection) new java.net.URL(BuildConfig.NETWORK_PROBE_URL).openConnection(explicitProxy);
                        probe.setConnectTimeout(8000); probe.setReadTimeout(8000); probe.setInstanceFollowRedirects(false); probe.setUseCaches(false);
                        int code = probe.getResponseCode();
                        if (code < 100 || code >= 500) throw new java.io.IOException("检查目标未返回有效响应");
                    } finally { if (probe != null) probe.disconnect(); }
                }
                final int appliedPort = port;
                main.post(() -> applyWebView(node, appliedPort, generation, system, listener));
            } catch (Exception e) {
                if (BuildConfig.APPLICATION_ID.endsWith(".ci"))
                    android.util.Log.e("NetworkPreviewCI", "startup stage=" + stage + " exception=" + e.getClass().getName());
                try { Networkcore.stop(); } catch (Exception ignored) { }
                main.post(() -> fail(generation, listener, "线路连接或目标检查失败"));
            }
        });
    }
    private void applyWebView(VlessNode node, int port, long generation, boolean system, Listener listener) {
        if (closed || current.generation != generation || current.state != State.CONNECTING) return;
        Runnable timeout = () -> {
            if (closed || current.generation != generation || current.state != State.CONNECTING) return;
            worker.execute(() -> { try { Networkcore.stop(); } catch (Exception ignored) {} });
            fail(generation, listener, "网页线路应用超时");
        };
        Runnable done = () -> {
            main.removeCallbacks(timeout);
            if (closed || current.generation != generation || current.state != State.CONNECTING) return;
            current = new Snapshot(node == null ? (system ? State.SYSTEM : State.DIRECT) : State.CONNECTED, node, port, generation);
            stateChanged(current);
            listener.complete(true, node == null ? (system ? "使用系统网络" : "直连") : "已连接；仅已验证当前检查目标");
        };
        main.postDelayed(timeout, 12000);
        try {
            if (!supportsWebViewProxy()) { if (node == null) done.run(); else fail(generation, listener, "WebView 不支持代理覆盖"); }
            else if (system) ProxyController.getInstance().clearProxyOverride(main::post, done);
            else if (node == null) ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder().addDirect().build(), main::post, done);
            else ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + port).removeImplicitRules().build(), main::post, done);
        } catch (Exception e) { main.removeCallbacks(timeout); worker.execute(() -> { try { Networkcore.stop(); } catch (Exception ignored) {} }); fail(generation, listener, "WebView 代理应用失败"); }
    }
    private void fail(long generation, Listener listener, String message) {
        if (closed || current.generation != generation || current.state != State.CONNECTING) return;
        error = message; current = new Snapshot(State.FAILED, current.node, current.port, generation);
        stateChanged(current);
        listener.complete(false, message + "；没有改为直连");
    }
    public synchronized void close() {
        if (closed) return;
        closed = true; current = new Snapshot(State.CLOSED, current.node, current.port, current.generation + 1);
        stateChanged(current);
        worker.execute(() -> { try { Networkcore.stop(); } catch (Exception ignored) {} });
    }
}
