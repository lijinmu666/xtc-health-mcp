package com.lijinmu.healthxtc.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.lijinmu.healthxtc.R;
import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.service.HealthService;
import com.lijinmu.healthxtc.service.Scheduler;

public final class SettingsActivity extends Activity {
    private EditText serverInput;
    private EditText intervalInput;
    private Switch autoSwitch;
    private Switch notificationSwitch;
    private Switch wakeLockSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        serverInput = findViewById(R.id.serverUrlInput);
        intervalInput = findViewById(R.id.intervalInput);
        autoSwitch = findViewById(R.id.autoSwitch);
        notificationSwitch = findViewById(R.id.notificationSwitch);
        wakeLockSwitch = findViewById(R.id.wakeLockSwitch);
        serverInput.setText(AppConfig.serverUrl(this));
        intervalInput.setText(String.valueOf(AppConfig.intervalMinutes(this)));
        autoSwitch.setChecked(AppConfig.autoUpload(this));
        notificationSwitch.setChecked(AppConfig.allowNotifications(this));
        wakeLockSwitch.setChecked(AppConfig.wakeLockEnabled(this));
        findViewById(R.id.saveButton).setOnClickListener(v -> save());
    }

    private void save() {
        String server = serverInput.getText().toString().trim();
        if (!server.isEmpty() && !server.startsWith("https://")) {
            Toast.makeText(this, "服务器地址必须以 https:// 开头", Toast.LENGTH_LONG).show();
            return;
        }
        int interval;
        try { interval = Integer.parseInt(intervalInput.getText().toString().trim()); }
        catch (Throwable ignored) { interval = 30; }
        if (interval < 1) interval = 1;
        AppConfig.save(this, server, interval, autoSwitch.isChecked(), notificationSwitch.isChecked(), wakeLockSwitch.isChecked());
        Scheduler.apply(this);
        try {
            Intent service = new Intent(this, HealthService.class).setAction(HealthService.ACTION_RECONNECT);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        }
        catch (Throwable ignored) {}
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
