package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyEventSpec
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies injection failures release keys without duplicating character-producing downs. */
class SystemInputPlanTest {
    private val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

    /** Leaves a successful physical shortcut untouched with no extra cleanup events. */
    @Test
    fun completeShortcutHasNoExtraEvents() {
        val plan = rawKeyEventPlan(KeyEvent.KEYCODE_C, meta)
        val attempted = mutableListOf<RawKeyEventSpec>()

        val result = injectKeyPlan(plan) { attempted.add(it); true }

        assertEquals(plan.size, result)
        assertEquals(plan, attempted)
    }

    /** Releases an uncertain character-down and its modifier without replaying either down. */
    @Test
    fun rejectedCharacterDownReleasesKeyAndModifier() {
        val attempted = mutableListOf<RawKeyEventSpec>()
        val result = injectKeyPlan(rawKeyEventPlan(KeyEvent.KEYCODE_C, meta)) {
            attempted.add(it)
            !(it.keyCode == KeyEvent.KEYCODE_C && it.action == KeyEvent.ACTION_DOWN)
        }

        assertEquals(1, result)
        assertEquals(
            listOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_META_LEFT),
            attempted.map { it.keyCode },
        )
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_UP),
            attempted.map { it.action },
        )
        assertEquals(listOf(0, 0), attempted.takeLast(2).map { it.metaState })
    }

    /** Treats a transport exception as an uncertain delivery and only attempts key-up cleanup. */
    @Test
    fun injectionExceptionDoesNotReplayDown() {
        val attempted = mutableListOf<RawKeyEventSpec>()
        val result = injectKeyPlan(rawKeyEventPlan(KeyEvent.KEYCODE_N, 0)) {
            attempted.add(it)
            if (it.action == KeyEvent.ACTION_DOWN) throw IllegalStateException("lost Binder reply")
            true
        }

        assertEquals(0, result)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), attempted.map { it.action })
    }

    /** Continues modifier cleanup even when an earlier cleanup release also fails. */
    @Test
    fun cleanupFailureDoesNotPreventRemainingReleases() {
        val attempted = mutableListOf<RawKeyEventSpec>()
        val result = injectKeyPlan(rawKeyEventPlan(KeyEvent.KEYCODE_C, meta)) {
            attempted.add(it)
            if (it.keyCode == KeyEvent.KEYCODE_C) throw IllegalStateException("injection failed")
            true
        }

        assertEquals(1, result)
        assertEquals(KeyEvent.KEYCODE_META_LEFT, attempted.last().keyCode)
        assertEquals(KeyEvent.ACTION_UP, attempted.last().action)
        assertEquals(1, attempted.count { it.keyCode == KeyEvent.KEYCODE_C && it.action == KeyEvent.ACTION_DOWN })
    }
}
