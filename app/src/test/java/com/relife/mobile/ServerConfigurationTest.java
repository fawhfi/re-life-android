package com.relife.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServerConfigurationTest {
    @Test
    public void defaultServerUsesCanonicalWebViewOrigin() {
        assertEquals("https://www.relifeapp.com", BuildConfig.REL_SERVER_URL);
    }
}
