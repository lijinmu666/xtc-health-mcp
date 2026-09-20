package com.lijinmu.healthxtc.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppConfig {
    public static final String DEVICE_ID = "xtc-z8a";
    private static final String PREFS = "healthxtc_settings";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_INTERVAL = "interval_minutes";
    private static final String KEY_AUTO = "auto_upload";
    private static final String KEY_NOTIFY = "allow_notifications";
    private static final String KEY_WAKE_LOCK = "wake_lock";
    private static final String KEY_LAST_UPLOAD_SUCCESS = "last_upload_success";

    private AppConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String serverUrl(Context context) {
        String value = prefs(context).getString(KEY_SERVER, "");
        if (value == null) return "";
        return normalizeServer(value);
    }

    public static int intervalMinutes(Context context) {
        return Math.max(1, prefs(context).getInt(KEY_INTERVAL, 30));
    }

    public static boolean autoUpload(Context context) {
        return prefs(context).getBoolean(KEY_AUTO, false);
    }

    public static boolean allowNotifications(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY, true);
    }

    public static boolean wakeLockEnabled(Context context) {
        return prefs(context).getBoolean(KEY_WAKE_LOCK, false);
    }

    public static void save(Context context, String server, int interval, boolean auto, boolean notify, boolean wakeLock) {
        prefs(context).edit()
                .putString(KEY_SERVER, normalizeServer(server))
                .putInt(KEY_INTERVAL, Math.max(1, interval))
                .putBoolean(KEY_AUTO, auto)
                .putBoolean(KEY_NOTIFY, notify)
                .putBoolean(KEY_WAKE_LOCK, wakeLock)
                .apply();
    }

    public static String normalizeServer(String server) {
        String value = server == null ? "" : server.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/health")) value = value.substring(0, value.length() - 7);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public static void setLastResult(Context context, String text) {
        prefs(context).edit().putString("last_result", text).apply();
    }

    public static String lastResult(Context context) {
        return prefs(context).getString("last_result", "尚无记录");
    }

    public static void markUploadSuccess(Context context) {
        prefs(context).edit().putLong(KEY_LAST_UPLOAD_SUCCESS, System.currentTimeMillis()).apply();
    }

    public static String lastUploadSuccessText(Context context) {
        long time = prefs(context).getLong(KEY_LAST_UPLOAD_SUCCESS, 0L);
        if (time <= 0L) return "尚无成功上传记录";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(time));
    }
}
