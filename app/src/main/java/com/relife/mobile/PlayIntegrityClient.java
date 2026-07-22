package com.relife.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.URLEncoder;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Google Play Integrity Standard API client. The token is intentionally kept
 * in memory only; the server must decode it and verify nonce/package/verdicts.
 */
public final class PlayIntegrityClient {
    private static final long TOKEN_TTL_MS = 2 * 60 * 1000L;
    public interface Callback {
        void onToken(String token);
        void onUnavailable(String reason);
    }

    private final StandardIntegrityManager manager;
    private final long cloudProjectNumber;
    private final ExecutorService challengeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String token = "";
    private volatile String lastNonce = "";
    private volatile String tokenAction = "";
    private volatile long tokenIssuedAtMs;
    private volatile long requestGeneration;
    private volatile StandardIntegrityManager.StandardIntegrityTokenProvider provider;
    private static volatile String latestToken = "";
    private static volatile String latestNonce = "";
    private static volatile long latestIssuedAtMs;

    public PlayIntegrityClient(Context context) {
        manager = IntegrityManagerFactory.createStandard(context.getApplicationContext());
        cloudProjectNumber = parseProjectNumber(BuildConfig.REL_PLAY_CLOUD_PROJECT_NUMBER);
    }

    public void requestToken(Callback callback) {
        requestToken("app_session", callback);
    }

    public void requestToken(String action, Callback callback) {
        String normalizedAction = normalizeAction(action);
        // Session evidence may be reused briefly for low-risk startup. Every
        // protected transaction must obtain a new server challenge/token.
        if ("app_session".equals(normalizedAction)
                && isFresh() && normalizedAction.equals(tokenAction)) {
            callback.onToken(token);
            return;
        }
        if (cloudProjectNumber <= 0) {
            callback.onUnavailable("PLAY_PROJECT_NUMBER_NOT_CONFIGURED");
            return;
        }
        token = "";
        lastNonce = "";
        tokenAction = normalizedAction;
        final long generation = ++requestGeneration;
        challengeExecutor.execute(() -> {
            try {
                // In production this must be a one-time nonce issued by the
                // server and bound to the logged-in user/action. A random
                // nonce is permitted only for local debug builds; it cannot
                // establish server trust and is disabled in release builds.
                String challenge = fetchServerNonce(normalizedAction);
                lastNonce = challenge;
                requestStandardToken(challenge, normalizedAction, generation, callback);
            } catch (Exception error) {
                if (generation == requestGeneration) {
                    mainHandler.post(() -> callback.onUnavailable("PLAY_CHALLENGE_UNAVAILABLE"));
                }
            }
        });
    }

    private void requestStandardToken(
            String challenge,
            String action,
            long generation,
            Callback callback
    ) {
        StandardIntegrityManager.StandardIntegrityTokenProvider current = provider;
        if (current != null) {
            requestFromProvider(current, challenge, action, generation, callback);
            return;
        }
        StandardIntegrityManager.PrepareIntegrityTokenRequest prepareRequest =
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build();
        manager.prepareIntegrityToken(prepareRequest)
                .addOnSuccessListener(prepared -> {
                    provider = prepared;
                    requestFromProvider(prepared, challenge, action, generation, callback);
                })
                .addOnFailureListener(error -> unavailable(
                        generation, callback, "PLAY_INTEGRITY_PREPARE_FAILED"));
    }

    private void requestFromProvider(
            StandardIntegrityManager.StandardIntegrityTokenProvider prepared,
            String challenge,
            String action,
            long generation,
            Callback callback
    ) {
        String requestHash = requestHash(challenge, action);
        StandardIntegrityManager.StandardIntegrityTokenRequest tokenRequest =
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build();
        prepared.request(tokenRequest)
                .addOnSuccessListener(response -> {
                    if (generation != requestGeneration) return;
                    token = response.token();
                    tokenIssuedAtMs = System.currentTimeMillis();
                    latestToken = token;
                    latestNonce = lastNonce;
                    latestIssuedAtMs = tokenIssuedAtMs;
                    mainHandler.post(() -> callback.onToken(token));
                })
                .addOnFailureListener(error -> unavailable(
                        generation, callback, "PLAY_INTEGRITY_UNAVAILABLE"));
    }

    private void unavailable(long generation, Callback callback, String reason) {
        if (generation == requestGeneration) {
            mainHandler.post(() -> callback.onUnavailable(reason));
        }
    }

    private static String requestHash(String challenge, String action) {
        String binding = challenge + "\n" + action + "\ncom.relife.mobile";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(binding.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public String currentToken() { return token; }
    public String currentNonce() { return lastNonce; }
    public String currentAction() { return tokenAction; }
    public boolean isFresh() {
        return !token.trim().isEmpty()
                && tokenIssuedAtMs > 0
                && System.currentTimeMillis() - tokenIssuedAtMs < TOKEN_TTL_MS;
    }
    public static String latestToken() {
        return isFresh(latestToken, latestIssuedAtMs) ? latestToken : "";
    }
    public static String latestNonce() {
        return isFresh(latestToken, latestIssuedAtMs) ? latestNonce : "";
    }

    private static boolean isFresh(String value, long issuedAtMs) {
        return value != null && !value.trim().isEmpty()
                && issuedAtMs > 0
                && System.currentTimeMillis() - issuedAtMs < TOKEN_TTL_MS;
    }

    private String fetchServerNonce(String action) throws Exception {
        String endpoint = BuildConfig.REL_PLAY_CHALLENGE_URL == null
                ? "" : BuildConfig.REL_PLAY_CHALLENGE_URL.trim();
        if (endpoint.isEmpty()) {
            if (BuildConfig.DEBUG) return nonce();
            throw new SecurityException("server challenge endpoint is required");
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        endpoint = endpoint + separator + "action="
                + URLEncoder.encode(action, StandardCharsets.UTF_8.name());
        URL url = new URL(endpoint);
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean localDevelopment = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("10.0.2.2");
        if (!protocol.equals("https") && !(localDevelopment && protocol.equals("http"))) {
            throw new SecurityException("challenge endpoint must use HTTPS");
        }
        URL serverUrl = new URL(BuildConfig.REL_SERVER_URL);
        if (!url.getProtocol().equalsIgnoreCase(serverUrl.getProtocol())
                || !url.getHost().equalsIgnoreCase(serverUrl.getHost())
                || url.getPort() != serverUrl.getPort()) {
            throw new SecurityException("challenge endpoint must share the configured server origin");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = CookieManager.getInstance().getCookie(BuildConfig.REL_SERVER_URL);
        if (cookie != null && !cookie.trim().isEmpty()) connection.setRequestProperty("Cookie", cookie);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("challenge status " + status);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        String value = new JSONObject(body.toString()).optString("nonce", "").trim();
        if (value.length() < 16 || value.length() > 500) throw new IllegalStateException("invalid challenge nonce");
        return value;
    }

    private static String normalizeAction(String action) {
        if (action == null || action.trim().isEmpty()) return "app_session";
        String value = action.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9_/-]{1,64}") ? value : "app_session";
    }

    private static long parseProjectNumber(String value) {
        try { return Long.parseLong(value == null ? "0" : value.trim()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String nonce() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(random);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ignored) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }
    }
}
