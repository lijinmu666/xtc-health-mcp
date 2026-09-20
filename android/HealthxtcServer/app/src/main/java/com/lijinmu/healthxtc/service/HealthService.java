package com.lijinmu.healthxtc.service;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.core.AppLog;
import com.lijinmu.healthxtc.core.HealthRunner;
import com.lijinmu.healthxtc.core.NotificationHelper;
import com.lijinmu.healthxtc.net.NetworkClient;

import org.json.JSONObject;

import okhttp3.WebSocket;

public final class HealthService extends Service {
    public static final String ACTION_AUTO_COLLECT = "com.lijinmu.healthxtc.AUTO_COLLECT";
    public static final String ACTION_RECONNECT = "com.lijinmu.healthxtc.RECONNECT";
    public static final String ACTION_WATCHDOG = "com.lijinmu.healthxtc.WATCHDOG";
    private static final long SOCKET_STALE_MS = 90_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile WebSocket socket;
    private PowerManager.WakeLock serviceWakeLock;
    private boolean destroyed;
    private volatile long lastSocketActivityAt;
    private volatile int socketGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) startForeground(7300, NotificationHelper.serviceNotification(this));
        applyServiceWakeLock();
        connectSocket();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_AUTO_COLLECT.equals(action)) runUpload("scheduled", null);
        if (ACTION_RECONNECT.equals(action)) {
            applyServiceWakeLock();
            if (socket != null) socket.cancel();
            socket = null;
            lastSocketActivityAt = 0L;
            connectSocket();
        }
        if (ACTION_WATCHDOG.equals(action) || action == null) {
            applyServiceWakeLock();
            checkSocketHealth();
        }
        return START_STICKY;
    }

    private void applyServiceWakeLock() {
        boolean enabled = AppConfig.wakeLockEnabled(this);
        try {
            if (enabled) {
                if (serviceWakeLock == null) {
                    PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
                    serviceWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "healthxtc:service");
                    serviceWakeLock.setReferenceCounted(false);
                }
                if (!serviceWakeLock.isHeld()) {
                    serviceWakeLock.acquire();
                    AppLog.i(this, "WakeLock：开启");
                }
            } else if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
                serviceWakeLock.release();
                AppLog.i(this, "WakeLock：关闭");
            }
        } catch (Throwable error) {
            AppLog.e(this, "切换 WakeLock 失败", error);
        }
    }

    private void runUpload(String source, String commandId) {
        PowerManager.WakeLock lock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "healthxtc:measurement");
        try { lock.acquire(30_000L); } catch (Throwable ignored) {}
        HealthRunner.collectAndUpload(this, source, commandId, true, (success, message, measurement) -> {
            if (lock.isHeld()) lock.release();
        });
    }

    private void connectSocket() {
        if (destroyed || AppConfig.serverUrl(this).isEmpty() || socket != null) return;
        final int generation = ++socketGeneration;
        lastSocketActivityAt = SystemClock.elapsedRealtime();
        socket = NetworkClient.get(this).openCommandSocket(new NetworkClient.SocketCallback() {
            @Override public void opened() {
                if (generation != socketGeneration) return;
                lastSocketActivityAt = SystemClock.elapsedRealtime();
                AppLog.i(HealthService.this, "MCP 指令监听已连接");
            }

            @Override public void message(String text) {
                if (generation != socketGeneration) return;
                lastSocketActivityAt = SystemClock.elapsedRealtime();
                if ("pong".equals(text)) return;
                try {
                    JSONObject object = new JSONObject(text);
                    String type = object.optString("type", "");
                    if ("collect".equals(type) || "collect-health-now".equals(type) || "collect_health_now".equals(type)) {
                        AppLog.i(HealthService.this, "收到 MCP 采集指令");
                        runUpload("manual", object.optString("command_id", null));
                    }
                } catch (Throwable error) {
                    AppLog.e(HealthService.this, "解析 MCP 指令失败", error);
                }
            }

            @Override public void closed(String reason) {
                if (generation != socketGeneration) return;
                AppLog.w(HealthService.this, "MCP 连接关闭，等待重连");
                socket = null;
                lastSocketActivityAt = 0L;
                reconnectLater();
            }

            @Override public void failed(Throwable error) {
                if (generation != socketGeneration) return;
                AppLog.e(HealthService.this, "MCP 指令连接失败", error);
                socket = null;
                lastSocketActivityAt = 0L;
                reconnectLater();
            }
        });
    }

    private void checkSocketHealth() {
        if (destroyed || AppConfig.serverUrl(this).isEmpty()) return;
        if (socket == null) {
            connectSocket();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (lastSocketActivityAt <= 0L || now - lastSocketActivityAt > SOCKET_STALE_MS) {
            AppLog.w(this, "MCP 连接超时，重新连接");
            socket.cancel();
            socket = null;
            lastSocketActivityAt = 0L;
            connectSocket();
            return;
        }

        if (!socket.send("ping")) {
            AppLog.w(this, "MCP 心跳发送失败，重新连接");
            socket.cancel();
            socket = null;
            lastSocketActivityAt = 0L;
            connectSocket();
        }
    }

    private void reconnectLater() {
        if (!destroyed) handler.postDelayed(this::connectSocket, 15_000L);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        socketGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) socket.cancel();
        socket = null;
        lastSocketActivityAt = 0L;
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) serviceWakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
