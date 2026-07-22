package com.relife.mobile.offline;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small app-private durable queue. Payloads are JSON and never leave this app directory. */
public final class OfflineQueueStore {
    private static final String PREFS = "offline-store";
    private static final String QUEUE = "mutation-queue";
    private static final String CACHE_PREFIX = "cache:";
    private final SecureValueStore values;

    public OfflineQueueStore(Context context) {
        values = new SecureValueStore(context, PREFS);
    }

    public synchronized String enqueue(String method, String path, String body, String cookie) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", UUID.randomUUID().toString());
            entry.put("method", method);
            entry.put("path", path);
            entry.put("body", body == null ? JSONObject.NULL : body);
            entry.put("cookie", cookie == null ? "" : cookie);
            entry.put("createdAt", System.currentTimeMillis());
            entry.put("attempts", 0);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        JSONArray queue = readQueue();
        queue.put(entry);
        writeQueue(queue);
        return entry.optString("id");
    }

    public synchronized List<JSONObject> pending() {
        JSONArray queue = readQueue();
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item != null) result.add(item);
        }
        return result;
    }

    public synchronized void remove(String id) {
        JSONArray current = readQueue();
        JSONArray next = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) next.put(item);
        }
        writeQueue(next);
    }

    public synchronized void incrementAttempts(String id) {
        JSONArray current = readQueue();
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) {
                try {
                    item.put("attempts", item.optInt("attempts", 0) + 1);
                } catch (JSONException impossible) {
                    throw new IllegalStateException(impossible);
                }
                break;
            }
        }
        writeQueue(current);
    }

    public synchronized void cachePut(String key, String body) {
        values.put(CACHE_PREFIX + key, body);
    }

    public synchronized String cacheGet(String key) {
        return values.get(CACHE_PREFIX + key, null);
    }

    public synchronized void clear() {
        values.clear();
    }

    private JSONArray readQueue() {
        String raw = values.get(QUEUE, "[]");
        try { return new JSONArray(raw); } catch (JSONException ignored) { return new JSONArray(); }
    }

    private void writeQueue(JSONArray queue) {
        values.put(QUEUE, queue.toString());
    }
}
