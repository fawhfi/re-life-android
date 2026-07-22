package com.relife.mobile.offline;

import android.content.Context;

import com.relife.mobile.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** At-least-once replay of explicitly allow-listed, user-session-bound mutations. */
public final class OfflineSync {
    private OfflineSync() {}

    public static void sync(Context context) {
        OfflineQueueStore store = new OfflineQueueStore(context);
        for (JSONObject item : store.pending()) {
            String id = item.optString("id");
            try {
                int status = send(item);
                if (status >= 200 && status < 300 || status == 401 || status == 403 || status == 404) {
                    store.remove(id);
                } else {
                    store.incrementAttempts(id);
                }
            } catch (Exception ignored) {
                store.incrementAttempts(id);
            }
        }
    }

    private static int send(JSONObject item) throws Exception {
        String path = item.optString("path", "/");
        URL url = new URL(BuildConfig.REL_SERVER_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(item.optString("method", "POST"));
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = item.optString("cookie", "");
        if (!cookie.trim().isEmpty()) connection.setRequestProperty("Cookie", cookie);
        String body = item.optString("body", "");
        if (!body.trim().isEmpty() && !"null".equals(body)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        try (BufferedReader ignored = new BufferedReader(new InputStreamReader(
                status >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
            while (ignored.readLine() != null) { /* drain connection */ }
        }
        connection.disconnect();
        return status;
    }
}
