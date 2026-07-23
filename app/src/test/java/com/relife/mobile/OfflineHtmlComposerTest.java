package com.relife.mobile;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class OfflineHtmlComposerTest {
    @Test
    public void coreStylesAreInlinedBeforeOfflinePageLoads() {
        String html = OfflineHtmlComposer.compose(
                "<html><head><link rel=\"stylesheet\" href=\"/static/style.css\"></head><body>Re-Life</body></html>",
                "body{color:#123456}",
                ".card{display:block}",
                "window.bridgeReady=true"
        );

        assertTrue(html.contains("<style id=\"relife-bundled-css\">"));
        assertTrue(html.contains("body{color:#123456}"));
        assertTrue(html.contains(".card{display:block}"));
        assertTrue(html.indexOf("relife-bundled-css") < html.indexOf("</head>"));
        assertTrue(html.contains("window.bridgeReady=true"));
    }

    @Test
    public void injectedContentCannotCloseItsWrapperElement() {
        String html = OfflineHtmlComposer.compose(
                "<html><head></head><body></body></html>",
                "body{} </style><script>badCss()</script>",
                "",
                "window.ready=true;</script><div>badScript</div>"
        );

        assertTrue(html.contains("<\\/style><script>badCss()"));
        assertTrue(html.contains("<\\/script><div>badScript"));
        assertFalse(html.contains("body{} </style><script>badCss()"));
    }

    @Test
    public void documentWithoutHeadStillReceivesBundledResources() {
        String html = OfflineHtmlComposer.compose("<main>Re-Life</main>", "main{}", "", "bridge() ");

        assertTrue(html.startsWith("<style id=\"relife-bundled-css\">"));
        assertTrue(html.contains("<script>bridge() </script><main>Re-Life</main>"));
    }

    @Test
    public void offlineDocumentDoesNotWaitForLinkedStylesheets() {
        String html = OfflineHtmlComposer.compose(
                "<html><head>"
                        + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                        + "<link href=\"https://fonts.googleapis.com/font.css\" rel=\"stylesheet\">"
                        + "<link rel=\"stylesheet\" href=\"/static/style.css\">"
                        + "<link rel=\"icon\" href=\"/favicon.png\">"
                        + "</head><body></body></html>",
                "body{}",
                "",
                ""
        );

        assertFalse(html.contains("rel=\"preconnect\""));
        assertFalse(html.contains("rel=\"stylesheet\""));
        assertTrue(html.contains("rel=\"icon\""));
    }
}
