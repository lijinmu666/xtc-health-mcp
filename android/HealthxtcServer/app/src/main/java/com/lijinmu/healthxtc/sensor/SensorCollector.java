package com.lijinmu.healthxtc.sensor;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.lijinmu.healthxtc.core.AppLog;
import com.lijinmu.healthxtc.core.Measurement;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SensorCollector {
    public static final int TYPE_HEART_RATE_XTC = 88620080;
    public static final int TYPE_TEMPERATURE_XTC = 88620081;
    public static final long TIMEOUT_MS = 20_000L;

    public interface Callback {
        void success(Measurement measurement);
        void failure(String reason);
    }

    private final Context context;

    public SensorCollector(Context context) {
        this.context = context.getApplicationContext();
    }

    public void collect(Callback callback) {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                context.checkSelfPermission("android.permission.BODY_SENSORS") != PackageManager.PERMISSION_GRANTED) {
            callback.failure("BODY_SENSORS 权限未授权");
            return;
        }

        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor heart = manager.getDefaultSensor(TYPE_HEART_RATE_XTC);
        Sensor temperature = manager.getDefaultSensor(TYPE_TEMPERATURE_XTC);
        if (heart == null && temperature == null) {
            callback.failure("没有找到 XTC 心率或体温传感器");
            return;
        }

        HandlerThread thread = new HandlerThread("HealthMeasurement");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        AtomicBoolean completed = new AtomicBoolean(false);
        final Double[] values = new Double[2]; // heart, temperature

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (event.values == null || event.values.length == 0) return;
                double value = event.values[0];
                if (event.sensor.getType() == TYPE_HEART_RATE_XTC && value >= 20 && value <= 250) {
                    values[0] = value;
                } else if (event.sensor.getType() == TYPE_TEMPERATURE_XTC && value >= 20 && value <= 45) {
                    values[1] = value;
                }
                if (values[0] != null && values[1] != null) finish(true, null);
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

            private void finish(boolean normal, String reason) {
                if (!completed.compareAndSet(false, true)) return;
                manager.unregisterListener(this);
                handler.removeCallbacksAndMessages(null);
                Measurement measurement = (values[0] != null || values[1] != null)
                        ? new Measurement(values[0], values[1]) : null;
                thread.quitSafely();
                if (measurement != null) callback.success(measurement);
                else callback.failure(reason == null ? "20 秒内没有获得有效数据" : reason);
            }
        };

        boolean registered = false;
        try {
            if (heart != null) registered |= manager.registerListener(listener, heart, 200_000, handler);
            if (temperature != null) registered |= manager.registerListener(listener, temperature, 200_000, handler);
        } catch (Throwable error) {
            manager.unregisterListener(listener);
            thread.quitSafely();
            AppLog.e(context, "注册传感器失败", error);
            callback.failure(error.getClass().getSimpleName() + ": " + error.getMessage());
            return;
        }
        if (!registered) {
            manager.unregisterListener(listener);
            thread.quitSafely();
            callback.failure("传感器监听注册失败");
            return;
        }

        AppLog.i(context, "开始测量，超时 20 秒");
        handler.postDelayed(() -> {
            if (!completed.compareAndSet(false, true)) return;
            manager.unregisterListener(listener);
            Measurement partial = (values[0] != null || values[1] != null)
                    ? new Measurement(values[0], values[1]) : null;
            thread.quitSafely();
            if (partial != null) callback.success(partial);
            else callback.failure("20 秒内没有获得有效数据");
        }, TIMEOUT_MS);
    }
}
