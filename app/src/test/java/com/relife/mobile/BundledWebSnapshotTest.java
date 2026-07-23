package com.relife.mobile;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class BundledWebSnapshotTest {
    @Test
    public void androidRepositoryContainsOfflineHtmlCssAndJavaScript() throws Exception {
        Path assets = Path.of("src", "main", "assets", "web");
        assertTrue(Files.size(assets.resolve("templates/index.html")) > 1_000);
        assertTrue(Files.size(assets.resolve("static/style.css")) > 10_000);
        assertTrue(Files.size(assets.resolve("static/app.js")) > 10_000);
    }

    @Test
    public void webViewHardwareAccelerationIsExplicitlyEnabled() throws Exception {
        String manifest = Files.readString(Path.of("src", "main", "AndroidManifest.xml"));
        assertTrue(manifest.contains("<application"));
        assertTrue(manifest.contains("android:hardwareAccelerated=\"true\""));
    }

    @Test
    public void playIntegrityDoesNotBlockInitialPageLoad() throws Exception {
        String activity = Files.readString(Path.of(
                "src", "main", "java", "com", "relife", "mobile", "MainActivity.java"));
        int clientCreated = activity.indexOf("playIntegrityClient = new PlayIntegrityClient(this)");
        int pageLoad = activity.indexOf("loadMainPage(state);", clientCreated);
        int optionalWarmup = activity.indexOf("if (NetworkState.isOnline(this))", clientCreated);
        assertTrue(clientCreated >= 0);
        assertTrue(pageLoad > clientCreated);
        assertTrue(optionalWarmup > pageLoad);
    }
}
