package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyEventSpec
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import org.junit.Assert.*
import org.junit.Test

/** Covers boot keyboard encoding without depending on a privileged Android subprocess. */
class HidKeyReportTest {
    /** Uses HID usages rather than Android key codes or Linux scan codes. */
    @Test fun lettersDigitsAndNavigationHaveKeyboardPageUsages() {
        assertEquals(4, hidUsage(KeyEvent.KEYCODE_A))
        assertEquals(29, hidUsage(KeyEvent.KEYCODE_Z))
        assertEquals(30, hidUsage(KeyEvent.KEYCODE_1))
        assertEquals(39, hidUsage(KeyEvent.KEYCODE_0))
        assertEquals(69, hidUsage(KeyEvent.KEYCODE_F12))
        assertEquals(82, hidUsage(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(75, hidUsage(KeyEvent.KEYCODE_PAGE_UP))
        assertNull(hidUsage(KeyEvent.KEYCODE_FUNCTION))
    }

    /** Releases the primary key before the right-sided modifier and always ends neutral. */
    @Test fun rightControlShortcutPreservesOrderAndSide() {
        val reports = mutableListOf<List<Int>>()
        val plan = rawKeyEventPlan(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON)
        assertEquals(4, sendHidReports(plan, { true }, reports::add))
        assertEquals(listOf(16, 0, 0, 0, 0, 0, 0, 0), reports[0])
        assertEquals(listOf(16, 0, 6, 0, 0, 0, 0, 0), reports[1])
        assertEquals(reports[0], reports[2])
        assertEquals(List(8) { 0 }, reports[3])
        assertEquals(List(8) { 0 }, reports.last())
    }

    /** Refuses an entire unsupported plan rather than partially pressing its modifiers. */
    @Test fun unsupportedKeyDoesNotPressModifiers() {
        val reports = mutableListOf<List<Int>>()
        assertEquals(SystemInputStatus.INVALID_KEY, sendHidReports(
            rawKeyEventPlan(KeyEvent.KEYCODE_FUNCTION, KeyEvent.META_CTRL_ON), { true }, reports::add,
        ))
        assertTrue(reports.isEmpty())
    }

    /** Rechecks focus after modifier down and releases without emitting the primary letter. */
    @Test fun focusLossReleasesAlreadyPressedModifier() {
        val reports = mutableListOf<List<Int>>()
        var checks = 0
        assertEquals(1, sendHidReports(
            rawKeyEventPlan(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON), { ++checks == 1 }, reports::add,
        ))
        assertEquals(2, checks)
        assertEquals(2, reports.size)
        assertEquals(List(8) { 0 }, reports.last())
    }

    /** An initial unsafe focus returns a distinct status and sends only a neutral report. */
    @Test fun initialFocusFailureDoesNotType() {
        val reports = mutableListOf<List<Int>>()
        assertEquals(SystemInputStatus.FOCUS_UNSAFE, sendHidReports(
            rawKeyEventPlan(KeyEvent.KEYCODE_A, 0), { false }, reports::add,
        ))
        assertEquals(listOf(List(8) { 0 }), reports)
    }

    /** Attempts release after a writer exception without replaying the failed primary report. */
    @Test fun writerFailureStillAttemptsRelease() {
        val reports = mutableListOf<List<Int>>()
        assertThrows(java.io.IOException::class.java) {
            sendHidReports(rawKeyEventPlan(KeyEvent.KEYCODE_A, 0), { true }) {
                reports.add(it)
                if (reports.size == 1) throw java.io.IOException("closed")
            }
        }
        assertEquals(2, reports.size)
        assertEquals(List(8) { 0 }, reports.last())
    }

    /** Rejects non-key actions before touching the device. */
    @Test fun invalidActionIsRejected() {
        assertEquals(SystemInputStatus.INVALID_KEY, sendHidReports(
            listOf(RawKeyEventSpec(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_A, 0)),
            { error("Must not check focus") }, { error("Must not emit") },
        ))
    }
}
