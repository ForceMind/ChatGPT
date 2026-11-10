package com.forcemind.chatgpt.profiles;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** One inexact Shanghai-noon check. Never starts a download or opens an activity. */
public final class AppUpdateCheckJob extends JobService {
    private JobParameters active;

    @Override public boolean onStartJob(JobParameters params) {
        if (!BuildConfig.APP_UPDATE_AUTO) return false;
        active = params;
        boolean started = AppUpdateAuto.get(this).backgroundCheck(params.getExtras().getString("day"), () -> {
            if (active != params) return;
            active = null;
            jobFinished(params, false);
        });
        if (!started) active = null;
        return started;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) {
            active = null;
            AppUpdateAuto.get(this).stopBackgroundCheck();
        }
        return false;
    }
}
