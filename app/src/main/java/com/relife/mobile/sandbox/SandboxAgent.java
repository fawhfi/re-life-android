package com.relife.mobile.sandbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.EnumSet;
import java.util.stream.Stream;

/** Executes only small, explicitly granted tools inside filesDir/sandbox. */
public final class SandboxAgent {
    private static final String PREFS = "agent-permissions";
    private static final String AUDIT = "sandbox-audit.log";
    private SandboxAgent() {}

    public static boolean isGranted(Context context, String capability) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(capability, false);
    }

    public static void setGranted(Context context, String capability, boolean granted) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(capability, granted).apply();
        audit(context, "permission:" + capability + "=" + granted);
    }

    public static String execute(Context context, String command) {
        JSONObject result = new JSONObject();
        String tool = "";
        try {
            JSONObject input = new JSONObject(command == null ? "{}" : command);
            tool = input.optString("tool", "");
            EnumSet<SandboxCapability> grants = grants(context);
            if (!SandboxPolicy.canRun(grants, tool)) return error("CAPABILITY_DENIED");
            Path root = sandboxRoot(context);
            Files.createDirectories(root);
            switch (tool) {
                case "list_files" -> {
                    JSONArray files = new JSONArray();
                    try (Stream<Path> paths = Files.list(root)) { paths.limit(100).forEach(path -> files.put(root.relativize(path).toString())); }
                    result.put("files", files);
                }
                case "read_text" -> {
                    Path file = resolve(root, input.optString("path"));
                    byte[] bytes = Files.readAllBytes(file);
                    if (bytes.length > 64 * 1024) return error("FILE_TOO_LARGE");
                    result.put("text", new String(bytes, StandardCharsets.UTF_8));
                }
                case "write_text" -> {
                    Path file = resolve(root, input.optString("path"));
                    String text = input.optString("text", "");
                    if (text.length() > 64 * 1024) return error("FILE_TOO_LARGE");
                    Files.createDirectories(file.getParent());
                    Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    result.put("ok", true);
                }
                case "delete_file" -> {
                    Files.deleteIfExists(resolve(root, input.optString("path")));
                    result.put("ok", true);
                }
                case "device_info" -> {
                    result.put("manufacturer", Build.MANUFACTURER);
                    result.put("model", Build.MODEL);
                    result.put("android", Build.VERSION.RELEASE);
                }
                default -> { return error("UNKNOWN_TOOL"); }
            }
            audit(context, tool + "=ok");
            return result.toString();
        } catch (SecurityException e) {
            audit(context, tool + "=denied");
            return error("CAPABILITY_DENIED");
        } catch (Exception e) {
            audit(context, tool + "=error");
            return error("TOOL_FAILED");
        }
    }

    private static EnumSet<SandboxCapability> grants(Context context) {
        EnumSet<SandboxCapability> grants = EnumSet.noneOf(SandboxCapability.class);
        for (SandboxCapability capability : SandboxCapability.values()) if (isGranted(context, capability.name())) grants.add(capability);
        return grants;
    }

    private static Path sandboxRoot(Context context) { return context.getFilesDir().toPath().resolve("sandbox").toAbsolutePath().normalize(); }

    private static Path resolve(Path root, String relative) throws IOException {
        if (!SandboxPolicy.isInside(root, relative)) throw new SecurityException("outside sandbox");
        Path path = root.resolve(relative).normalize();
        if (Files.exists(path) && Files.isSymbolicLink(path)) throw new SecurityException("symlink");
        return path;
    }

    private static String error(String code) { return "{\"error\":\"" + code + "\"}"; }

    private static void audit(Context context, String event) {
        try {
            Path log = context.getFilesDir().toPath().resolve(AUDIT);
            Files.write(log, (Instant.now() + " " + event + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
