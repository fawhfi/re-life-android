package com.relife.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.HapticFeedbackConstants;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
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
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.relife.mobile.offline.OfflineQueueStore;
import com.relife.mobile.offline.SyncScheduler;
import com.relife.mobile.sandbox.SandboxAgent;
import com.relife.mobile.sandbox.SandboxCapability;
import com.relife.mobile.sandbox.SandboxPolicy;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends ComponentActivity {
    private static final String RENDERING_PREFERENCES = "android-rendering";
    private static final String HIGH_END_RENDERING = "high-end";
    private static final int REQUEST_FILE_CHOOSER = 741;
    private static final int REQUEST_GEOLOCATION = 742;
    private static final int REQUEST_AGENT_CAMERA = 743;
    private static final int REQUEST_AGENT_CAMERA_PERMISSION = 744;
    private static final int REQUEST_AGENT_LOCATION_PERMISSION = 745;
    private static final int REQUEST_WEB_CAMERA_PERMISSION = 746;

    private WebView webView;
    private WebAssetInterceptor assetInterceptor;
    private OfflineQueueStore offlineStore;
    private boolean offlinePageShown;
    private boolean mainPageStarted;
    private String pendingFileCallback;
    private String pendingGeolocationOrigin;
    private android.webkit.GeolocationPermissions.Callback pendingGeolocationCallback;
    private final String bridgeToken = UUID.randomUUID().toString();
    private PlayIntegrityClient playIntegrityClient;
    private String pendingAgentCapability;
    private String pendingAgentCameraCallback;
    private AlertDialog pendingAgentDialog;
    private PermissionRequest pendingWebCameraRequest;
    private ScriptHandler documentStartScriptHandler;

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
        loadMainPage(state);
        if (NetworkState.isOnline(this)) {
            playIntegrityClient.requestToken(new PlayIntegrityClient.Callback() {
                @Override public void onToken(String token) {}
                @Override public void onUnavailable(String reason) {}
            });
        }
    }

    private void loadMainPage(Bundle state) {
        if (mainPageStarted || webView == null) return;
        mainPageStarted = true;
        if (state == null) webView.loadUrl(BuildConfig.REL_SERVER_URL + "/");
        else webView.restoreState(state);
    }

    private void configureWebView() {
        assetInterceptor = new WebAssetInterceptor(this, BuildConfig.REL_SERVER_URL);
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        webView.setHapticFeedbackEnabled(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setOffscreenPreRaster(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new RelifeNativeBridge(this), "RelifeNative");
        installDocumentStartBridge();
        webView.setWebViewClient(new RelifeWebViewClient());
        webView.setWebChromeClient(new RelifeChromeClient());
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return intercept(request.getUrl().toString(), request.getMethod());
            }
        });
    }

    private void installDocumentStartBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (documentStartScriptHandler != null) documentStartScriptHandler.remove();
        documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                bridgeScript(),
                Collections.singleton(configuredOrigin())
        );
    }

    private String configuredOrigin() {
        Uri configured = Uri.parse(BuildConfig.REL_SERVER_URL);
        return configured.getScheme() + "://" + configured.getAuthority();
    }

    private void showAgentPermissions() {
        String[] labels = {
                "读取 App 沙箱文件", "写入 App 沙箱文件", "查看设备基本信息",
                "读取当前位置", "启动相机", "打开系统分享", "打开 HTTPS 链接"
        };
        String[] keys = {
                "READ_FILES", "WRITE_FILES", "DEVICE_INFO", "LOCATION", "CAMERA", "SHARE", "OPEN_LINK"
        };
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = SandboxAgent.isGranted(this, keys[i]);
        new AlertDialog.Builder(this)
                .setTitle("手机 Agent 权限")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        changeAgentCapability(keys[which], isChecked))
                .setPositiveButton("完成", null)
                .show();
    }

    private void changeAgentCapability(String capability, boolean granted) {
        if (!granted) {
            SandboxAgent.setGranted(this, capability, false);
            return;
        }
        if ("CAMERA".equals(capability)
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingAgentCapability = capability;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_AGENT_CAMERA_PERMISSION);
            return;
        }
        if ("LOCATION".equals(capability)
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingAgentCapability = capability;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_AGENT_LOCATION_PERMISSION);
            return;
        }
        SandboxAgent.setGranted(this, capability, true);
    }

    private String runDeviceAgent(String command) {
        try {
            JSONObject input = new JSONObject(command == null ? "{}" : command);
            String tool = input.optString("tool", "").trim();
            String callbackId = input.optString("callback_id", "").trim();
            SandboxCapability required = SandboxPolicy.requiredCapability(tool);
            if (required == null) {
                return "{\"error\":\"UNKNOWN_TOOL\"}";
            }
            if (!SandboxAgent.isGranted(this, required.name())) {
                return "{\"error\":\"CAPABILITY_DENIED\",\"required\":\"" + required.name() + "\"}";
            }
            switch (tool) {
                case "current_location" -> {
                    if (!validCallbackId(callbackId)) return "{\"error\":\"CALLBACK_REQUIRED\"}";
                    runOnUiThread(() -> confirmAgentAction(
                            "允许 Agent 读取当前位置？",
                            "位置只会返回当前 ReAgent 请求。",
                            callbackId,
                            tool,
                            () -> readCurrentLocation(callbackId)));
                }
                case "take_photo" -> {
                    if (!validCallbackId(callbackId)) return "{\"error\":\"CALLBACK_REQUIRED\"}";
                    runOnUiThread(() -> confirmAgentAction(
                            "允许 Agent 启动相机？",
                            "你需要在系统相机中亲自拍摄并确认。照片只保存到 App 私有沙箱。",
                            callbackId,
                            tool,
                            () -> launchAgentCamera(callbackId)));
                }
                case "share_text" -> {
                    String text = input.optString("text", "");
                    if (text.isBlank() || text.length() > 10_000) {
                        return "{\"error\":\"INVALID_SHARE_TEXT\"}";
                    } else if (!validCallbackId(callbackId)) {
                        return "{\"error\":\"CALLBACK_REQUIRED\"}";
                    } else {
                        runOnUiThread(() -> confirmAgentAction(
                                "允许 Agent 打开分享面板？", text, callbackId, tool,
                                () -> shareAgentText(callbackId, text)));
                    }
                }
                case "open_url" -> {
                    Uri uri = Uri.parse(input.optString("url", ""));
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                        return "{\"error\":\"HTTPS_URL_REQUIRED\"}";
                    } else if (!validCallbackId(callbackId)) {
                        return "{\"error\":\"CALLBACK_REQUIRED\"}";
                    } else {
                        runOnUiThread(() -> confirmAgentAction(
                                "允许 Agent 打开链接？", uri.toString(), callbackId, tool,
                                () -> openAgentUrl(callbackId, uri)));
                    }
                }
                default -> { return SandboxAgent.execute(this, command); }
            }
            return "{\"status\":\"AWAITING_USER_CONFIRMATION\",\"callback_id\":"
                    + JSONObject.quote(callbackId) + "}";
        } catch (Exception ignored) {
            return "{\"error\":\"INVALID_COMMAND\"}";
        }
    }

    private void confirmAgentAction(
            String title,
            String message,
            String callbackId,
            String tool,
            Runnable action
    ) {
        if (pendingAgentDialog != null && pendingAgentDialog.isShowing()) {
            notifyAgentError(callbackId, "TOOL_BUSY", "另一个 Agent 操作正在等待确认");
            SandboxAgent.auditExternalTool(this, tool, "busy");
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("拒绝", (ignored, which) -> {
                    notifyAgentError(callbackId, "USER_DENIED", "Agent 操作已拒绝");
                    SandboxAgent.auditExternalTool(this, tool, "denied");
                })
                .setPositiveButton("允许一次", (ignored, which) -> action.run())
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (pendingAgentDialog == dialog) pendingAgentDialog = null;
        });
        pendingAgentDialog = dialog;
        dialog.show();
    }

    private void readCurrentLocation(String callbackId) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            notifyAgentError(callbackId, "ANDROID_PERMISSION_DENIED", "Agent 缺少 Android 定位权限");
            return;
        }
        try {
            LocationManager manager = getSystemService(LocationManager.class);
            Location newest = null;
            if (manager != null) {
                for (String provider : manager.getProviders(true)) {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) newest = candidate;
                }
            }
            if (newest == null) {
                notifyAgentError(callbackId, "LOCATION_UNAVAILABLE", "当前位置暂不可用");
                return;
            }
            JSONObject result = new JSONObject();
            result.put("latitude", newest.getLatitude());
            result.put("longitude", newest.getLongitude());
            result.put("accuracy_m", newest.getAccuracy());
            result.put("captured_at", newest.getTime());
            notifyAgentResult(callbackId, result, "已取得当前位置");
            SandboxAgent.auditExternalTool(this, "current_location", "ok");
        } catch (Exception ignored) {
            notifyAgentError(callbackId, "LOCATION_UNAVAILABLE", "当前位置暂不可用");
        }
    }

    private void launchAgentCamera(String callbackId) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            notifyAgentError(callbackId, "ANDROID_PERMISSION_DENIED", "Agent 缺少 Android 相机权限");
            return;
        }
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) {
            notifyAgentError(callbackId, "CAMERA_UNAVAILABLE", "相机不可用");
            return;
        }
        pendingAgentCameraCallback = callbackId;
        try {
            startActivityForResult(camera, REQUEST_AGENT_CAMERA);
        } catch (RuntimeException ignored) {
            pendingAgentCameraCallback = null;
            notifyAgentError(callbackId, "CAMERA_UNAVAILABLE", "相机不可用");
        }
    }

    private void saveAgentPhoto(String callbackId, Intent data) {
        Object raw = data == null || data.getExtras() == null ? null : data.getExtras().get("data");
        if (!(raw instanceof Bitmap bitmap)) {
            notifyAgentError(callbackId, "PHOTO_UNAVAILABLE", "没有取得照片");
            return;
        }
        File directory = new File(getFilesDir(), "sandbox");
        if (!directory.exists() && !directory.mkdirs()) {
            notifyAgentError(callbackId, "PHOTO_SAVE_FAILED", "照片保存失败");
            return;
        }
        File photo = new File(directory, "agent-photo-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(photo)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new IOException("compress failed");
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("sandbox_file", photo.getName());
            notifyAgentResult(callbackId, result, "照片已保存到 Agent 沙箱：" + photo.getName());
            SandboxAgent.auditExternalTool(this, "take_photo", "ok");
        } catch (Exception ignored) {
            notifyAgentError(callbackId, "PHOTO_SAVE_FAILED", "照片保存失败");
        }
    }

    private void shareAgentText(String callbackId, String text) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(share, "选择分享方式"));
            notifyAgentResult(callbackId, successResult("opened"), "已打开系统分享面板");
            SandboxAgent.auditExternalTool(this, "share_text", "opened");
        } catch (RuntimeException ignored) {
            notifyAgentError(callbackId, "SHARE_UNAVAILABLE", "系统分享不可用");
            SandboxAgent.auditExternalTool(this, "share_text", "error");
        }
    }

    private void openAgentUrl(String callbackId, Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            notifyAgentResult(callbackId, successResult("opened"), "已打开链接");
            SandboxAgent.auditExternalTool(this, "open_url", "opened");
        } catch (RuntimeException ignored) {
            notifyAgentError(callbackId, "LINK_UNAVAILABLE", "没有可打开该链接的 App");
            SandboxAgent.auditExternalTool(this, "open_url", "error");
        }
    }

    private void notifyAgentError(String callbackId, String code, String toastMessage) {
        JSONObject result = new JSONObject();
        try {
            result.put("error", code);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        notifyAgentResult(callbackId, result, toastMessage);
    }

    private JSONObject successResult(String status) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("status", status);
            return result;
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void notifyAgentResult(String callbackId, JSONObject result, String toastMessage) {
        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, toastMessage, android.widget.Toast.LENGTH_LONG).show();
            if (!validCallbackId(callbackId) || webView == null) return;
            String safeResult = result.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            String script = "window.__RELIFE_NATIVE_AGENT_RESULT__ && "
                    + "window.__RELIFE_NATIVE_AGENT_RESULT__(" + JSONObject.quote(callbackId) + "," + safeResult + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private static boolean validCallbackId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,128}");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        webView.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "document.documentElement?.classList.add('relife-backgrounded');"
                            + OfflineBridgeScript.STOP_MEDIA_SCRIPT,
                    null
            );
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.resumeTimers();
            webView.onResume();
            webView.evaluateJavascript(
                    "document.documentElement?.classList.remove('relife-backgrounded');",
                    null
            );
        }
    }

    @Override protected void onDestroy() {
        denyPendingWebCameraRequest();
        if (pendingAgentDialog != null) pendingAgentDialog.dismiss();
        if (documentStartScriptHandler != null) {
            documentStartScriptHandler.remove();
            documentStartScriptHandler = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("RelifeNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void showOfflinePage() {
        if (offlinePageShown) return;
        offlinePageShown = true;
        try {
            String html = OfflineHtmlComposer.compose(
                    readAsset("web/templates/index.html"),
                    readAsset("web/static/style.css"),
                    readAsset("web/static/css/theme.css"),
                    bridgeScript()
            );
            webView.loadDataWithBaseURL(BuildConfig.REL_SERVER_URL, html, "text/html", "UTF-8", BuildConfig.REL_SERVER_URL + "/");
        } catch (IOException ignored) {
            webView.loadData("<h1>Re-Life 暂时无法连接</h1><p>请恢复网络后重试。</p>", "text/html", "UTF-8");
        }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream stream = getAssets().open(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) output.write(chunk, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private WebResourceResponse intercept(String urlString, String method) {
        return assetInterceptor.intercept(urlString, method);
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
        view.evaluateJavascript(bridgeScript(), null);
    }

    private String bridgeScript() {
        boolean highEnd = getSharedPreferences(RENDERING_PREFERENCES, MODE_PRIVATE)
                .getBoolean(HIGH_END_RENDERING, false);
        return OfflineBridgeScript.SCRIPT
                .replace("__RELIFE_BRIDGE_TOKEN__", bridgeToken)
                .replace("__RELIFE_HIGH_END__", Boolean.toString(highEnd));
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
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_GEOLOCATION);
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handleWebPermissionRequest(request));
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            runOnUiThread(() -> {
                if (pendingWebCameraRequest == request) pendingWebCameraRequest = null;
            });
        }

        @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
            pendingFileCallback = "callback";
            Intent intent = params.createIntent();
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(intent, REQUEST_FILE_CHOOSER);
            FileCallbackHolder.callback = callback;
            return true;
        }
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (!WebPermissionPolicy.canGrantCamera(
                BuildConfig.REL_SERVER_URL,
                request.getOrigin().toString(),
                request.getResources())) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            return;
        }
        denyPendingWebCameraRequest();
        pendingWebCameraRequest = request;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_WEB_CAMERA_PERMISSION);
    }

    private void denyPendingWebCameraRequest() {
        PermissionRequest request = pendingWebCameraRequest;
        pendingWebCameraRequest = null;
        if (request != null) request.deny();
    }

    private static final class FileCallbackHolder { static android.webkit.ValueCallback<Uri[]> callback; }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AGENT_CAMERA) {
            String callbackId = pendingAgentCameraCallback;
            pendingAgentCameraCallback = null;
            if (resultCode == RESULT_OK) saveAgentPhoto(callbackId, data);
            else notifyAgentError(callbackId, "USER_CANCELLED", "已取消拍照");
            return;
        }
        if (requestCode != REQUEST_FILE_CHOOSER || FileCallbackHolder.callback == null) return;
        Uri result = resultCode == RESULT_OK && data != null ? data.getData() : null;
        FileCallbackHolder.callback.onReceiveValue(result == null ? null : new Uri[]{result});
        FileCallbackHolder.callback = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WEB_CAMERA_PERMISSION) {
            PermissionRequest request = pendingWebCameraRequest;
            pendingWebCameraRequest = null;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (request != null) {
                if (granted && WebPermissionPolicy.canGrantCamera(
                        BuildConfig.REL_SERVER_URL,
                        request.getOrigin().toString(),
                        request.getResources())) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    request.deny();
                }
            }
            return;
        }
        if (requestCode == REQUEST_AGENT_CAMERA_PERMISSION || requestCode == REQUEST_AGENT_LOCATION_PERMISSION) {
            boolean granted = false;
            for (int grantResult : grantResults) granted |= grantResult == PackageManager.PERMISSION_GRANTED;
            if (pendingAgentCapability != null) {
                SandboxAgent.setGranted(this, pendingAgentCapability, granted);
                android.widget.Toast.makeText(
                        this,
                        granted ? "Agent 权限已开启" : "Android 系统权限未授予",
                        android.widget.Toast.LENGTH_LONG
                ).show();
            }
            pendingAgentCapability = null;
            return;
        }
        if (requestCode != REQUEST_GEOLOCATION || pendingGeolocationCallback == null) return;
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
        @JavascriptInterface public String agent(String token, String command) { return authorized(token) ? runDeviceAgent(command) : "{\"error\":\"BRIDGE_UNAUTHORIZED\"}"; }
        @JavascriptInterface public boolean openAgentPermissions(String token) {
            if (!authorized(token)) return false;
            runOnUiThread(MainActivity.this::showAgentPermissions);
            return true;
        }
        @JavascriptInterface public boolean setHighEndRendering(String token, boolean enabled) {
            if (!authorized(token)) return false;
            boolean changed = getSharedPreferences(RENDERING_PREFERENCES, MODE_PRIVATE)
                    .getBoolean(HIGH_END_RENDERING, false) != enabled;
            getSharedPreferences(RENDERING_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putBoolean(HIGH_END_RENDERING, enabled)
                    .apply();
            if (changed) runOnUiThread(() -> {
                if (webView == null) return;
                installDocumentStartBridge();
                if (offlinePageShown) {
                    offlinePageShown = false;
                    showOfflinePage();
                } else {
                    webView.reload();
                }
            });
            return true;
        }
        /** Performs one system-respecting tap haptic for an authorized app page. */
        @JavascriptInterface public void tapFeedback(String token) {
            if (!authorized(token)) return;
            runOnUiThread(() -> {
                if (webView != null) webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            });
        }

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
