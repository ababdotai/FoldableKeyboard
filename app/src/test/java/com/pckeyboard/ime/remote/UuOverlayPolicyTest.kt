package com.pckeyboard.ime.remote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies gesture bounds and arrow orientation independently of overlay permissions. */
class UuOverlayPolicyTest {
    /** Allows only versions that reject a mismatched target without untargeted retries. */
    @Test
    fun requiresAndroid14ForTargetedInjection() {
        assertFalse(supportsTargetedSystemInput(26))
        assertFalse(supportsTargetedSystemInput(33))
        assertTrue(supportsTargetedSystemInput(34))
        assertTrue(supportsTargetedSystemInput(35))
    }

    /** Prevents zero movement from injecting keys and preserves signed axis directions. */
    @Test
    fun mapsCursorDirection() {
        assertEquals(emptyList<Int>(), overlayCursorKeys(0, 0))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN), overlayCursorKeys(-1, 1))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP), overlayCursorKeys(1, -1))
    }

    /** Caps large gesture bursts before absolute-value conversion can overflow. */
    @Test
    fun capsExtremeCursorDeltas() {
        assertEquals(
            List(8) { KeyEvent.KEYCODE_DPAD_LEFT } + List(8) { KeyEvent.KEYCODE_DPAD_DOWN },
            overlayCursorKeys(Int.MIN_VALUE, Int.MAX_VALUE),
        )
    }
}
