package com.relife.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class MainActivityRenderingTest {
    @Test
    public void highEndRenderingRequiresBridgeTokenAndPersists() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("android-rendering", Context.MODE_PRIVATE).edit().clear().commit();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Boolean> unauthorized = new AtomicReference<>();
            AtomicReference<Boolean> authorized = new AtomicReference<>();
            AtomicReference<String> updatedScript = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    Field tokenField = MainActivity.class.getDeclaredField("bridgeToken");
                    tokenField.setAccessible(true);
                    String token = (String) tokenField.get(activity);
                    MainActivity.RelifeNativeBridge bridge = activity.new RelifeNativeBridge(activity);
                    unauthorized.set(bridge.setHighEndRendering("wrong-token", true));
                    authorized.set(bridge.setHighEndRendering(token, true));

                    Method scriptMethod = MainActivity.class.getDeclaredMethod("bridgeScript");
                    scriptMethod.setAccessible(true);
                    updatedScript.set((String) scriptMethod.invoke(activity));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });

            assertFalse(unauthorized.get());
            assertTrue(authorized.get());
            assertTrue(context.getSharedPreferences("android-rendering", Context.MODE_PRIVATE)
                    .getBoolean("high-end", false));
            assertTrue(updatedScript.get().contains("const highEnd = true"));
        } finally {
            context.getSharedPreferences("android-rendering", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test
    public void webViewUsesHardwareAcceleratedRendering() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field webViewField = MainActivity.class.getDeclaredField("webView");
                    webViewField.setAccessible(true);
                    WebView webView = (WebView) webViewField.get(activity);
                    assertTrue(activity.getWindow().getDecorView().isHardwareAccelerated());
                    assertTrue(webView.isHardwareAccelerated());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test
    public void renderingChangeRebuildsBundledOfflinePage() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("android-rendering", Context.MODE_PRIVATE).edit().clear().commit();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> webView = new AtomicReference<>();
            CountDownLatch testPageReady = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                try {
                    Field webViewField = MainActivity.class.getDeclaredField("webView");
                    webViewField.setAccessible(true);
                    WebView view = (WebView) webViewField.get(activity);
                    webView.set(view);

                    Field offlineField = MainActivity.class.getDeclaredField("offlinePageShown");
                    offlineField.setAccessible(true);
                    offlineField.setBoolean(activity, true);
                    view.loadDataWithBaseURL(
                            BuildConfig.REL_SERVER_URL,
                            "<html><body>offline fixture</body></html>",
                            "text/html",
                            "UTF-8",
                            null
                    );
                    view.postDelayed(() -> {
                        try {
                            offlineField.setBoolean(activity, true);
                            Field tokenField = MainActivity.class.getDeclaredField("bridgeToken");
                            tokenField.setAccessible(true);
                            String token = (String) tokenField.get(activity);
                            activity.new RelifeNativeBridge(activity)
                                    .setHighEndRendering(token, true);
                            testPageReady.countDown();
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError(error);
                        }
                    }, 300);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            assertTrue(testPageReady.await(3, TimeUnit.SECONDS));
            assertTrue(waitForJavascript(webView.get(),
                    "document.getElementById('tab-more') !== null ? true : JSON.stringify({"
                            + "ready:document.readyState,title:document.title,url:location.href,"
                            + "length:document.documentElement?.outerHTML.length})"));
            assertTrue(waitForJavascript(webView.get(),
                    "window.matchMedia('(prefers-reduced-motion: reduce)').matches "
                            + "? window.RELIFE_PERF?.motionEnabled === false "
                            + ": window.RELIFE_PERF?.motionEnabled === true"));
        } finally {
            context.getSharedPreferences("android-rendering", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private static boolean waitForJavascript(WebView webView, String expression) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String lastValue = null;
        while (System.currentTimeMillis() < deadline) {
            CountDownLatch evaluated = new CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    webView.evaluateJavascript(expression, result -> {
                        value.set(result);
                        evaluated.countDown();
                    }));
            assertTrue(evaluated.await(2, TimeUnit.SECONDS));
            lastValue = value.get();
            if ("true".equals(lastValue)) return true;
            Thread.sleep(50);
        }
        throw new AssertionError("JavaScript did not become true; last value=" + lastValue);
    }
}
