package com.lijinmu.healthxtc.core;

import android.content.Context;
import android.content.Intent;

import com.lijinmu.healthxtc.net.NetworkClient;
import com.lijinmu.healthxtc.sensor.SensorCollector;

import java.util.concurrent.atomic.AtomicBoolean;

public final class HealthRunner {
    public static final String ACTION_STATUS = "com.lijinmu.healthxtc.STATUS_UPDATED";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public interface Callback {
        void complete(boolean success, String message, Measurement measurement);
    }

    private HealthRunner() {}

    public static boolean isRunning() { return RUNNING.get(); }

    public static void collectOnly(Context context, Callback callback) {
        start(context, false, "manual", null, false, callback);
    }

    public static void collectAndUpload(Context context, String source, String commandId, boolean notify, Callback callback) {
        start(context, true, source, commandId, notify, callback);
    }

    public static void uploadCustom(Context context, Measurement measurement, Callback callback) {
        if (!RUNNING.compareAndSet(false, true)) {
            callback.complete(false, "已有任务正在运行", null);
            return;
        }
        NetworkClient.get(context).upload(measurement, "manual", null, (success, code, message) -> {
            RUNNING.set(false);
            if (success) AppConfig.markUploadSuccess(context);
            String result = success ? "自定义数据上传成功，HTTP " + code : "自定义数据上传失败，HTTP " + code + ": " + message;
            update(context, result);
            callback.complete(success, result, measurement);
        });
    }

    private static void start(Context rawContext, boolean upload, String source, String commandId,
                              boolean notify, Callback callback) {
        Context context = rawContext.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            callback.complete(false, "已有测量任务正在运行", null);
            return;
        }
        update(context, "正在测量……");
        new SensorCollector(context).collect(new SensorCollector.Callback() {
            @Override public void success(Measurement measurement) {
                AppLog.i(context, "测量完成: " + measurement.summary());
                if (!upload) {
                    RUNNING.set(false);
                    String result = "测量成功：" + measurement.summary();
                    update(context, result);
                    callback.complete(true, result, measurement);
                    return;
                }
                NetworkClient.get(context).upload(measurement, source, commandId, (success, code, message) -> {
                    RUNNING.set(false);
                    if (success) AppConfig.markUploadSuccess(context);
                    String result = success
                            ? "上传成功，HTTP " + code + "；" + measurement.summary()
                            : "上传失败，HTTP " + code + "：" + message;
                    update(context, result);
                    if (notify) NotificationHelper.result(context, success, result);
                    callback.complete(success, result, measurement);
                });
            }

            @Override public void failure(String reason) {
                RUNNING.set(false);
                String result = "测量失败：" + reason + "；本次不上传";
                AppLog.w(context, result);
                update(context, result);
                if (notify) NotificationHelper.result(context, false, result);
                callback.complete(false, result, null);
            }
        });
    }

    private static void update(Context context, String result) {
        AppConfig.setLastResult(context, result);
        context.sendBroadcast(new Intent(ACTION_STATUS).setPackage(context.getPackageName()));
    }
}
