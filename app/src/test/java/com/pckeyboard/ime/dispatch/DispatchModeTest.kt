package com.pckeyboard.ime.dispatch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies dispatch-mode persistence parsing remains upgrade-safe. */
class DispatchModeTest {

    /** Verifies missing and unknown values preserve existing Android behavior. */
    @Test
    fun defaultsToNormal() {
        assertEquals(DispatchMode.NORMAL, DispatchMode.fromPreference(null))
        assertEquals(DispatchMode.NORMAL, DispatchMode.fromPreference("future_mode"))
    }

    /** Verifies the raw preference value restores remote dispatch. */
    @Test
    fun parsesRawMode() {
        assertEquals(DispatchMode.RAW_REMOTE, DispatchMode.fromPreference("raw_remote"))
    }
}
