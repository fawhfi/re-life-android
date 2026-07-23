package com.relife.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class MainActivityCameraTest {
    @Test
    public void webCameraGrantsTrustedVideoAndRejectsOtherOrigins() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                BuildConfig.APPLICATION_ID,
                Manifest.permission.CAMERA
        );
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            RecordingPermissionRequest trusted = new RecordingPermissionRequest(
                    BuildConfig.REL_SERVER_URL + "/",
                    new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}
            );
            RecordingPermissionRequest untrusted = new RecordingPermissionRequest(
                    "https://attacker.test/",
                    new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}
            );

            scenario.onActivity(activity -> {
                WebChromeClient client = webView(activity).getWebChromeClient();
                client.onPermissionRequest(trusted);
                client.onPermissionRequest(untrusted);
            });

            assertArrayEquals(
                    new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE},
                    trusted.grantedResources
            );
            assertTrue(untrusted.denied);
        }
    }

    private static WebView webView(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("webView");
            field.setAccessible(true);
            return (WebView) field.get(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingPermissionRequest extends PermissionRequest {
        private final Uri origin;
        private final String[] resources;
        private volatile String[] grantedResources;
        private volatile boolean denied;

        RecordingPermissionRequest(String origin, String[] resources) {
            this.origin = Uri.parse(origin);
            this.resources = resources;
        }

        @Override public Uri getOrigin() { return origin; }
        @Override public String[] getResources() { return resources; }
        @Override public void grant(String[] resources) { grantedResources = resources; }
        @Override public void deny() { denied = true; }
    }
}
