package com.pckeyboard.ime.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies movable overlay sizing independently of device density and Android layout. */
class UuOverlayGeometryTest {
    /** Reserves sideways movement on a tablet while respecting a phone's usable width. */
    @Test
    fun expandedWidthFitsNarrowAndWideDisplays() {
        assertEquals(900, UuOverlayGeometry.expandedWidth(1000, 360))
        assertEquals(360, UuOverlayGeometry.expandedWidth(380, 360))
        assertEquals(320, UuOverlayGeometry.expandedWidth(320, 360))
    }

    /** Bounds both drag directions and keeps oversized content anchored to its leading edge. */
    @Test
    fun clampHandlesEdgesAndOversizedPanels() {
        assertEquals(20, UuOverlayGeometry.clamp(-100, 20, 800, 300))
        assertEquals(520, UuOverlayGeometry.clamp(1000, 20, 800, 300))
        assertEquals(123, UuOverlayGeometry.clamp(123, 20, 800, 300))
        assertEquals(20, UuOverlayGeometry.clamp(123, 20, 200, 300))
    }
}
