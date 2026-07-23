package com.relife.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WebPermissionPolicyTest {
    private static final String SERVER = "https://www.relifeapp.com";
    private static final String VIDEO = "android.webkit.resource.VIDEO_CAPTURE";
    private static final String AUDIO = "android.webkit.resource.AUDIO_CAPTURE";

    @Test
    public void trustedOriginMayRequestVideoOnly() {
        assertTrue(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://www.relifeapp.com/", new String[]{VIDEO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://www.relifeapp.com/", new String[]{VIDEO, AUDIO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://www.relifeapp.com/", new String[]{AUDIO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://www.relifeapp.com/", new String[0]));
    }

    @Test
    public void lookalikeOrDowngradedOriginsCannotUseCamera() {
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://www.relifeapp.com.attacker.test/", new String[]{VIDEO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "http://www.relifeapp.com/", new String[]{VIDEO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "https://user@www.relifeapp.com/", new String[]{VIDEO}));
        assertFalse(WebPermissionPolicy.canGrantCamera(
                SERVER, "not a URI", new String[]{VIDEO}));
    }
}
