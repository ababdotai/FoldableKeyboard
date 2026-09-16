package com.pckeyboard.ime.dispatch

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies raw shortcut event ordering without Android framework event construction. */
class RawKeyDispatcherTest {

    /** Verifies Command+C is framed by a physical Meta press and release. */
    @Test
    fun plansMetaCShortcut() {
        val metaState = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

        assertEquals(
            listOf(
                RawKeyEventSpec(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, metaState),
                RawKeyEventSpec(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, metaState),
                RawKeyEventSpec(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, metaState),
                RawKeyEventSpec(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0),
            ),
            rawKeyEventPlan(KeyEvent.KEYCODE_C, metaState),
        )
    }

    /** Verifies multiple modifiers release in reverse order and retain the combined meta state. */
    @Test
    fun plansMultipleModifiersWithReverseRelease() {
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

        val plan = rawKeyEventPlan(KeyEvent.KEYCODE_TAB, metaState)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
            ),
            plan.map { it.keyCode },
        )
        assertEquals(
            listOf(
                KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_DOWN,
                KeyEvent.ACTION_UP,
                KeyEvent.ACTION_UP,
                KeyEvent.ACTION_UP,
                KeyEvent.ACTION_UP,
                KeyEvent.ACTION_UP,
            ),
            plan.map { it.action },
        )
        assertEquals(
            listOf(
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                    KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
                metaState,
                metaState,
                metaState,
                metaState and (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON).inv(),
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                0,
            ),
            plan.map { it.metaState },
        )
    }

    /** Verifies a first-event rejection stops the sequence with zero accepted events. */
    @Test
    fun stopsAfterFirstEventFailure() {
        val attempted = mutableListOf<Int>()
        val events = listOf(0, 1, 2, 3)

        val result = dispatchUntilRejected(
            events = events,
            send = { event -> attempted.add(event); false },
        )

        assertEquals(RawDispatchResult(0, 4), result)
        assertFalse(result.fullyAccepted)
        assertEquals(listOf(0), attempted)
    }

    /** Verifies a partial rejection stops without replaying the accepted prefix. */
    @Test
    fun stopsAfterPartialAcceptance() {
        val attempted = mutableListOf<Int>()
        val events = listOf(0, 1, 2, 3)

        val result = dispatchUntilRejected(
            events = events,
            send = { event -> attempted.add(event); event < 2 },
        )

        assertEquals(RawDispatchResult(2, 4), result)
        assertFalse(result.fullyAccepted)
        assertEquals(listOf(0, 1, 2), attempted)
    }

    /** Verifies a runtime transport failure is reported without attempting later events. */
    @Test
    fun stopsAfterRuntimeException() {
        val attempted = mutableListOf<Int>()

        val result = dispatchUntilRejected(listOf(0, 1, 2)) { event ->
            attempted.add(event)
            if (event == 1) throw IllegalStateException("connection closed")
            true
        }

        assertEquals(RawDispatchResult(1, 3), result)
        assertEquals(listOf(0, 1), attempted)
    }
}
