package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned network intent and file ownership. Never mutates workspace/profile/recovery records. */
final class NetworkSession {
    final Context context;
    final NetworkRoute routes = new NetworkRoute();
    private final NetworkSettingsStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService settingsIO = Executors.newSingleThreadExecutor();
    private final Map<String, FileJob> jobs = new LinkedHashMap<>();
    private final java.util.Set<String> externalJobs = new java.util.HashSet<>();
    private final Map<String, Long> epochs = new LinkedHashMap<>();
    private final Runnable observer;
    private final java.util.Set<Runnable> panelObservers = new java.util.LinkedHashSet<>();
    private NetworkSettingsStore.Data settings = new NetworkSettingsStore.Data();
    private volatile boolean changing = true, saving, closed, unreadable;
    private boolean booted;
    private volatile boolean fileStagingReady = true;
    NetworkSession(Context context, Runnable observer) {
        this.context = context.getApplicationContext(); this.observer = observer;
        store = new NetworkSettingsStore(this.context);
        routes.setStateListener(snapshot -> notifyChanged());
        settingsIO.execute(() -> {
            try {
                try { NetworkDownload.clearPreviousProcessParts(this.context); }
                catch (Exception cleanup) { fileStagingReady = false; }
                NetworkSettingsStore.Data value = store.read();
                main.post(() -> {
                    if (closed) return;
                    settings = value; booted = true;
                    if (value.enabled && !capable()) {
                        changing = false; routes.block("当前网页组件不支持内置网络，请更新 Android System WebView 或选择使用系统网络");
                    } else apply(value, null);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (closed) return;
                    changing = false; booted = true; unreadable = true;
                    routes.block("线路配置暂不可读，原配置已保留。可重开应用，或本次使用系统网络");
                });
            }
        });
    }
    static boolean capable() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && NetworkRoute.supportsWebViewProxy(); }
        catch (RuntimeException unavailable) { return false; }
    }
    boolean ready() { return !closed && !changing && routes.snapshot().ready(); }
    boolean changing() { return changing; }
    boolean unreadable() { return unreadable; }
    List<NetworkNode> nodes() { return new ArrayList<>(settings.nodes); }
    int selected() { return settings.selected; }
    boolean enabled() { return settings.enabled; }
    boolean controlsBusy() { return !booted || changing || saving || busy() || routes.activeTransfers() != 0 || HandoffRuntime.networkBusy(); }
    boolean closed() { return closed; }
    boolean connectedTo(int index) {
        NetworkRoute.Snapshot actual = routes.snapshot();
        return settings.enabled && index == settings.selected && index >= 0 && index < settings.nodes.size()
                && actual.state == NetworkRoute.State.CONNECTED && actual.node == settings.nodes.get(index);
    }
    void observePanel(Runnable listener) {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Network panel observers require the UI thread");
        if (closed) return;
        panelObservers.add(listener); listener.run();
    }
    void removePanelObserver(Runnable listener) {
        if (Looper.myLooper() != main.getLooper()) { main.post(() -> removePanelObserver(listener)); return; }
        panelObservers.remove(listener);
    }
    String status() {
        if (closed) return "网络已关闭";
        if (!booted) return "正在准备网络";
        if (saving) return "正在保存线路";
        if (changing || routes.snapshot().state == NetworkRoute.State.CONNECTING) return "正在连接";
        switch (routes.snapshot().state) {
            case SYSTEM: return "使用系统网络";
            case CONNECTED: return "已连接 · " + (routes.snapshot().node == null ? "所选线路" : routes.snapshot().node.name);
            case FAILED: return "连接失败";
            default: return "网络已关闭";
        }
    }
    synchronized boolean busy() { return !jobs.isEmpty() || !externalJobs.isEmpty(); }
    NetworkRoute.Snapshot beginEnvironmentFile(String id) {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("File ownership requires UI thread");
        synchronized (this) {
            if (!ready() || jobs.size()+externalJobs.size()>=32 || !externalJobs.add(id)) throw new IllegalStateException("文件任务暂不可用");
        }
        notifyPanelChanged(); return routes.snapshot();
    }
    void finishEnvironmentFile(String id) {
        synchronized (this) { externalJobs.remove(id); }
        notifyPanelChanged();
    }
    private void notifyChanged() {
        if (Looper.myLooper() != main.getLooper()) { main.post(this::notifyChanged); return; }
        if (!closed && observer != null) observer.run();
        notifyPanelChanged();
    }
    private void notifyPanelChanged() {
        if (Looper.myLooper() != main.getLooper()) { main.post(this::notifyPanelChanged); return; }
        for (Runnable listener : new ArrayList<>(panelObservers)) if (panelObservers.contains(listener)) listener.run();
        if (closed) panelObservers.clear();
    }
    private void apply(NetworkSettingsStore.Data value, NetworkRoute.Listener result) {
        NetworkRoute.Listener done = (success, message) -> {
            changing = false; notifyChanged();
            if (result != null) result.complete(success, success ? status() : message);
        };
        if (value.enabled) routes.switchTo(value.nodes.get(value.selected), done);
        else routes.useSystem(done);
    }
    private boolean canChange(NetworkRoute.Listener listener) {
        if (closed || !booted || changing || saving) { listener.complete(false, "网络正在准备，请稍候"); return false; }
        if (busy() || routes.activeTransfers() != 0 || HandoffRuntime.networkBusy()) { listener.complete(false, "文件任务进行中，请完成或取消后再更改网络"); return false; }
        return true;
    }
    private void save(NetworkSettingsStore.Data value, boolean reconnect, NetworkRoute.Listener listener) {
        if (!canChange(listener)) return;
        if (unreadable) { listener.complete(false, "原线路配置已保留，请先恢复配置；本次可使用系统网络"); return; }
        saving = true; if (reconnect) changing = true; notifyChanged();
        settingsIO.execute(() -> {
            try {
                store.write(value);
                main.post(() -> {
                    if (closed) return;
                    settings = value; saving = false;
                    if (reconnect) apply(value, listener);
                    else { changing = false; notifyChanged(); listener.complete(true, "线路已保存"); }
                });
            } catch (Exception failure) {
                main.post(() -> { if (!closed) { changing = false; saving = false; notifyChanged(); listener.complete(false, "线路配置保存失败，原配置和当前网络保持不变"); } });
            }
        });
    }
    void saveNode(int index, NetworkNode node, NetworkRoute.Listener listener) {
        NetworkSettingsStore.Data value = settings.copy();
        if (index < 0) { if (value.nodes.size() >= 64) { listener.complete(false, "最多保存 64 条线路"); return; } value.nodes.add(node); }
        else if (index < value.nodes.size()) value.nodes.set(index, node);
        else { listener.complete(false, "线路已改变，请重新打开"); return; }
        save(value, value.enabled && value.selected == index, listener);
    }
    void removeNode(int index, NetworkRoute.Listener listener) {
        if (index < 0 || index >= settings.nodes.size()) { listener.complete(false, "线路不存在"); return; }
        if (settings.enabled && settings.selected == index) { listener.complete(false, "请先停用或切换当前线路"); return; }
        NetworkSettingsStore.Data value = settings.copy(); value.nodes.remove(index);
        if (value.selected == index) value.selected = -1; else if (value.selected > index) value.selected--;
        save(value, false, listener);
    }
    void select(int index, NetworkRoute.Listener listener) {
        if (!capable()) { listener.complete(false, "请更新 Android System WebView 后使用内置网络"); return; }
        if (index < 0 || index >= settings.nodes.size()) { listener.complete(false, "请先添加线路"); return; }
        NetworkSettingsStore.Data value = settings.copy(); value.selected = index; value.enabled = true;
        save(value, true, listener);
    }
    void useSystem(NetworkRoute.Listener listener) {
        if (!canChange(listener)) return;
        if (unreadable) {
            changing = true; notifyChanged();
            routes.useSystem((success, message) -> { changing = false; notifyChanged(); listener.complete(success, "本次使用系统网络；原线路配置已保留，下次启动仍会提示"); });
            return;
        }
        NetworkSettingsStore.Data value = settings.copy(); value.enabled = false; save(value, true, listener);
    }
    void retry(NetworkRoute.Listener listener) {
        if (!canChange(listener)) return;
        if (unreadable) { listener.complete(false, routes.error()); return; }
        changing = true; notifyChanged(); apply(settings, listener);
    }
    FileJob begin(WebView web, String accountId) throws Exception {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("File ownership must be captured on UI thread");
        if (!fileStagingReady) throw new IllegalStateException("下载暂存暂不可用，未改动账号或恢复数据");
        if (!ready()) throw new IllegalStateException("网络尚未就绪，请先选择线路或使用系统网络");
        WorkspaceState.Account account = WorkspaceFiles.read(context.getFilesDir()).find(accountId);
        if (!WorkspaceState.profile(account.storageId).equals(WebViewCompat.getProfile(web).getName())) throw new IllegalStateException("文件账号已改变，请重新发起");
        FileJob job;
        synchronized (this) {
            if (jobs.size() >= 32) throw new IllegalStateException("文件任务数量已满");
            job = new FileJob(accountId, account.storageId, epochs.getOrDefault(accountId, 0L), WebViewCompat.getProfile(web).getCookieManager(), routes.snapshot());
            jobs.put(job.id, job);
        }
        notifyPanelChanged(); return job;
    }
    void invalidateAccount(String accountId) {
        List<FileJob> cancelled = new ArrayList<>();
        synchronized (this) { epochs.put(accountId, epochs.getOrDefault(accountId, 0L) + 1); for (FileJob job : jobs.values()) if (job.account.equals(accountId)) cancelled.add(job); }
        for (FileJob job : cancelled) job.cancel();
    }
    void close() {
        List<FileJob> cancelled;
        synchronized (this) { if (closed) return; closed = true; cancelled = new ArrayList<>(jobs.values()); }
        for (FileJob job : cancelled) job.cancel();
        routes.close(); settingsIO.shutdown();
        notifyPanelChanged();
    }
    final class FileJob {
        final String id = UUID.randomUUID().toString();
        final String account, storageId;
        final long accountEpoch;
        final CookieManager cookies;
        final NetworkRoute.Snapshot route;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile boolean cancelled;
        volatile NetworkDownload.Handle download;
        volatile Runnable cancellation;
        FileJob(String account, String storageId, long epoch, CookieManager cookies, NetworkRoute.Snapshot route) {
            this.account = account; this.storageId = storageId; accountEpoch = epoch; this.cookies = cookies; this.route = route;
        }
        boolean valid() {
            synchronized (NetworkSession.this) { return !closed && !cancelled && epochs.getOrDefault(account, 0L) == accountEpoch && ready() && routes.snapshot() == route; }
        }
        boolean publishAllowed() {
            if (!valid()) return false;
            try { return storageId.equals(WorkspaceFiles.read(context.getFilesDir()).find(account).storageId); }
            catch (Exception unavailable) { return false; }
        }
        void cancel() { cancelled = true; NetworkDownload.Handle current = download; if (current != null) current.cancel(); Runnable action = cancellation; if (action != null) main.post(action); }
        void finish() { if (finished.compareAndSet(false, true)) { synchronized (NetworkSession.this) { jobs.remove(id); } notifyPanelChanged(); } }
    }
}
