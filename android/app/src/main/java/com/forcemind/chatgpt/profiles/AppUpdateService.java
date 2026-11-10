package com.forcemind.chatgpt.profiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Only a user-started download keeps this service active. Browser retention is independent. */
public final class AppUpdateService extends Service {
    private static final String CHANNEL = "app-update";
    private static final int NOTIFICATION = 2418;
    private AppUpdateTask task;
    private boolean observing;
    private final Runnable changed = this::refresh;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!BuildConfig.APP_UPDATES_ENABLED || intent == null) { stopSelf(); return START_NOT_STICKY; }
        task = AppUpdateTask.get(this);
        if ("cancel".equals(intent.getAction())) {
            task.cancelDownload();
            if (!task.downloading()) stopSelf();
            return START_NOT_STICKY;
        }
        if (observing) return START_NOT_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification());
            if (task.download(intent.getBooleanExtra("accelerated", false))) {
                observing = true;
                task.observe(changed);
            } else stopSelf();
        } catch (RuntimeException unavailable) {
            task.startFailed();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void refresh() {
        if (!task.downloading()) { stopSelf(); return; }
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }

    private Notification notification() {
        AppUpdateRelease release = task.release();
        int percent = release == null ? 0 : (int) Math.min(100, task.received() * 100 / release.size);
        PendingIntent open = PendingIntent.getActivity(this, 2418, new Intent(this, AppUpdateActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 2419, new Intent(this, AppUpdateService.class).setAction("cancel"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("ChatGPT Studio · 应用更新").setContentText(task.message() + " · " + percent + "%")
                .setProgress(100, percent, release == null).setOnlyAlertOnce(true).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "取消下载", cancel).build()).build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        if (task != null) task.cancelDownload();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (observing) {
            task.remove(changed);
            if (task.downloading()) task.cancelDownload();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
