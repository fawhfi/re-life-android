package com.relife.mobile;

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
        int profile = OfflineBridgeScript.SCRIPT.indexOf("classList.add('relife-android', 'perf-lite')");
        int bridgeGuard = OfflineBridgeScript.SCRIPT.indexOf("__RELIFE_NATIVE_BRIDGE__");
        assertTrue(profile >= 0);
        assertTrue(profile < bridgeGuard);
        assertTrue(OfflineBridgeScript.SCRIPT.contains("backdrop-filter: none !important"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("content-visibility: auto"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains(
                "background-color: var(--color-white, #ffffff) !important"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("html.relife-android .weather-panel"));
        assertTrue(OfflineBridgeScript.SCRIPT.contains("html.relife-android .empty-state-svg"));
    }

    @Test
    public void backgroundLifecycleCanStopActiveCameraTracks() {
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.contains("getTracks()"));
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.contains("track.stop()"));
        assertTrue(OfflineBridgeScript.STOP_MEDIA_SCRIPT.indexOf("const activeStreams")
                < OfflineBridgeScript.STOP_MEDIA_SCRIPT.indexOf("closeCamera()"));
    }
}
