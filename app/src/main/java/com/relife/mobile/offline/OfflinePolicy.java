package com.relife.mobile.offline;

import java.util.Locale;

/** Public policy for operations that are safe enough to persist and replay. */
public final class OfflinePolicy {
    private OfflinePolicy() {}

    public static boolean canQueue(String method, String path) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String cleanPath = path == null ? "" : path.split("\\?", 2)[0];
        if (verb.equals("POST") && cleanPath.equals("/api/records")) return true;
        if (verb.equals("PATCH") && cleanPath.equals("/api/users/me")) return true;
        return verb.equals("DELETE")
                && (cleanPath.equals("/api/records") || cleanPath.startsWith("/api/records/"));
    }

    public static boolean shouldRetry(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }
}
