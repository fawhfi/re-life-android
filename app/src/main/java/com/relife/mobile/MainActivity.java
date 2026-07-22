package com.relife.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.relife.mobile.offline.OfflineQueueStore;
import com.relife.mobile.offline.SyncScheduler;
import com.relife.mobile.sandbox.SandboxAgent;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends ComponentActivity {
    private WebView webView;
    private OfflineQueueStore offlineStore;
    private boolean offlinePageShown;
    private boolean mainPageStarted;
    private String pendingFileCallback;
    private String pendingGeolocationOrigin;
    private android.webkit.GeolocationPermissions.Callback pendingGeolocationCallback;
    private final String bridgeToken = UUID.randomUUID().toString();
    private PlayIntegrityClient playIntegrityClient;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        offlineStore = new OfflineQueueStore(this);
        webView = new WebView(this);
        setContentView(webView);
        configureWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        playIntegrityClient = new PlayIntegrityClient(this);
        if (NetworkState.isOnline(this)) {
            playIntegrityClient.requestToken(new PlayIntegrityClient.Callback() {
                @Override public void onToken(String token) { loadMainPage(state); }
                @Override public void onUnavailable(String reason) { loadMainPage(state); }
            });
        } else {
            loadMainPage(state);
        }
    }

    private void loadMainPage(Bundle state) {
        if (mainPageStarted || webView == null) return;
        mainPageStarted = true;
        if (state == null) webView.loadUrl(BuildConfig.REL_SERVER_URL + "/");
        else webView.restoreState(state);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new RelifeNativeBridge(this), "RelifeNative");
        webView.setWebViewClient(new RelifeWebViewClient());
        webView.setWebChromeClient(new RelifeChromeClient());
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return intercept(request.getUrl().toString(), request.getMethod());
            }
        });
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Agent 權限").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if ("Agent 權限".contentEquals(item.getTitle())) {
            showAgentPermissions();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAgentPermissions() {
        String[] labels = {"讀取 App 沙箱檔案", "寫入 App 沙箱檔案", "查看裝置基本資訊"};
        String[] keys = {"READ_FILES", "WRITE_FILES", "DEVICE_INFO"};
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = SandboxAgent.isGranted(this, keys[i]);
        new AlertDialog.Builder(this)
                .setTitle("手機端 Agent 權限")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        SandboxAgent.setGranted(this, keys[which], isChecked))
                .setMessage("Agent 永遠只能存取本 App 私有 sandbox 目錄；不提供 shell、通訊錄或其他 App 資料權限。")
                .setPositiveButton("完成", null)
                .show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    private void showOfflinePage() {
        if (offlinePageShown) return;
        offlinePageShown = true;
        try (InputStream stream = getAssets().open("templates/index.html")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) output.write(chunk, 0, count);
            byte[] bytes = output.toByteArray();
            String html = new String(bytes, StandardCharsets.UTF_8);
            html = html.replace("</head>", "<script>" + OfflineBridgeScript.SCRIPT.replace("__RELIFE_BRIDGE_TOKEN__", bridgeToken) + "</script></head>");
            webView.loadDataWithBaseURL(BuildConfig.REL_SERVER_URL, html, "text/html", "UTF-8", BuildConfig.REL_SERVER_URL + "/");
        } catch (IOException ignored) {
            webView.loadData("<h1>Re-Life 暫時無法連線</h1><p>請恢復網路後重試。</p>", "text/html", "UTF-8");
        }
    }

    private WebResourceResponse intercept(String urlString, String method) {
        Uri uri = Uri.parse(urlString);
        Uri configured = Uri.parse(BuildConfig.REL_SERVER_URL);
        if (uri.getHost() != null && !String.valueOf(configured.getHost()).equalsIgnoreCase(uri.getHost())) return null;
        if (!"GET".equalsIgnoreCase(method)) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/static/")) return null;
        String assetPath = path.substring(1);
        try {
            InputStream stream = getAssets().open(assetPath);
            return new WebResourceResponse(mimeFor(assetPath), "UTF-8", stream);
        } catch (IOException ignored) { return null; }
    }

    private static String mimeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private final class RelifeWebViewClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl().toString(), request.getMethod());
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String configuredOrigin = Uri.parse(BuildConfig.REL_SERVER_URL).getScheme() + "://" + Uri.parse(BuildConfig.REL_SERVER_URL).getAuthority();
            String requestOrigin = uri.getScheme() + "://" + uri.getAuthority();
            if (configuredOrigin.equalsIgnoreCase(requestOrigin) || uri.getHost() == null) return false;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        }

        @Override public void onPageFinished(WebView view, String url) {
            offlinePageShown = url.startsWith("file:") || url.startsWith("data:");
            if (isTrustedPage(url)) installOfflineBridge(view);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showOfflinePage();
        }
    }

    private void installOfflineBridge(WebView view) {
        String script = OfflineBridgeScript.SCRIPT.replace("__RELIFE_BRIDGE_TOKEN__", bridgeToken);
        view.evaluateJavascript(script, null);
    }

    private boolean isTrustedPage(String url) {
        if (url == null) return false;
        if (url.startsWith("data:")) return true;
        Uri candidate = Uri.parse(url);
        Uri configured = Uri.parse(BuildConfig.REL_SERVER_URL);
        return String.valueOf(candidate.getScheme()).equalsIgnoreCase(String.valueOf(configured.getScheme()))
                && String.valueOf(candidate.getAuthority()).equalsIgnoreCase(String.valueOf(configured.getAuthority()));
    }

    private final class RelifeChromeClient extends WebChromeClient {
        @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeolocationOrigin = origin;
            pendingGeolocationCallback = callback;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 742);
        }

        @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
            pendingFileCallback = "callback";
            Intent intent = params.createIntent();
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(intent, 741);
            FileCallbackHolder.callback = callback;
            return true;
        }
    }

    private static final class FileCallbackHolder { static android.webkit.ValueCallback<Uri[]> callback; }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 741 || FileCallbackHolder.callback == null) return;
        Uri result = resultCode == RESULT_OK && data != null ? data.getData() : null;
        FileCallbackHolder.callback.onReceiveValue(result == null ? null : new Uri[]{result});
        FileCallbackHolder.callback = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 742 || pendingGeolocationCallback == null) return;
        boolean granted = false;
        for (int grantResult : grantResults) granted |= grantResult == PackageManager.PERMISSION_GRANTED;
        pendingGeolocationCallback.invoke(pendingGeolocationOrigin, granted, false);
        pendingGeolocationOrigin = null;
        pendingGeolocationCallback = null;
    }

    public final class RelifeNativeBridge {
        private final Context context;
        RelifeNativeBridge(Context context) { this.context = context.getApplicationContext(); }
        private boolean authorized(String candidate) { return bridgeToken.equals(candidate); }

        @JavascriptInterface public boolean isOnline(String token) { return authorized(token) && NetworkState.isOnline(context); }
        @JavascriptInterface public String integrityHeader(String token) { return authorized(token) ? AppIntegrity.header(context) : ""; }
        @JavascriptInterface public String playIntegrityToken(String token) { return authorized(token) ? playIntegrityClient.currentToken() : ""; }
        @JavascriptInterface public String playIntegrityNonce(String token) { return authorized(token) ? playIntegrityClient.currentNonce() : ""; }
        @JavascriptInterface public String playIntegrityAction(String token) { return authorized(token) ? playIntegrityClient.currentAction() : ""; }
        @JavascriptInterface public boolean refreshPlayIntegrity(String token, String action) {
            if (!authorized(token)) return false;
            String normalized = action == null ? "" : action.trim();
            if (!("reward_redeem".equals(normalized) || "prove_swap".equals(normalized))) return false;
            playIntegrityClient.requestToken(normalized, new PlayIntegrityClient.Callback() {
                @Override public void onToken(String ignored) {}
                @Override public void onUnavailable(String ignored) {}
            });
            return true;
        }
        @JavascriptInterface public String getCookie(String token) { return authorized(token) ? CookieManager.getInstance().getCookie(BuildConfig.REL_SERVER_URL) : ""; }
        @JavascriptInterface public String enqueueMutation(String token, String method, String path, String body) {
            if (!authorized(token)) return "";
            if (!com.relife.mobile.offline.OfflinePolicy.canQueue(method, path)) return "";
            if ("PATCH".equalsIgnoreCase(method) && "/api/users/me".equals(path)) {
                body = sanitizeProfileBody(body);
            }
            if (body != null && body.length() > 3_000_000) return "";
            String id = offlineStore.enqueue(method, path, body, getCookie(token));
            SyncScheduler.runSoon(context);
            return id;
        }
        @JavascriptInterface public void clearOfflineData(String token) { if (authorized(token)) offlineStore.clear(); }
        @JavascriptInterface public void syncNow(String token) { if (authorized(token)) SyncScheduler.runSoon(context); }
        @JavascriptInterface public void cachePut(String token, String key, String body) {
            if (authorized(token) && key != null && body != null && body.length() < 2_000_000) {
                offlineStore.cachePut(cacheKey(token, key), body);
            }
        }
        @JavascriptInterface public String cacheGet(String token, String key) {
            return authorized(token) && key != null ? offlineStore.cacheGet(cacheKey(token, key)) : null;
        }
        @JavascriptInterface public String agent(String token, String command) { return authorized(token) ? SandboxAgent.execute(context, command) : "{\"error\":\"BRIDGE_UNAUTHORIZED\"}"; }

        private String sanitizeProfileBody(String body) {
            try {
                JSONObject input = new JSONObject(body == null ? "{}" : body);
                JSONObject output = new JSONObject();
                copyString(input, output, "photo_url");
                copyString(input, output, "photoUrl");
                return output.toString();
            } catch (JSONException ignored) {
                return "";
            }
        }

        private void copyString(JSONObject input, JSONObject output, String key) throws JSONException {
            if (input.has(key) && !input.isNull(key)) {
                String value = input.optString(key, "");
                if (value.length() <= 2_000) output.put(key, value);
            }
        }

        private String cacheKey(String token, String key) {
            String cookie = CookieManager.getInstance().getCookie(BuildConfig.REL_SERVER_URL);
            return "session:" + sha256(cookie == null ? "" : cookie) + "/" + key;
        }

        private String sha256(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(digest.length * 2);
                for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
                return result.toString();
            } catch (Exception ignored) {
                return "unavailable";
            }
        }
    }
}
