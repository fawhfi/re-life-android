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
}
