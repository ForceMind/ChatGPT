package com.forcemind.chatgpt.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.Locale;

final class AppUpdatePrompt {
    static boolean show(Activity owner, String name, long size) {
        if (owner.isFinishing() || owner.isDestroyed() || !owner.hasWindowFocus() || name.isEmpty() || size <= 0)
            return false;
        new AlertDialog.Builder(owner)
                .setTitle("发现新版本")
                .setMessage(String.format(Locale.ROOT, "%s · %.1f MiB", name, size / 1048576.0))
                .setNegativeButton("稍后", null)
                .setPositiveButton("查看更新", (dialog, which) -> AppUpdateActivity.open(owner))
                .show();
        return true;
    }
}
