package com.relife.mobile.sandbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import org.junit.Test;

public class SandboxPolicyTest {
    private final Path root = Paths.get("sandbox").toAbsolutePath().normalize();

    @Test
    public void pathsCannotEscapeThePrivateSandbox() {
        assertTrue(SandboxPolicy.isInside(root, "notes/today.txt"));
        assertFalse(SandboxPolicy.isInside(root, "../session-cookie.txt"));
        assertFalse(SandboxPolicy.isInside(root, "/sdcard/Download/private.txt"));
    }

    @Test
    public void capabilitiesAreDeniedUntilExplicitlyGranted() {
        EnumSet<SandboxCapability> grants = EnumSet.noneOf(SandboxCapability.class);
        assertFalse(SandboxPolicy.canRun(grants, "read_text"));
        assertFalse(SandboxPolicy.canRun(grants, "write_text"));

        grants.add(SandboxCapability.READ_FILES);
        assertTrue(SandboxPolicy.canRun(grants, "read_text"));
        assertFalse(SandboxPolicy.canRun(grants, "write_text"));
        assertFalse(SandboxPolicy.canRun(grants, "shell"));
    }
}
