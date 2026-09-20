package com.lijinmu.healthxtc.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lijinmu.healthxtc.R;
import com.lijinmu.healthxtc.core.AppLog;
import com.lijinmu.healthxtc.core.HealthRunner;
import com.lijinmu.healthxtc.core.Measurement;
import com.lijinmu.healthxtc.net.NetworkClient;

public final class TestToolsActivity extends Activity {
    private TextView resultText;
    private TextView logText;
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { showLogs(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_tools);
        resultText = findViewById(R.id.resultText);
        logText = findViewById(R.id.logText);
        findViewById(R.id.testConnectionButton).setOnClickListener(v -> testConnection());
        findViewById(R.id.collectButton).setOnClickListener(v -> collect(false));
        findViewById(R.id.collectUploadButton).setOnClickListener(v -> collect(true));
        findViewById(R.id.customUploadButton).setOnClickListener(v -> customDialog());
        findViewById(R.id.clearLogsButton).setOnClickListener(v -> { AppLog.clear(this); showLogs(); });
        showLogs();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(logReceiver, new IntentFilter(AppLog.ACTION_UPDATED));
    }

    @Override protected void onPause() {
        try { unregisterReceiver(logReceiver); } catch (Throwable ignored) {}
        super.onPause();
    }

    private void testConnection() {
        setResult("正在测试连接……");
        NetworkClient.get(this).testConnection((success, code, message) ->
                runOnUiThread(() -> setResult((success ? "连接成功" : "连接失败") + "，HTTP " + code + "\n" + message)));
    }

    private void collect(boolean upload) {
        setResult(upload ? "正在测量并上传……" : "正在测量……");
        HealthRunner.Callback callback = (success, message, measurement) -> runOnUiThread(() -> setResult(message));
        if (upload) HealthRunner.collectAndUpload(this, "manual", null, false, callback);
        else HealthRunner.collectOnly(this, callback);
    }

    private void customDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, 0);
        EditText heart = new EditText(this);
        heart.setHint("心率（20–250，可留空）");
        heart.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText temp = new EditText(this);
        temp.setHint("体温（20–45，可留空）");
        temp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        panel.addView(heart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(temp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("上传自定义数据")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("上传", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String heartText = heart.getText().toString().trim();
            String tempText = temp.getText().toString().trim();
            Double heartValue;
            Double tempValue;
            try {
                heartValue = heartText.isEmpty() ? null : Double.parseDouble(heartText);
                tempValue = tempText.isEmpty() ? null : Double.parseDouble(tempText);
            } catch (Throwable ignoredError) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                return;
            }
            if (heartValue == null && tempValue == null) {
                Toast.makeText(this, "至少填写一个健康值", Toast.LENGTH_SHORT).show();
                return;
            }
            if (heartValue != null && (heartValue < 20 || heartValue > 250)) {
                Toast.makeText(this, "心率必须在 20–250 之间", Toast.LENGTH_SHORT).show();
                return;
            }
            if (tempValue != null && (tempValue < 20 || tempValue > 45)) {
                Toast.makeText(this, "体温必须在 20–45 之间", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            setResult("正在上传自定义数据……");
            Measurement measurement = new Measurement(heartValue, tempValue);
            HealthRunner.uploadCustom(this, measurement,
                    (success, message, result) -> runOnUiThread(() -> setResult(message)));
        }));
        dialog.show();
    }

    private void setResult(String text) { resultText.setText(text); }
    private void showLogs() { logText.setText(AppLog.read(this)); }
}
