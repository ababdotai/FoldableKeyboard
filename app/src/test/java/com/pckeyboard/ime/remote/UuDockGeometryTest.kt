package com.pckeyboard.ime.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks dock calibration bounds without depending on Android's window manager. */
class UuDockGeometryTest {
    /** Keeps the calibrated region bounded even when the usable screen is very short. */
    @Test
    fun heightFitsRegularShortAndEmptyWindows() {
        assertEquals(460, UuOverlayGeometry.dockedHeight(1000, 0.46f, 220))
        assertTrue(UuOverlayGeometry.dockedHeight(1000, 0f, 220) in 150..220)
        assertTrue(UuOverlayGeometry.dockedHeight(1000, 2f, 220) <= 850)
        assertTrue(UuOverlayGeometry.dockedHeight(100, 0.15f, 220) in 1..85)
        assertEquals(0, UuOverlayGeometry.dockedHeight(0, 0.46f, 220))
    }

    /** Converts a user-selected height to a finite reusable ratio without exceeding its limits. */
    @Test
    fun calibrationRatiosAreFiniteAndBounded() {
        assertEquals(0.5f, UuOverlayGeometry.dockedRatio(1000, 500, 220), 0.001f)
        listOf(0, 1, 100, 1000).forEach { available ->
            listOf(-100, 0, 100, 5000).forEach { height ->
                val ratio = UuOverlayGeometry.dockedRatio(available, height, 220)
                assertTrue("Ratio must stay finite", ratio.isFinite())
                assertTrue("Ratio must be a bounded fraction", ratio in 0.15f..0.85f)
            }
        }
    }
}
