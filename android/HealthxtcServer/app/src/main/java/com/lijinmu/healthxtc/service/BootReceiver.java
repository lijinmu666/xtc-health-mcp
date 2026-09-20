package com.lijinmu.healthxtc.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.lijinmu.healthxtc.core.AppLog;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppLog.i(context, "系统启动或应用更新，恢复计划和监听服务");
        Scheduler.apply(context);
        Intent service = new Intent(context, HealthService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service); else context.startService(service);
        } catch (Throwable error) {
            AppLog.e(context, "恢复监听服务失败", error);
        }
    }
}
