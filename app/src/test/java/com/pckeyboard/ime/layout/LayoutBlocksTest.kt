package com.pckeyboard.ime.layout

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies shared PC-layout row invariants. */
class LayoutBlocksTest {

    /** Verifies F12 is visible without changing the function row's total width. */
    @Test
    fun functionRowIncludesF12AndKeepsFourteenUnits() {
        val row = LayoutBlocks.fnRow()

        assertTrue(row.any { it.label == "F12" && it.keyCode == KeyEvent.KEYCODE_F12 })
        assertTrue(row.any { it.label == "Home" })
        assertTrue(row.any { it.label == "End" })
        assertEquals(15, row.size)
        assertEquals(14f, row.sumOf { it.widthWeight.toDouble() }.toFloat(), 0.0001f)
    }
}
