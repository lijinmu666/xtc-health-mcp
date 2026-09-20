package com.lijinmu.healthxtc.core;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    public static final String ACTION_UPDATED = "com.lijinmu.healthxtc.LOG_UPDATED";
    private static final String PREFS = "healthxtc_logs";
    private static final int MAX_CHARS = 12000;

    private AppLog() {}

    public static void i(Context context, String message) { append(context, "信息", message); }
    public static void w(Context context, String message) { append(context, "警告", message); }
    public static void e(Context context, String message, Throwable error) {
        append(context, "错误", message + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    public static synchronized void append(Context context, String level, String message) {
        long now = System.currentTimeMillis();
        String signature = level + "|" + message;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (signature.equals(prefs.getString("last_signature", ""))
                && now - prefs.getLong("last_time", 0L) < 60_000L) return;
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String previous = read(context);
        String next = "[" + time + "][" + level + "] " + message + "\n" + previous;
        if (next.length() > MAX_CHARS) next = next.substring(0, MAX_CHARS);
        prefs.edit()
                .putString("text", next)
                .putString("last_signature", signature)
                .putLong("last_time", now)
                .apply();
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }

    public static String read(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("text", "暂无日志");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("text")
                .remove("last_signature")
                .remove("last_time")
                .apply();
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }
}
