package com.forcemind.chatgpt.profiles;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Main-process state shared by both browser engines. APKs stay outside account storage. */
final class AppUpdateTask {
    enum Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, CANCELLING, VERIFYING, READY, INSTALLING, CONFIRM, ERROR }

    private static AppUpdateTask instance;
    static AppUpdateTask get(Context context) {
        if (instance == null) instance = new AppUpdateTask(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences saved;
    private final File directory, partial, apk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Runnable> observers = new ArrayList<>();
    private AppUpdateTransfer transfer;
    private boolean automaticCheck;
    private AppUpdateRelease offer, ready;
    private Intent confirmation;
    private int installSession = -1;
    private boolean installPreparing;
    private Phase phase = Phase.IDLE;
    private String message = "可以手动检查更新";
    private long received;

    private AppUpdateTask(Context context) {
        this.context = context;
        saved = context.getSharedPreferences("app-update-v1", Context.MODE_PRIVATE);
        File files = context.getFilesDir();
        try { files = files.getCanonicalFile(); }
        catch (IOException unavailable) { /* The worker fails closed if the directory cannot be verified. */ }
        directory = new File(files, "app-update").getAbsoluteFile();
        partial = new File(directory, "download.part.apk");
        apk = new File(directory, "ready.apk");
        try {
            long installed = AppUpdatePackage.installed(context).getLongVersionCode();
            String cached = saved.getString("ready", null);
            if (cached != null) {
                AppUpdateRelease release = AppUpdateRelease.parse(cached, BuildConfig.APP_UPDATE_TEST);
                if (release.code > installed && apk.isFile() && apk.length() == release.size) {
                    offer = ready = release;
                    installSession = saved.getInt("installSession", -1);
                    if (installSession >= 0 && context.getPackageManager().getPackageInstaller().getSessionInfo(installSession) != null) {
                        phase = Phase.INSTALLING;
                        message = "请返回系统安装界面，或取消后重试";
                    } else {
                        installSession = -1;
                        phase = Phase.READY;
                        message = "更新已下载，可以安装";
                    }
                } else {
                    saved.edit().remove("ready").remove("installSession").apply();
                }
            }
            if (ready == null && saved.getBoolean("downloading", false)) {
                offer = AppUpdateRelease.parse(saved.getString("offer", null), BuildConfig.APP_UPDATE_TEST);
                if (offer.code <= installed) offer = null;
                phase = Phase.ERROR;
                message = "上次下载已中断，请重试";
            } else if (ready == null && BuildConfig.APP_UPDATE_AUTO) {
                String pending = saved.getString("available", null);
                if (pending != null) {
                    AppUpdateRelease release = AppUpdateRelease.parse(pending, BuildConfig.APP_UPDATE_TEST);
                    if (release.code > installed) {
                        offer = release;
                        phase = Phase.AVAILABLE;
                        message = "发现新版本 " + release.name;
                    } else saved.edit().remove("available").apply();
                }
            }
        } catch (Exception invalid) {
            phase = Phase.ERROR;
            message = "更新记录不可用，请重新检查";
        }
        boolean keepApk = ready != null;
        worker.execute(() -> {
            try {
                if (!directory.exists()) return;
                HandoffFiles.directory(directory, false);
                Files.deleteIfExists(partial.toPath());
                if (!keepApk) Files.deleteIfExists(apk.toPath());
            } catch (IOException ignored) { /* The next task rechecks the directory before writing. */ }
        });
    }

    Phase phase() { return phase; }
    String message() { return message; }
    AppUpdateRelease release() { return ready != null ? ready : offer; }
    long received() { return received; }
    boolean downloading() { return phase == Phase.DOWNLOADING || phase == Phase.CANCELLING || phase == Phase.VERIFYING; }
    boolean canDownload() { return transfer == null && !installPreparing && installSession < 0 && ready == null && offer != null; }
    boolean canCheck() { return transfer == null && !installPreparing && installSession < 0 && ready == null; }
    boolean canCancelInstall() { return installSession >= 0 && !installPreparing; }
    void observe(Runnable observer) { observers.add(observer); observer.run(); }
    void remove(Runnable observer) { observers.remove(observer); }

    private void state(Phase next, String text) {
        phase = next;
        message = text;
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }

    private AppUpdateTransfer start(Phase phase, String text) {
        try {
            transfer = new AppUpdateTransfer(BrowserMemory.get(context).network().routes);
            state(phase, text);
            return transfer;
        } catch (RuntimeException unavailable) {
            state(Phase.ERROR, "当前线路尚未就绪，请连接后重试");
            return null;
        }
    }

    void check() {
        if (!canCheck()) return;
        check(BrowserMemory.get(context).network().routes, null);
    }

    boolean checkAutomatically(NetworkRoute route, java.util.function.Consumer<Boolean> finished) {
        if (!canCheck() || saved.getBoolean("downloading", false) || phase == Phase.ERROR && offer != null)
            return false;
        return check(route, finished);
    }

    void cancelAutomaticCheck() {
        if (automaticCheck && phase == Phase.CHECKING && transfer != null) transfer.cancel();
    }

    private boolean check(NetworkRoute route, java.util.function.Consumer<Boolean> finished) {
        final AppUpdateTransfer job;
        try { job = new AppUpdateTransfer(route); }
        catch (RuntimeException unavailable) {
            if (finished == null) state(Phase.ERROR, "当前线路尚未就绪，请连接后重试");
            return false;
        }
        transfer = job;
        automaticCheck = finished != null;
        state(Phase.CHECKING, "正在检查更新");
        worker.execute(() -> {
            AppUpdateRelease found;
            try (AppUpdateTransfer owned = job) {
                AppUpdateRelease release = AppUpdateRelease.parse(owned.feed(BuildConfig.APP_UPDATE_TEST), BuildConfig.APP_UPDATE_TEST);
                android.content.pm.PackageInfo current = AppUpdatePackage.installed(context);
                requireCompatible(release, current);
                found = release.code > current.getLongVersionCode() ? release : null;
            } catch (Exception invalid) {
                main.post(() -> {
                    transfer = null;
                    automaticCheck = false;
                    if (finished == null) state(Phase.ERROR, "检查更新未完成，请确认线路后重试");
                    else {
                        state(offer == null ? Phase.IDLE : Phase.AVAILABLE,
                                offer == null ? "可以手动检查更新" : "发现新版本 " + offer.name);
                        finished.accept(false);
                    }
                });
                return;
            }
            main.post(() -> {
                transfer = null;
                automaticCheck = false;
                if (finished != null && job.cancelled()) {
                    state(offer == null ? Phase.IDLE : Phase.AVAILABLE, offer == null ? "可以手动检查更新" : "发现新版本 " + offer.name);
                    finished.accept(false);
                    return;
                }
                offer = found;
                if (BuildConfig.APP_UPDATE_AUTO) {
                    if (offer == null) saved.edit().remove("available").apply();
                    else saved.edit().putString("available", offer.metadata.toString()).apply();
                }
                state(offer == null ? Phase.IDLE : Phase.AVAILABLE,
                        offer == null ? "当前没有可用更新" : "发现新版本 " + offer.name);
                if (finished != null) finished.accept(true);
            });
        });
        return true;
    }

    private void requireCompatible(AppUpdateRelease release, android.content.pm.PackageInfo current) throws Exception {
        if (!AppUpdateRelease.PACKAGE.equals(current.packageName) || !release.certificate.equals(AppUpdatePackage.certificate(current))
                || !Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains(release.abi))
            throw new IOException("更新与当前应用不兼容");
    }

    boolean download(boolean accelerated) {
        if (!canDownload()) return false;
        AppUpdateRelease release = offer;
        received = 0;
        AppUpdateTransfer job = start(Phase.DOWNLOADING, "正在连接下载源");
        if (job == null) return false;
        worker.execute(() -> {
            try (AppUpdateTransfer owned = job) {
                android.content.pm.PackageInfo current = AppUpdatePackage.installed(context);
                requireCompatible(release, current);
                if (release.code <= current.getLongVersionCode()) throw new IOException("没有可安装的新版本");
                HandoffFiles.directory(directory, true);
                Files.deleteIfExists(partial.toPath());
                if (!saved.edit().putString("offer", release.metadata.toString()).putBoolean("downloading", true).commit())
                    throw new IOException("下载状态未能保存");
                owned.download(release, partial, accelerated, BuildConfig.APP_UPDATE_TEST, count -> main.post(() -> {
                    if (transfer != job || job.cancelled()) return;
                    received = count;
                    state(Phase.DOWNLOADING, "正在下载更新");
                }));
                main.post(() -> { if (!job.cancelled()) state(Phase.VERIFYING, "正在校验安装包"); });
                AppUpdatePackage.verify(context, release, partial);
                owned.check();
                Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!saved.edit().putString("ready", release.metadata.toString()).remove("downloading").remove("offer").commit())
                    throw new IOException("校验状态未能保存");
                main.post(() -> {
                    transfer = null;
                    if (job.cancelled()) {
                        saved.edit().remove("ready").apply();
                        worker.execute(this::deleteReadyFile);
                        state(Phase.AVAILABLE, "已取消下载");
                    } else {
                        ready = release;
                        state(Phase.READY, "校验通过，可以安装");
                    }
                });
            } catch (Exception failure) {
                if (BuildConfig.APP_UPDATE_TEST && !job.cancelled())
                    android.util.Log.w("StudioAppUpdateCI", "Owned update download or verification failed", failure);
                try { Files.deleteIfExists(partial.toPath()); }
                catch (IOException ignored) { /* Owned staging will be removed on retry. */ }
                saved.edit().remove("downloading").apply();
                main.post(() -> {
                    transfer = null;
                    state(job.cancelled() ? Phase.AVAILABLE : Phase.ERROR,
                            job.cancelled() ? "已取消下载" : "下载或校验未完成，请检查线路后重试");
                });
            }
        });
        return true;
    }

    void cancelDownload() {
        if (!downloading() || transfer == null) return;
        transfer.cancel();
        state(Phase.CANCELLING, "正在取消下载");
    }

    void startFailed() { if (canDownload()) state(Phase.ERROR, "下载任务未能启动，请重试"); }

    void install() {
        if (phase != Phase.READY || ready == null || installPreparing || installSession >= 0
                || !context.getPackageManager().canRequestPackageInstalls()) return;
        AppUpdateRelease release = ready;
        installPreparing = true;
        state(Phase.INSTALLING, "正在准备系统安装");
        worker.execute(() -> {
            int id = -1;
            try {
                HandoffFiles.directory(directory, false);
                id = AppUpdatePackage.prepare(context, release, apk, session -> {
                    if (!saved.edit().putInt("installSession", session).commit())
                        throw new IllegalStateException("安装状态未能保存");
                });
                int session = id;
                main.post(() -> { installSession = session; installPreparing = false; });
                AppUpdatePackage.commit(context, session);
            } catch (Exception failure) {
                AppUpdatePackage.abandon(context, id);
                saved.edit().remove("installSession").apply();
                main.post(() -> {
                    installSession = -1;
                    installPreparing = false;
                    confirmation = null;
                    state(Phase.READY, "安装准备未完成，可重试或重新下载");
                });
            }
        });
    }

    void installResult(Intent result) {
        int id = result.getIntExtra("studioSession", -1);
        if (id < 0 || id != saved.getInt("installSession", -1)
                || id != result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)) return;
        installSession = id;
        installPreparing = false;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) state(Phase.CONFIRM, "请在系统界面确认安装");
            else state(Phase.INSTALLING, "系统安装确认不可用，可取消后重试");
            return;
        }
        finishInstall(status == PackageInstaller.STATUS_SUCCESS);
    }

    Intent confirmation() { return confirmation; }
    void confirmationOpened() {
        confirmation = null;
        state(Phase.INSTALLING, "等待系统安装结果");
    }
    void cancelInstall() {
        if (!canCancelInstall()) return;
        AppUpdatePackage.abandon(context, installSession);
        finishInstall(false);
    }

    private void finishInstall(boolean success) {
        saved.edit().remove("installSession").apply();
        installSession = -1;
        confirmation = null;
        if (success) {
            discard();
            state(Phase.IDLE, "更新已安装");
        } else state(ready != null ? Phase.READY : Phase.ERROR, "安装未完成，可以重试");
    }

    void discard() {
        if (transfer != null || installSession >= 0 || installPreparing) return;
        ready = null;
        offer = null;
        received = 0;
        saved.edit().remove("ready").remove("offer").remove("downloading").apply();
        worker.execute(this::deleteReadyFile);
        state(Phase.IDLE, "可以重新检查更新");
    }

    private void deleteReadyFile() {
        try {
            HandoffFiles.directory(directory, false);
            Files.deleteIfExists(apk.toPath());
        } catch (IOException ignored) { /* No stale file can be installed without matching ready metadata. */ }
    }
}
