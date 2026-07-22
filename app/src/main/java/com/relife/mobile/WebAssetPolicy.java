package com.relife.mobile;

import java.net.URI;
import java.util.Locale;

final class WebAssetPolicy {
    private WebAssetPolicy() {}

    static boolean canIntercept(String serverUrl, String method, String requestUrl) {
        if (!"GET".equalsIgnoreCase(method)) return false;
        try {
            URI server = URI.create(serverUrl);
            URI request = URI.create(requestUrl);
            if (!sameOrigin(server, request)) return false;
            String path = request.getPath();
            return path != null && path.startsWith("/static/");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean hasExpectedMimeType(String path, String contentType) {
        if (path == null || contentType == null) return false;
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".css")) return "text/css".equals(mime);
        if (lowerPath.endsWith(".js")) {
            return "application/javascript".equals(mime)
                    || "text/javascript".equals(mime)
                    || "application/x-javascript".equals(mime);
        }
        if (lowerPath.endsWith(".json")) return "application/json".equals(mime) || mime.endsWith("+json");
        if (lowerPath.endsWith(".png")) return "image/png".equals(mime);
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) return "image/jpeg".equals(mime);
        if (lowerPath.endsWith(".svg")) return "image/svg+xml".equals(mime);
        if (lowerPath.endsWith(".woff2")) return "font/woff2".equals(mime) || "application/font-woff".equals(mime);
        return !"text/html".equals(mime);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right)
                && right.getUserInfo() == null;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
