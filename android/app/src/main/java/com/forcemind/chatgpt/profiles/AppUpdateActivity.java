package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Shared update sheet, hosted in the same process as the network coordinator. */
public final class AppUpdateActivity extends Activity {
    private StudioSheet sheet;
    private AppUpdateTask task;
    private TextView status, bytes;
    private ProgressBar progress;
    private Button primary, secondary, check;
    private final Runnable changed = this::render;

    static void open(Context context) {
        if (BuildConfig.APP_UPDATES_ENABLED) context.startActivity(new Intent(context, AppUpdateActivity.class));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!BuildConfig.APP_UPDATES_ENABLED) { finish(); return; }
        task = AppUpdateTask.get(this);
        String version;
        try { version = AppUpdatePackage.installed(this).versionName; }
        catch (Exception unavailable) { version = ""; }
        sheet = new StudioSheet(this, "应用更新", "当前版本 " + version);
        status = sheet.text("", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sheet.body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setContentDescription("更新下载进度");
        sheet.body.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        bytes = sheet.text("", 13);
        sheet.body.addView(bytes, new LinearLayout.LayoutParams(-1, -2));
        primary = button(true);
        secondary = button(false);
        check = button(false);
        sheet.setOnDismissListener(dialog -> finish());
        sheet.show();
        render();
    }

    private Button button(boolean main) {
        Button button = sheet.button("", main);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = StudioTypography.dp(this, 8);
        sheet.footer.addView(button, layout);
        sheet.footer.setVisibility(View.VISIBLE);
        return button;
    }

    @Override protected void onStart() {
        super.onStart();
        if (task != null) task.observe(changed);
    }

    @Override protected void onResume() {
        super.onResume();
        if (task != null) render();
    }

    @Override protected void onStop() {
        if (task != null) task.remove(changed);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (sheet != null) {
            sheet.setOnDismissListener(null);
            sheet.dismiss();
        }
        super.onDestroy();
    }

    private void show(Button button, String label, Runnable action) {
        button.setVisibility(View.VISIBLE);
        button.setText(label);
        button.setEnabled(action != null);
        button.setOnClickListener(action == null ? null : view -> action.run());
    }

    private void render() {
        AppUpdateTask.Phase phase = task.phase();
        AppUpdateRelease release = task.release();
        status.setText(task.message());
        boolean showProgress = task.downloading() && release != null;
        progress.setVisibility(showProgress || phase == AppUpdateTask.Phase.CHECKING ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!showProgress);
        bytes.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            int percent = (int) Math.min(100, task.received() * 100 / release.size);
            progress.setProgress(percent);
            bytes.setText(String.format(Locale.ROOT, "%d%% · %.1f / %.1f MiB", percent,
                    task.received() / 1048576.0, release.size / 1048576.0));
        }
        secondary.setVisibility(View.GONE);
        check.setVisibility(View.GONE);
        switch (phase) {
            case DOWNLOADING:
            case VERIFYING:
                show(primary, "取消下载", task::cancelDownload);
                break;
            case CANCELLING:
                show(primary, "正在取消", null);
                break;
            case CHECKING:
                show(primary, "正在检查", null);
                break;
            case READY:
                show(primary, "安装更新", this::install);
                show(secondary, "重新检查", () -> { task.discard(); task.check(); });
                break;
            case CONFIRM:
                show(primary, "确认安装", this::confirm);
                show(secondary, "取消安装", task::cancelInstall);
                break;
            case INSTALLING:
                show(primary, "等待系统安装", null);
                if (task.canCancelInstall()) show(secondary, "取消安装", task::cancelInstall);
                break;
            default:
                if (task.canDownload()) {
                    show(primary, phase == AppUpdateTask.Phase.ERROR ? "重试下载" : "下载更新", () -> download(false));
                    if (!BuildConfig.APP_UPDATE_TEST) show(secondary, "国内加速下载", () -> download(true));
                    show(check, "重新检查", task::check);
                } else show(primary, "检查更新", task::check);
        }
    }

    private void download(boolean accelerated) {
        try { startForegroundService(new Intent(this, AppUpdateService.class).putExtra("accelerated", accelerated)); }
        catch (RuntimeException unavailable) { task.startFailed(); }
    }

    private void install() {
        if (getPackageManager().canRequestPackageInstalls()) { task.install(); return; }
        try {
            Toast.makeText(this, "请允许此来源安装，返回后再点安装更新", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "无法打开系统安装权限设置", Toast.LENGTH_LONG).show();
        }
    }

    private void confirm() {
        Intent request = task.confirmation();
        if (request == null) return;
        try {
            startActivity(request);
            task.confirmationOpened();
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "系统安装界面不可用，请取消后重试", Toast.LENGTH_LONG).show();
        }
    }
}
