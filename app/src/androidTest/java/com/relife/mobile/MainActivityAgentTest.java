package com.relife.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.relife.mobile.sandbox.SandboxAgent;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class MainActivityAgentTest {
    @Test
    public void deniedSensitiveActionCompletesItsWebViewCallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("agent-permissions", Context.MODE_PRIVATE).edit().clear().commit();
        SandboxAgent.setGranted(context, "SHARE", true);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> webView = new AtomicReference<>();
            CountDownLatch pageReady = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("webView");
                    field.setAccessible(true);
                    WebView view = (WebView) field.get(activity);
                    webView.set(view);
                    view.loadDataWithBaseURL(
                            BuildConfig.REL_SERVER_URL,
                            "<html><head><title>ready</title></head><body><script>"
                                    + "window.__RELIFE_NATIVE_BRIDGE__=true;"
                                    + "window.__RELIFE_NATIVE_AGENT_RESULT__=(id,result)=>{document.title=id+':' + result.error};"
                                    + "</script></body></html>",
                            "text/html",
                            "UTF-8",
                            null
                    );
                    view.postDelayed(pageReady::countDown, 500);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            assertTrue(pageReady.await(5, TimeUnit.SECONDS));

            AtomicReference<String> dispatchResult = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    Method method = MainActivity.class.getDeclaredMethod("runDeviceAgent", String.class);
                    method.setAccessible(true);
                    dispatchResult.set((String) method.invoke(
                            activity,
                            "{\"tool\":\"share_text\",\"text\":\"QA\",\"callback_id\":\"test_callback_123\"}"
                    ));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });

            assertTrue(dispatchResult.get().contains("AWAITING_USER_CONFIRMATION"));
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("pendingAgentDialog");
                    field.setAccessible(true);
                    AlertDialog dialog = (AlertDialog) field.get(activity);
                    assertTrue(dialog.isShowing());
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            assertEquals("\"test_callback_123:USER_DENIED\"", waitForTitle(webView.get()));
        } finally {
            context.getSharedPreferences("agent-permissions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private static String waitForTitle(WebView webView) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String title = null;
        while (System.currentTimeMillis() < deadline) {
            CountDownLatch evaluated = new CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    webView.evaluateJavascript("document.title", result -> {
                        value.set(result);
                        evaluated.countDown();
                    }));
            assertTrue(evaluated.await(2, TimeUnit.SECONDS));
            title = value.get();
            if ("\"test_callback_123:USER_DENIED\"".equals(title)) return title;
            Thread.sleep(50);
        }
        return title;
    }
}
