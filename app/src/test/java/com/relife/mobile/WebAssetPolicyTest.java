package com.relife.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WebAssetPolicyTest {
    private static final String SERVER = "https://www.relifeapp.com";

    @Test
    public void onlySameOriginStaticGetRequestsCanBeIntercepted() {
        assertTrue(WebAssetPolicy.canIntercept(
                SERVER, "GET", "https://www.relifeapp.com/static/style.css?v=next"));
        assertFalse(WebAssetPolicy.canIntercept(
                SERVER, "POST", "https://www.relifeapp.com/static/style.css"));
        assertFalse(WebAssetPolicy.canIntercept(
                SERVER, "GET", "http://www.relifeapp.com/static/style.css"));
        assertFalse(WebAssetPolicy.canIntercept(
                SERVER, "GET", "https://cdn.example.com/static/style.css"));
        assertFalse(WebAssetPolicy.canIntercept(
                SERVER, "GET", "https://www.relifeapp.com/api/users/me"));
    }

    @Test
    public void htmlResponsesCannotMasqueradeAsCssOrJavaScript() {
        assertTrue(WebAssetPolicy.hasExpectedMimeType("/static/style.css", "text/css"));
        assertTrue(WebAssetPolicy.hasExpectedMimeType("/static/app.js", "application/javascript"));
        assertTrue(WebAssetPolicy.hasExpectedMimeType("/static/assets/Logo.png", "image/png"));
        assertFalse(WebAssetPolicy.hasExpectedMimeType("/static/style.css", "text/html"));
        assertFalse(WebAssetPolicy.hasExpectedMimeType("/static/app.js", "text/html"));
    }
}
