package com.relife.mobile.sandbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/** Pure, testable authorization and path-confinement policy for device tools. */
public final class SandboxPolicy {
    private static final Map<String, SandboxCapability> TOOL_CAPABILITIES = Map.of(
            "list_files", SandboxCapability.READ_FILES,
            "read_text", SandboxCapability.READ_FILES,
            "write_text", SandboxCapability.WRITE_FILES,
            "delete_file", SandboxCapability.WRITE_FILES,
            "device_info", SandboxCapability.DEVICE_INFO,
            "current_location", SandboxCapability.LOCATION,
            "take_photo", SandboxCapability.CAMERA,
            "share_text", SandboxCapability.SHARE,
            "open_url", SandboxCapability.OPEN_LINK
    );

    private SandboxPolicy() {}

    /** Returns true only when a relative path normalizes below the supplied sandbox root. */
    public static boolean isInside(Path root, String untrustedRelativePath) {
        if (root == null || untrustedRelativePath == null || untrustedRelativePath.trim().isEmpty()) return false;
        try {
            Path candidate = Paths.get(untrustedRelativePath);
            if (candidate.isAbsolute()) return false;
            Path normalizedRoot = root.toAbsolutePath().normalize();
            return normalizedRoot.resolve(candidate).normalize().startsWith(normalizedRoot);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Returns true when a known tool's exact capability is present in the supplied grant set. */
    public static boolean canRun(Set<SandboxCapability> grants, String tool) {
        SandboxCapability required = requiredCapability(tool);
        return required != null && grants != null && grants.contains(required);
    }

    /** Returns the capability required by a known tool, or null for an unknown tool. */
    public static SandboxCapability requiredCapability(String tool) {
        return TOOL_CAPABILITIES.get(tool);
    }
}
