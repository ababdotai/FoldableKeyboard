package com.pckeyboard.ime.remote

import android.view.KeyEvent
import kotlin.math.abs

/** Retains the existing Android 14+ rollout floor; HID availability is checked separately. */
internal fun supportsTargetedSystemInput(sdkInt: Int): Boolean = sdkInt >= 34

/** Bounds cursor gestures to sixteen events without risking overflow on extreme deltas. */
internal fun overlayCursorKeys(dx: Int, dy: Int): List<Int> = buildList {
    val horizontal = dx.coerceIn(-8, 8)
    val vertical = dy.coerceIn(-8, 8)
    repeat(abs(horizontal)) {
        add(if (horizontal < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }
    repeat(abs(vertical)) {
        add(if (vertical < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
    }
}
