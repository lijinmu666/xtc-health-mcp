package com.lijinmu.healthxtc.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import com.lijinmu.healthxtc.R;
import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.core.HealthRunner;
import com.lijinmu.healthxtc.service.HealthService;

public final class MainActivity extends Activity {
    private TextView status;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.statusText);
        findViewById(R.id.testToolsButton).setOnClickListener(v -> startActivity(new Intent(this, TestToolsActivity.class)));
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 100);
        }
        try {
            Intent service = new Intent(this, HealthService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(HealthRunner.ACTION_STATUS));
        refresh();
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        super.onPause();
    }

    private void refresh() {
        String server = AppConfig.serverUrl(this);
        String text = "服务：正在运行\n"
                + "设备 ID：" + AppConfig.DEVICE_ID + "\n"
                + "服务器：" + (server.isEmpty() ? "未设置" : server) + "\n"
                + "自动上传：" + (AppConfig.autoUpload(this) ? "开启，每 " + AppConfig.intervalMinutes(this) + " 分钟" : "关闭") + "\n"
                + "WakeLock：" + (AppConfig.wakeLockEnabled(this) ? "开启" : "关闭") + "\n"
                + "当前任务：" + (HealthRunner.isRunning() ? "正在执行" : "空闲") + "\n\n"
                + "最近上传成功时间：\n" + AppConfig.lastUploadSuccessText(this) + "\n\n"
                + "最近结果：\n" + AppConfig.lastResult(this);
        status.setText(text);
    }
}
