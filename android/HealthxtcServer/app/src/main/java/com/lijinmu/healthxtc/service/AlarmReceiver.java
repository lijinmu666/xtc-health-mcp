package com.lijinmu.healthxtc.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.core.AppLog;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Scheduler.ACTION_WATCHDOG_ALARM.equals(action)) {
            Scheduler.scheduleWatchdog(context);
            startService(context, HealthService.ACTION_WATCHDOG, "启动连接看门狗失败");
            return;
        }

        if (!Scheduler.ACTION_AUTO_ALARM.equals(action) || !AppConfig.autoUpload(context)) return;
        AppLog.i(context, "自动上传触发");
        Scheduler.scheduleAuto(context);
        startService(context, HealthService.ACTION_AUTO_COLLECT, "启动自动采集失败");
    }

    private static void startService(Context context, String action, String errorMessage) {
        Intent service = new Intent(context, HealthService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        } catch (Throwable error) {
            AppLog.e(context, errorMessage, error);
        }
    }
}
