package com.relife.mobile;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import androidx.webkit.WebViewAssetLoader;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Loads current server assets first and falls back to the bundled snapshot on transport or MIME failures. */
final class WebAssetInterceptor {
    private static final int TIMEOUT_MS = 5_000;
    private final Context context;
    private final String serverUrl;
    private final WebViewAssetLoader assetLoader;

    WebAssetInterceptor(Context context, String serverUrl) {
        this.context = context.getApplicationContext();
        this.serverUrl = serverUrl;
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context))
                .build();
    }

    WebResourceResponse intercept(String requestUrl, String method) {
        if (!WebAssetPolicy.canIntercept(serverUrl, method, requestUrl)) return null;
        if (NetworkState.isOnline(context)) {
            WebResourceResponse remote = fromNetwork(requestUrl);
            if (remote != null) return remote;
        }
        return fromBundle(Uri.parse(requestUrl).getPath());
    }

    private WebResourceResponse fromNetwork(String requestUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "*/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                connection.disconnect();
                return null;
            }
            String contentType = connection.getContentType();
            String path = new URL(requestUrl).getPath();
            if (!WebAssetPolicy.hasExpectedMimeType(path, contentType)) {
                connection.disconnect();
                return null;
            }
            String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String encoding = charset(contentType);
            Map<String, String> headers = responseHeaders(connection);
            InputStream stream = new DisconnectingInputStream(connection.getInputStream(), connection);
            String reason = connection.getResponseMessage();
            return new WebResourceResponse(mime, encoding, status, reason == null ? "OK" : reason, headers, stream);
        } catch (Exception ignored) {
            if (connection != null) connection.disconnect();
            return null;
        }
    }

    private WebResourceResponse fromBundle(String path) {
        if (path == null) return null;
        Uri assetUri = Uri.parse("https://appassets.androidplatform.net/assets/web" + path);
        WebResourceResponse response = assetLoader.shouldInterceptRequest(assetUri);
        if (response != null) {
            response.setResponseHeaders(Map.of(
                    "Cache-Control", "public, max-age=31536000, immutable",
                    "X-Content-Type-Options", "nosniff",
                    "X-Re-Life-Asset-Source", "apk-fallback"
            ));
        }
        return response;
    }

    private static Map<String, String> responseHeaders(HttpURLConnection connection) {
        Map<String, String> result = new HashMap<>();
        copyHeader(connection, result, "Cache-Control");
        copyHeader(connection, result, "ETag");
        copyHeader(connection, result, "Last-Modified");
        result.put("X-Content-Type-Options", "nosniff");
        result.put("X-Re-Life-Asset-Source", "network");
        return result;
    }

    private static void copyHeader(HttpURLConnection connection, Map<String, String> target, String name) {
        String value = connection.getHeaderField(name);
        if (value != null && !value.isBlank()) target.put(name, value);
    }

    private static String charset(String contentType) {
        for (String part : contentType.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) return value.substring("charset=".length()).trim();
        }
        return null;
    }

    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection connection;

        DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
            super(input);
            this.connection = connection;
        }

        @Override public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
