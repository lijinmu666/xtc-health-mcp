package com.lijinmu.healthxtc.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class Measurement {
    public final Double heartRate;
    public final Double temperature;

    public Measurement(Double heartRate, Double temperature) {
        this.heartRate = heartRate;
        this.temperature = temperature;
    }

    public JSONObject toJson(String source, String commandId) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("device_id", AppConfig.DEVICE_ID);
        object.put("measured_at", isoNow());
        object.put("source", source);
        if (heartRate != null) object.put("heart_rate", heartRate);
        if (temperature != null) object.put("temperature", temperature);
        if (commandId != null && !commandId.isEmpty()) object.put("command_id", commandId);
        return object;
    }

    public String summary() {
        String heart = heartRate == null ? "无" : String.format(Locale.US, "%.0f", heartRate);
        String temp = temperature == null ? "无" : String.format(Locale.US, "%.2f", temperature);
        return "心率=" + heart + "，体温=" + temp;
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
