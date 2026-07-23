package com.relife.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OfflineBridgeScriptTest {
    @Test
    public void androidTouchHighlightIsRemovedWithoutHidingKeyboardFocus() {
        assertTrue(OfflineBridgeScript.SCRIPT.contains("-webkit-tap-highlight-color: transparent"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains(":focus:not(:focus-visible)"));
    }

    @Test
    public void nativeAgentResultsUseRequestIdCallbacks() {
        assertTrue(OfflineBridgeScript.SCRIPT.contains("__RELIFE_NATIVE_AGENT_RESULT__"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("callback_id"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("get_user_location"));
    }

    @Test
    public void androidRenderingProfileIsAppliedBeforeWebAppScriptsRun() {
        int profile = OfflineBridgeScript.SCRIPT.indexOf("const highEnd = __RELIFE_HIGH_END__");
        int bridgeGuard = OfflineBridgeScript.SCRIPT.indexOf("__RELIFE_NATIVE_BRIDGE__");
        assertTrue(profile >= 0);
        assertTrue(profile < bridgeGuard);
        assertTrue(OfflineBridgeScript.SCRIPT.contains("root.classList.toggle('perf-lite', !highEnd)"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("root.classList.toggle('relife-android-high-end', highEnd)"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("androidLiteStyle.disabled = highEnd"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("document.addEventListener('DOMContentLoaded', clearLiteProfile"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("Object.defineProperty(window, 'RELIFE_PERF'"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("motionEnabled: !reportedReducedMotion"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("backdrop-filter: none !important"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("content-visibility: auto"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains(
                "background-color: var(--color-white, #ffffff) !important"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("html.relife-android .weather-panel"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("html.relife-android .empty-state-svg"));
    }

    @Test
    public void morePageProvidesNativeHighQualitySwitch() {
        assertTrue(OfflineBridgeScript.SCRIPT.contains("relife-high-end-row"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("className = 'ai-switch'"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("nativeBridge.setHighEndRendering"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("High quality"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("高画质模式"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("高畫質模式"));
    }

    @Test
    public void onlineRewardDataIsNotReducedToProfileFields() {
        int online = OfflineBridgeScript.SCRIPT.indexOf("if (!isOffline())");
        int originalSave = OfflineBridgeScript.SCRIPT.indexOf("originalSave(data)", online);
        int profileOnly = OfflineBridgeScript.SCRIPT.indexOf("profileOnly(data)", online);
        assertTrue(originalSave > online);
        assertTrue(profileOnly > originalSave);
    }

    @Test
    public void unavailablePlayIntegrityNeverBlocksRewardRequest() {
        assertFalse(OfflineBridgeScript.SCRIPT.contains("INTEGRITY_REQUIRED"));
        assertFalse(OfflineBridgeScript.SCRIPT.contains("await waitForIntegrity"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("refreshPlayIntegrity?.(bridgeToken, protectedAction)"));
    }

    @Test
    public void backgroundLifecycleCanStopActiveCameraTracks() {
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.contains("getTracks()"));
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.contains("track.stop()"));
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.indexOf("const activeStreams")
                < OfflineBridgeScript.STOP_MEDIA_SCRIPT.indexOf("closeCamera()"));
    }
}
