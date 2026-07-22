package com.relife.mobile.offline;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            OfflineSync.sync(this);
            jobFinished(params, false);
        }, "relife-offline-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
