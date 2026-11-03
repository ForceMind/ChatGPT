package com.forcemind.chatgpt.networkpreview;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Joint-proof adapters only. Cookies and results retain their initiating account. */
public final class ProfileFileTasks implements AutoCloseable {
    public enum State { COMPLETED, FAILED, CANCELLED }
    public interface Callback { void complete(Result result); }
    public static final class Result {
        public final String taskId, accountId, storageId, error;
        public final long accountEpoch, routeGeneration;
        public final State state;
        public final File file;
        public final Uri document;
        Result(Task task, String account, String storage, long epoch, State state, File file, Uri document, String error) {
            this.taskId = task.id; this.accountId = account; this.accountEpoch = epoch;
            this.storageId = storage;
            this.routeGeneration = task.routeGeneration; this.state = state;
            this.file = file; this.document = document; this.error = error;
        }
    }
    private static final class Task {
        final String id; final long routeGeneration; final Callback callback;
        final AtomicBoolean delivered = new AtomicBoolean();
        volatile boolean cancelled;
        Task(String id, long generation, Callback callback) { this.id = id; routeGeneration = generation; this.callback = callback; }
    }
    private final Context context;
    private final RouteManager routes;
    private final String accountId;
    private final String storageId;
    private final long accountEpoch;
    private final BooleanSupplier available;
    private final NativeDownloads client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Result> results = new LinkedHashMap<>();
    private final File directory;
    private volatile boolean closed, clearing;
    private volatile boolean clearComplete;
    private volatile String clearError = "";
    private Task networkTask;
    public ProfileFileTasks(Context context, RouteManager routes, String accountId, long accountEpoch,
                            CookieManager cookies, BooleanSupplier available) {
        this(context, routes, accountId, "owner-" + java.util.UUID.randomUUID(), accountEpoch, cookies, available);
    }
    public ProfileFileTasks(Context context, RouteManager routes, String accountId, String storageId,
                            long accountEpoch, CookieManager cookies, BooleanSupplier available) {
        if (!accountId.matches("[A-Za-z0-9_-]{1,64}") || !storageId.matches("[A-Za-z0-9_-]{1,160}")
                || accountEpoch < 0 || cookies == null || available == null)
            throw new IllegalArgumentException("Invalid joint account context");
        this.context = context.getApplicationContext(); this.routes = routes;
        this.accountId = accountId; this.accountEpoch = accountEpoch; this.available = available;
        this.storageId = storageId;
        this.client = new NativeDownloads(context, routes, cookies, () -> !closed && available.getAsBoolean());
        directory = new File(new File(this.context.getFilesDir(), "joint-assets"), storageId + "-" + accountEpoch);
    }
    private boolean usable(Task task) { return !closed && !task.cancelled && available.getAsBoolean(); }
    public void startAsset(String taskId, String url, String userAgent, Callback callback) {
        start(taskId, url, userAgent, null, callback);
    }
    /** The input is a parent document, never an existing destination to truncate or delete. */
    public void saveDocument(String taskId, String url, String userAgent, Uri parentDocument, Callback callback) {
        if (parentDocument == null || !"content".equals(parentDocument.getScheme())) throw new IllegalArgumentException("Content parent required");
        start(taskId, url, userAgent, parentDocument, callback);
    }
    private void start(String id, String url, String agent, Uri parent, Callback callback) {
        if (Looper.myLooper() != main.getLooper()) { main.post(() -> start(id, url, agent, parent, callback)); return; }
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || callback == null) throw new IllegalArgumentException("Invalid joint task");
        Task task = new Task(id, routes.snapshot().generation, callback);
        synchronized (this) {
            if (closed || !available.getAsBoolean() || tasks.containsKey(id) || tasks.size() >= 256 || networkTask != null) {
                deliver(task, State.FAILED, null, null, "账号任务无效、重复或正在传输"); return;
            }
            tasks.put(id, task); networkTask = task;
        }
        client.download(url, agent, (temporary, error) -> {
            synchronized (this) { if (networkTask == task) networkTask = null; }
            if (temporary == null || error != null || !usable(task)) {
                if (temporary != null && temporary.exists() && !temporary.delete()) error = "仅本任务暂存清理失败";
                deliver(task, usable(task) ? State.FAILED : State.CANCELLED, null, null, error == null ? "账号任务已取消" : error);
                return;
            }
            try { files.execute(() -> publish(task, temporary, parent)); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                boolean removed = !temporary.exists() || temporary.delete();
                deliver(task, State.CANCELLED, null, null, removed ? "文件任务已关闭" : "本任务暂存需清理");
            }
        });
    }
    private void publish(Task task, File temporary, Uri parent) {
        File asset = null; Uri created = null; String error = null; State state = State.FAILED;
        try {
            if (!usable(task)) throw new java.io.IOException("账号任务已失效");
            if (parent == null) {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create owned asset directory");
                File destination = new File(directory, task.id + ".asset");
                if (destination.exists() || !temporary.renameTo(destination)) throw new java.io.IOException("Cannot publish new owned asset");
                asset = destination;
            } else {
                created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                        "application/octet-stream", accountId + "-" + task.id + ".bin");
                if (created == null) throw new java.io.IOException("Cannot create new document");
                try (FileInputStream input = new FileInputStream(temporary);
                     OutputStream output = context.getContentResolver().openOutputStream(created, "w")) {
                    if (output == null) throw new java.io.IOException("Cannot open newly created document");
                    byte[] bytes = new byte[32768]; int count;
                    while ((count = input.read(bytes)) >= 0) {
                        if (!usable(task)) throw new java.io.IOException("账号任务已取消");
                        output.write(bytes, 0, count);
                    }
                    output.flush();
                }
            }
            if (!usable(task)) throw new java.io.IOException("账号任务已失效");
            state = State.COMPLETED;
        } catch (Exception failure) {
            state = usable(task) ? State.FAILED : State.CANCELLED;
            error = "文件任务未发布；" + failure.getClass().getSimpleName();
            if (asset != null && asset.exists() && !asset.delete()) error += "；本任务资产暂存需清理";
            if (created != null) try { DocumentsContract.deleteDocument(context.getContentResolver(), created); }
            catch (Exception cleanup) { error += "；本次新建文档需清理"; }
            asset = null; created = null;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                if (state != State.COMPLETED) error = "本任务暂存需清理";
            }
        }
        deliver(task, state, asset, created, error);
    }
    private void deliver(Task task, State state, File file, Uri document, String error) {
        if (!task.delivered.compareAndSet(false, true)) return;
        main.post(() -> {
            // A completion may have queued before an account clear. Recheck at
            // delivery so it cannot appear as success in a replacement session.
            boolean current = !closed && available.getAsBoolean();
            State visibleState = current ? state : State.CANCELLED;
            Result result = new Result(task, accountId, storageId, accountEpoch, visibleState,
                    current ? file : null, current ? document : null,
                    current ? error : "账号任务已失效；旧完成回调未发布成功");
            synchronized (this) { if (tasks.get(task.id) == task) results.put(task.id, result); }
            task.callback.complete(result);
        });
    }
    public synchronized List<Result> history() { return new ArrayList<>(results.values()); }
    public void cancel(String taskId) {
        boolean cancelNetwork;
        synchronized (this) {
            Task task = tasks.get(taskId);
            if (task == null || task.delivered.get()) return;
            task.cancelled = true; cancelNetwork = networkTask == task;
        }
        if (cancelNetwork) client.cancel();
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (Task task : tasks.values()) if (!task.delivered.get()) task.cancelled = true;
        }
        client.close(); files.shutdown();
    }
    /** Exported documents remain user copies; only this account/epoch's private files are removed. */
    public void clearAccount() {
        synchronized (this) { if (clearing) return; clearing = true; }
        close();
        new Thread(() -> {
            try {
                long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                boolean pending;
                do {
                    synchronized (this) { pending = tasks.values().stream().anyMatch(task -> !task.delivered.get()); }
                    if (!pending) break;
                    Thread.sleep(25);
                } while (android.os.SystemClock.elapsedRealtime() < deadline);
                if (pending) throw new java.io.IOException("Account network tasks still active");
                long remaining = Math.max(0, deadline - android.os.SystemClock.elapsedRealtime());
                if (!files.awaitTermination(remaining, java.util.concurrent.TimeUnit.MILLISECONDS))
                    throw new java.io.IOException("File publisher still active");
                File[] owned = directory.listFiles();
                if (owned != null) for (File file : owned) {
                    if (!file.isFile() || !file.getName().matches("[A-Za-z0-9_-]{1,80}\\.asset") || !file.delete())
                        throw new java.io.IOException("Cannot clear owned account asset");
                }
                if (directory.exists() && !directory.delete()) throw new java.io.IOException("Cannot clear owned asset directory");
                clearComplete = true;
            } catch (Exception failure) { clearError = failure.getClass().getSimpleName(); }
        }, "joint-account-clear").start();
    }
    public boolean clearComplete() { return clearComplete; }
    public String clearError() { return clearError; }
}
