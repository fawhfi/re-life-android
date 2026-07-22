package com.relife.mobile;

/** Builds an offline document whose core styling cannot fail as a subresource request. */
public final class OfflineHtmlComposer {
    private OfflineHtmlComposer() {}

    /**
     * Injects bundled CSS and the native bridge into a trusted HTML snapshot.
     * Null inputs are treated as empty strings and closing tags in injected
     * content are escaped so they cannot terminate their wrapper element.
     */
    public static String compose(String html, String coreCss, String themeCss, String bridgeScript) {
        String safeHtml = html == null ? "" : html;
        String styles = "<style id=\"relife-bundled-css\">"
                + escapeStyle(coreCss) + "\n" + escapeStyle(themeCss) + "</style>";
        String script = "<script>" + escapeScript(bridgeScript) + "</script>";
        String injection = styles + script;
        if (safeHtml.contains("</head>")) return safeHtml.replace("</head>", injection + "</head>");
        return injection + safeHtml;
    }

    private static String escapeStyle(String value) {
        return value == null ? "" : value.replace("</style", "<\\/style");
    }

    private static String escapeScript(String value) {
        return value == null ? "" : value.replace("</script", "<\\/script");
    }
}
