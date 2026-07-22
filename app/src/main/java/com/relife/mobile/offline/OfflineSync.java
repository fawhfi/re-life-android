package com.relife.mobile.offline;

import android.content.Context;

import com.relife.mobile.BuildConfig;
import com.relife.mobile.AppIntegrity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
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
                int status = send(context, item);
                if ((status >= 200 && status < 300)
                        || status == 401 || status == 403 || status == 404
                        || !OfflinePolicy.shouldRetry(status)) {
                    store.remove(id);
                } else {
                    store.incrementAttempts(id);
                }
            } catch (Exception ignored) {
                store.incrementAttempts(id);
            }
        }
    }

    private static int send(Context context, JSONObject item) throws Exception {
        String path = item.optString("path", "/");
        URL url = new URL(BuildConfig.REL_SERVER_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(item.optString("method", "POST"));
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Re-Life-Request-Id", idFor(item));
        connection.setRequestProperty("X-Re-Life-App-Integrity", AppIntegrity.header(context));
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
        InputStream responseBody = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (responseBody != null) {
            try (BufferedReader ignored = new BufferedReader(new InputStreamReader(
                    responseBody, StandardCharsets.UTF_8))) {
                while (ignored.readLine() != null) { /* drain connection */ }
            }
        }
        connection.disconnect();
        return status;
    }

    private static String idFor(JSONObject item) {
        String id = item.optString("id", "").trim();
        return id.isEmpty() ? "offline-unknown" : id;
    }
}
