package com.lijinmu.healthxtc.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.lijinmu.healthxtc.ui.MainActivity;

public final class NotificationHelper {
    public static final String SERVICE_CHANNEL = "healthxtc_service";
    private static final String RESULT_CHANNEL = "healthxtc_results";

    private NotificationHelper() {}

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(SERVICE_CHANNEL, "后台监听", NotificationManager.IMPORTANCE_MIN);
        service.setDescription("维持 MCP 远程指令监听");
        NotificationChannel result = new NotificationChannel(RESULT_CHANNEL, "采集结果", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(service);
        manager.createNotificationChannel(result);
    }

    public static Notification serviceNotification(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 1, intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, SERVICE_CHANNEL) : new Notification.Builder(context);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Healthxtc Server")
                .setContentText("正在监听 MCP 指令")
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    public static void result(Context context, boolean success, String text) {
        if (!AppConfig.allowNotifications(context)) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, RESULT_CHANNEL) : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(success ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error)
                .setContentTitle(success ? "健康数据上传成功" : "健康数据上传失败")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
    }
}
