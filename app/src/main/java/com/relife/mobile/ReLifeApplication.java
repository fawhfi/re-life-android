package com.relife.mobile;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;
import android.os.Build;
import com.relife.mobile.offline.SyncScheduler;

public final class ReLifeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WebView.setDataDirectorySuffix("relife");
        SyncScheduler.schedule(this);
    }

    public static Context appContext(Context context) {
        return context.getApplicationContext();
    }
}
