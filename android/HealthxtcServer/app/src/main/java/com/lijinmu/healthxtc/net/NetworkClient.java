package com.lijinmu.healthxtc.net;

import android.content.Context;

import com.lijinmu.healthxtc.core.AppConfig;
import com.lijinmu.healthxtc.core.AppLog;
import com.lijinmu.healthxtc.core.Measurement;

import org.json.JSONObject;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.dnsoverhttps.DnsOverHttps;

public final class NetworkClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static volatile NetworkClient instance;
    private final Context context;
    private final OkHttpClient client;

    public interface ResultCallback {
        void complete(boolean success, int statusCode, String message);
    }

    public interface SocketCallback {
        void opened();
        void message(String text);
        void closed(String reason);
        void failed(Throwable error);
    }

    private NetworkClient(Context context) {
        this.context = context.getApplicationContext();
        this.client = buildClient(this.context);
    }

    public static NetworkClient get(Context context) {
        if (instance == null) {
            synchronized (NetworkClient.class) {
                if (instance == null) instance = new NetworkClient(context);
            }
        }
        return instance;
    }

    private static OkHttpClient buildClient(Context context) {
        OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .build();
        try {
            DnsOverHttps doh = new DnsOverHttps.Builder()
                    .client(bootstrap)
                    .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                    .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                    .includeIPv6(false)
                    .build();
            Dns resilientDns = hostname -> {
                try {
                    List<InetAddress> addresses = doh.lookup(hostname);
                    return addresses;
                } catch (Throwable dohError) {
                    AppLog.w(context, "DoH 失败，回退系统 DNS: " + dohError.getMessage());
                    List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                    return addresses == null ? Collections.emptyList() : addresses;
                }
            };
            return bootstrap.newBuilder().dns(resilientDns).build();
        } catch (Throwable error) {
            AppLog.e(context, "初始化 DoH 失败，使用系统 DNS", error);
            return bootstrap;
        }
    }

    public void testConnection(ResultCallback callback) {
        String base = AppConfig.serverUrl(context);
        if (!validBase(base, callback)) return;
        Request request = new Request.Builder().url(base + "/health").get().build();
        execute(request, callback);
    }

    public void upload(Measurement measurement, String source, String commandId, ResultCallback callback) {
        String base = AppConfig.serverUrl(context);
        if (!validBase(base, callback)) return;
        try {
            JSONObject body = measurement.toJson(source, commandId);
            Request request = new Request.Builder()
                    .url(base + "/api/device/measurements")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            execute(request, callback);
        } catch (Throwable error) {
            AppLog.e(context, "创建上传请求失败", error);
            callback.complete(false, 0, error.getMessage());
        }
    }

    private boolean validBase(String base, ResultCallback callback) {
        if (base.isEmpty() || !(base.startsWith("https://"))) {
            callback.complete(false, 0, "请先填写以 https:// 开头的服务器地址");
            return false;
        }
        return true;
    }

    private void execute(Request request, ResultCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException error) {
                AppLog.e(context, "网络请求失败", error);
                callback.complete(false, 0, error.getClass().getSimpleName() + ": " + error.getMessage());
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String body = closeable.body() == null ? "" : closeable.body().string();
                    String result = "HTTP " + closeable.code();
                    if (!closeable.isSuccessful() && !body.isEmpty()) result += ": " + body;
                    AppLog.i(context, result);
                    callback.complete(closeable.isSuccessful(), closeable.code(), body);
                } catch (Throwable error) {
                    AppLog.e(context, "读取响应失败", error);
                    callback.complete(false, response.code(), error.getMessage());
                }
            }
        });
    }

    public WebSocket openCommandSocket(SocketCallback callback) {
        String base = AppConfig.serverUrl(context);
        if (base.isEmpty()) {
            callback.failed(new IllegalStateException("服务器地址未设置"));
            return null;
        }
        String wsUrl = (base.startsWith("https://") ? "wss://" + base.substring(8) : "ws://" + base.substring(7))
                + "/api/device/ws?device_id=" + AppConfig.DEVICE_ID;
        Request request = new Request.Builder().url(wsUrl).build();
        return client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { callback.opened(); }
            @Override public void onMessage(WebSocket webSocket, String text) { callback.message(text); }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { callback.closed(code + ": " + reason); }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { callback.failed(error); }
        });
    }
}
