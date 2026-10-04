package com.pckeyboard.ime.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers safe hardware input routing and fail-closed parsing of shell snapshots.
 *
 * Host tests verify matching semantics; Android ICU regex compatibility needs a device probe.
 */
class HidFocusGuardTest {
    /** Accepts both observed window delimiters without requiring optional type metadata. */
    @Test
    fun acceptsActivityFollowedByClosingBraceOrType() {
        assertTrue(isUuHidFocusSafe(WINDOW, INPUT))
        assertTrue(isUuHidFocusSafe(WINDOW.replace(" type=1", ""), INPUT))
    }

    /** Requires the exact UU package and activity rather than accepting shared prefixes. */
    @Test
    fun rejectsPackageAndActivityLookalikes() {
        for (window in listOf(
            WINDOW.replace("com.netease.uuremote/", "com.netease.uuremote.fake/"),
            WINDOW.replace("com.netease.uuremote/", "com.other/"),
            WINDOW.replace("ScreenActivity", "ScreenActivityExtra"),
            WINDOW.replace("ScreenActivity", "ScreenActivity.fake"),
        )) {
            assertFalse(isUuHidFocusSafe(window, INPUT))
            assertFalse(isUuHidFocusSafe(window.replace(" type=1", ""), INPUT))
        }
    }

    /** Allows the observed UU physical keyboard view with an inactive second display. */
    @Test
    fun acceptsOnlyPhysicalKeyboardView() {
        assertTrue(isUuHidFocusSafe("mCurrentFocus=null\n$WINDOW", INPUT))
        assertTrue(isUuHidFocusSafe("mTopFocusedDisplayId=0\n$WINDOW", INPUT))
    }

    /** Rejects text editors, missing views, active input connections, and malformed dumps. */
    @Test
    fun rejectsImeAndUnknownInputState() {
        for (input in listOf("", INPUT.replace("GVDeviceInputView", "InvisibleEditText"),
            INPUT.replace("mServedInputConnection=null", "mServedInputConnection=Editor{}"),
            "$INPUT\n$INPUT")) {
            assertFalse(isUuHidFocusSafe(WINDOW, input))
        }
    }

    /** Rejects other apps, notification shade, multiple focus windows, and locked screens. */
    @Test
    fun rejectsUnsafeWindowState() {
        for (window in listOf("", "mCurrentFocus=null", "$WINDOW\n$WINDOW",
            WINDOW.replace("com.netease.uuremote/", "com.other/"),
            "mCurrentFocus=Window{a u0 NotificationShade}",
            "$WINDOW\nmKeyguardShowing=true", "$WINDOW\nmTopFocusedDisplayId=2")) {
            assertFalse(isUuHidFocusSafe(window, INPUT))
        }
    }

    /** Never reuses a previous safe snapshot when the next query fails or focus moves. */
    @Test
    fun queriesFreshStateAndFailsClosed() {
        var safe = true
        val guard = HidFocusGuard { service -> if (!safe) null else if (service == "window") WINDOW else INPUT }
        assertTrue(guard.isSafe())
        safe = false
        assertFalse(guard.isSafe())
        assertFalse(HidFocusGuard { throw IllegalStateException() }.isSafe())
    }

    companion object {
        internal const val WINDOW = "mCurrentFocus=Window{df8de1 u0 com.netease.uuremote/com.remote.app.ui.activity.ScreenActivity type=1}"
        internal const val INPUT = "mServedView=com.remote.inputdevice.view.GVDeviceInputView{abc}\nmServedInputConnection=null"
    }
}
