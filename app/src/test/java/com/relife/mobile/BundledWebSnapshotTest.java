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
}
