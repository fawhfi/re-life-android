package com.relife.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Reports a reproducible build/signing fingerprint. This is a detection signal,
 * never an authorization secret: a rooted process can still hook this method.
 */
public final class AppIntegrity {
    private static volatile String cachedHeader;
    private AppIntegrity() {}

    public static String header(Context context) {
        String value = cachedHeader;
        if (value != null) return value;
        synchronized (AppIntegrity.class) {
            if (cachedHeader == null) {
                cachedHeader = "package=" + context.getPackageName()
                        + ";version=" + version(context)
                        + ";cert_sha256=" + certificateSha256(context)
                        + ";apk_sha256=" + apkSha256(context);
            }
            return cachedHeader;
        }
    }

    public static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { return ""; }
    }

    public static String certificateSha256(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = Build.VERSION.SDK_INT >= 28
                    ? manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                    : manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] signatures = Build.VERSION.SDK_INT >= 28
                    ? info.signingInfo.getApkContentsSigners() : info.signatures;
            return signatures == null || signatures.length == 0 ? "" : sha256(signatures[0].toByteArray());
        } catch (Exception ignored) { return ""; }
    }

    public static String apkSha256(Context context) {
        try (FileInputStream input = new FileInputStream(new File(context.getApplicationInfo().sourceDir))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return hex(digest.digest());
        } catch (Exception ignored) { return ""; }
    }

    private static String sha256(byte[] value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception ignored) { return ""; }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }
}
