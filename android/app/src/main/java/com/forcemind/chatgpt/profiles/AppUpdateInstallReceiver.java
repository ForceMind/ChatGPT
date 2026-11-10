package com.forcemind.chatgpt.profiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AppUpdateInstallReceiver extends BroadcastReceiver {
    static final String ACTION = "com.forcemind.chatgpt.profiles.APP_UPDATE_INSTALL_RESULT";

    @Override public void onReceive(Context context, Intent intent) {
        if (BuildConfig.APP_UPDATES_ENABLED && ACTION.equals(intent.getAction()))
            AppUpdateTask.get(context).installResult(intent);
    }
}
