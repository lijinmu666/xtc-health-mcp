package com.lijinmu.healthxtc.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.core.AppLog;

public final class Scheduler {
    public static final String ACTION_AUTO_ALARM = "com.lijinmu.healthxtc.AUTO_ALARM";
    public static final String ACTION_WATCHDOG_ALARM = "com.lijinmu.healthxtc.WATCHDOG_ALARM";
    private static final int AUTO_REQUEST_CODE = 7301;
    private static final int WATCHDOG_REQUEST_CODE = 7302;
    private static final long WATCHDOG_INTERVAL_MS = 60_000L;

    private Scheduler() {}

    public static void apply(Context context) {
        scheduleWatchdog(context);
        scheduleAuto(context);
    }

    public static void scheduleAuto(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = autoPendingIntent(context);
        manager.cancel(pending);
        if (!AppConfig.autoUpload(context)) {
            AppLog.i(context, "自动上传已关闭");
            return;
        }
        long delay = AppConfig.intervalMinutes(context) * 60_000L;
        long triggerAt = System.currentTimeMillis() + delay;
        try {
            scheduleExact(manager, triggerAt, pending);
            AppLog.i(context, "下次自动上传: " + new java.util.Date(triggerAt));
        } catch (Throwable error) {
            AppLog.e(context, "安排自动上传失败", error);
        }
    }

    public static void scheduleWatchdog(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = watchdogPendingIntent(context);
        manager.cancel(pending);
        try {
            scheduleExact(manager, System.currentTimeMillis() + WATCHDOG_INTERVAL_MS, pending);
        } catch (Throwable error) {
            AppLog.e(context, "安排连接看门狗失败", error);
        }
    }

    private static void scheduleExact(AlarmManager manager, long triggerAt, PendingIntent pending) {
        if (Build.VERSION.SDK_INT >= 23) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } else {
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    private static PendingIntent autoPendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_AUTO_ALARM);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, AUTO_REQUEST_CODE, intent, flags);
    }

    private static PendingIntent watchdogPendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_WATCHDOG_ALARM);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags);
    }
}
