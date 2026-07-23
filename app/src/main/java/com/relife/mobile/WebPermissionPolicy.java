package com.relife.mobile;

import java.net.URI;

/** Restricts WebView device permissions to the configured app origin. */
final class WebPermissionPolicy {
    static final String VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE";

    private WebPermissionPolicy() {}

    static boolean canGrantCamera(String serverUrl, String requestOrigin, String[] resources) {
        if (resources == null || resources.length != 1 || !VIDEO_CAPTURE.equals(resources[0])) {
            return false;
        }
        try {
            URI server = URI.create(serverUrl);
            URI origin = URI.create(requestOrigin);
            return sameOrigin(server, origin) && origin.getUserInfo() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
