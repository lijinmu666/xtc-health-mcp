package com.lijinmu.healthxtc;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

import com.lijinmu.healthxtc.core.AppLog;
import com.lijinmu.healthxtc.core.NotificationHelper;
import com.lijinmu.healthxtc.service.HealthService;
import com.lijinmu.healthxtc.service.Scheduler;

import org.conscrypt.Conscrypt;

import java.security.Security;

public final class HealthApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable error) {
            AppLog.e(this, "安装 Conscrypt 失败", error);
        }
        NotificationHelper.createChannels(this);
        Scheduler.apply(this);
        try {
            Intent intent = new Intent(this, HealthService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        } catch (Throwable error) {
            AppLog.e(this, "启动监听服务失败", error);
        }
    }
}
