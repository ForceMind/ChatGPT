package com.forcemind.chatgpt.profiles;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-process coordinator. The background job never starts a browser or a proxy core. */
final class AppUpdateAuto {
    static final int JOB = 0x53545544;
    private static AppUpdateAuto instance;
    private static Supplier<Instant> clock = Instant::now;

    static AppUpdateAuto get(Context context) {
        if (instance == null) instance = new AppUpdateAuto(context.getApplicationContext());
        return instance;
    }

    static void clockForTest(Supplier<Instant> source) {
        if (!BuildConfig.APP_UPDATE_TEST) throw new IllegalStateException("Test clock requires the owned fixture");
        clock = source;
    }

    static void routeChanged() {
        if (instance != null) instance.requestPump();
    }

    private final Context context;
    private final SharedPreferences saved;
    private final AppUpdateTask task;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = this::foregroundCheck;
    private MainActivity mainActivity;
    private Consumer<AppUpdateRelease> environment;
    private boolean startupPending = true;
    private boolean checking;
    private long retryAfter;

    private AppUpdateAuto(Context context) {
        this.context = context;
        saved = context.getSharedPreferences("app-update-schedule-v1", Context.MODE_PRIVATE);
        task = AppUpdateTask.get(context);
        task.observe(this::requestPump);
    }

    void mainResumed(MainActivity activity) {
        mainActivity = activity;
        requestPump();
    }

    void mainPaused(MainActivity activity) {
        if (mainActivity == activity) mainActivity = null;
        requestPump();
    }

    void launcherOpened() {
        startupPending = true;
        requestPump();
    }

    void environment(Consumer<AppUpdateRelease> receiver) {
        environment = receiver;
        requestPump();
    }

    void requestPump() {
        handler.removeCallbacks(pump);
        if (visible()) handler.postDelayed(pump, 300);
    }

    private boolean visible() { return mainActivity != null || environment != null; }

    private void foregroundCheck() {
        if (!visible()) return;
        Instant now = clock.get();
        scheduleNext(now);
        handler.postDelayed(pump, Math.max(1, Duration.between(now, AppUpdateSchedule.nextNoon(now)).toMillis()));
        maybePrompt(now);
        if (startupPending || AppUpdateSchedule.due(now, saved.getString("noonDay", "")))
            begin(now, null);
    }

    boolean backgroundCheck(String scheduledDay, Runnable completed) {
        Instant now = clock.get();
        if (!AppUpdateSchedule.day(now).toString().equals(scheduledDay)
                || !AppUpdateSchedule.due(now, saved.getString("noonDay", ""))) {
            handler.post(() -> scheduleNext(clock.get(), scheduledDay));
            return false;
        }
        boolean started = begin(now, () -> {
            completed.run();
            handler.post(() -> scheduleNext(clock.get(), scheduledDay));
        });
        if (!started) handler.post(() -> scheduleNext(clock.get(), scheduledDay));
        return started;
    }

    private boolean begin(Instant now, Runnable completed) {
        if (checking || SystemClock.elapsedRealtime() < retryAfter) return false;
        NetworkSession route = BrowserMemory.existingNetwork();
        if (route == null || !route.ready()) return false;
        boolean noon = AppUpdateSchedule.due(now, saved.getString("noonDay", ""));
        String day = AppUpdateSchedule.day(now).toString();
        String reason = startupPending ? (noon ? "启动及中午补查" : "启动检查") : "中午检查或补查";
        checking = task.checkAutomatically(route.routes, reason, success -> {
            checking = false;
            if (success) {
                startupPending = false;
                if (noon) saved.edit().putString("noonDay", day).apply();
            } else retryAfter = SystemClock.elapsedRealtime() + 60000;
            if (completed != null) completed.run();
            requestPump();
        });
        return checking;
    }

    private void maybePrompt(Instant now) {
        AppUpdateRelease release = task.release();
        if (task.phase() != AppUpdateTask.Phase.AVAILABLE || release == null || HandoffRuntime.networkBusy()) return;
        String key = AppUpdateSchedule.day(now) + ":" + release.code;
        if (key.equals(saved.getString("prompt", ""))) return;
        if (mainActivity != null && mainActivity.canShowAppUpdatePrompt()
                && AppUpdatePrompt.show(mainActivity, release.name, release.size)) {
            saved.edit().putString("prompt", key).apply();
        } else if (mainActivity == null && environment != null) {
            environment.accept(release);
        }
    }

    void environmentPrompted(long code) {
        AppUpdateRelease release = task.release();
        if (release != null && release.code == code)
            saved.edit().putString("prompt", AppUpdateSchedule.day(clock.get()) + ":" + code).apply();
    }

    void stopBackgroundCheck() { task.cancelAutomaticCheck(); }

    private void scheduleNext(Instant now) { scheduleNext(now, null); }

    private void scheduleNext(Instant now, String consumed) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        Instant next = AppUpdateSchedule.nextNoon(now);
        String day = AppUpdateSchedule.day(next).toString();
        JobInfo pending = scheduler.getPendingJob(JOB);
        // Keep today's eligible OS job until it runs; a foreground success will make it a no-op.
        if (pending != null) {
            String scheduled = pending.getExtras().getString("day");
            if (!java.util.Objects.equals(consumed, scheduled)
                    && (day.equals(scheduled) || AppUpdateSchedule.day(now).toString().equals(scheduled))) return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putString("day", day);
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, AppUpdateCheckJob.class))
                .setMinimumLatency(Math.max(1, Duration.between(now, next).toMillis()))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras).build());
    }
}
