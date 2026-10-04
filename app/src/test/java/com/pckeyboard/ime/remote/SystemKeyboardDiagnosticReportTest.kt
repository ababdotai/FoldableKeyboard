package com.pckeyboard.ime.remote

import com.pckeyboard.ime.dispatch.DispatchMode
import com.pckeyboard.ime.layout.KeyboardPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks that exported evidence stays bounded to metadata and aggregate transport results. */
class SystemKeyboardDiagnosticReportTest {
    private val environment = SystemKeyboardEnvironment(
        34, 1, 440000L, true, true, 13, true,
        KeyboardPlatform.MAC, DispatchMode.RAW_REMOTE, true,
    )

    /** Keeps the remote boundary unconfirmed even when every Android event was accepted. */
    @Test
    fun fullAcceptanceNeverClaimsRemoteDelivery() {
        val report = systemKeyboardDiagnosticReport(environment, SystemKeyboardSnapshot(
            connection = SystemConnectionState.CONNECTED,
            capabilityCode = SystemInputStatus.READY,
            attempted = 4, accepted = 4, lastResultCode = 2, lastExpectedEvents = 2,
        ))
        assertTrue(report.contains("hid_submitted_sequences=4"))
        assertTrue(report.contains("remote_delivery=UNCONFIRMED"))
        assertTrue(report.contains("uu_view_focus=CHECKED_BEFORE_KEY_DOWN_NOT_ATOMIC"))
        assertTrue(report.contains("mac_input_source=NOT_OBSERVABLE"))
    }

    /** Fixes the report schema so accidental field additions require explicit privacy review. */
    @Test
    fun exportContainsOnlyAllowlistedFields() {
        val report = systemKeyboardDiagnosticReport(environment, SystemKeyboardSnapshot())
        assertTrue(report.startsWith("FoldableKeyboard UU keyboard diagnostics / schema 2"))
        val fields = report.lineSequence().filter { '=' in it }.map { it.substringBefore('=') }.toSet()
        assertEquals(setOf(
            "scope", "android_sdk", "app_version_code", "uu_version_code", "overlay_allowed",
            "shizuku_running", "shizuku_version", "shizuku_authorized", "keyboard_platform",
            "ime_mode_preference", "command_right_ctrl_compatibility", "service_connection",
            "capability_code", "attempted_sequences", "hid_submitted_sequences", "failed_sequences",
            "dropped_sequences", "queue_overflow_dropped", "queue_expired_dropped", "cancelled_sequences",
            "last_result_code", "last_expected_event_count", "remote_delivery", "uu_view_focus",
            "uu_command_replacement", "mac_input_source",
        ), fields)
        assertFalse(report.contains("target_uid="))
        assertFalse(report.contains("key_code="))
        assertFalse(report.contains("timestamp="))
    }

    /** Distinguishes missing information from checked readiness and rejected delivery. */
    @Test
    fun unknownAndFailedValuesRemainDistinct() {
        val initial = systemKeyboardDiagnosticReport(environment.copy(uuVersionCode = null), SystemKeyboardSnapshot())
        assertTrue(initial.contains("uu_version_code=unavailable"))
        assertTrue(initial.contains("capability_code=not_checked"))
        assertTrue(initial.contains("last_result_code=none"))
        val failed = systemKeyboardDiagnosticReport(environment, SystemKeyboardSnapshot(
            attempted = 1, failed = 1, lastResultCode = SystemInputStatus.PERMISSION_DENIED,
            lastExpectedEvents = 2,
        ))
        assertTrue(failed.contains("last_result_code=-10"))
        assertTrue(failed.contains("hid_submitted_sequences=0"))
    }
}
