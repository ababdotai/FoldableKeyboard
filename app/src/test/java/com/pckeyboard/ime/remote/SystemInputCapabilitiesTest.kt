package com.pckeyboard.ime.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyEventSpec
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** Verifies registration-only preflight and fail-closed HID requests at the Binder endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SystemInputCapabilitiesTest {
    private lateinit var context: Context
    private lateinit var service: SystemInputService
    private lateinit var backend: RecordingHidBackend
    private var focusSafe = true
    private var focusQueries = 0
    private var allowedFocusQueries = Int.MAX_VALUE

    /** Installs UU and supplies deterministic device and focus dependencies without platform input. */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = SystemInputService.UU_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = SystemInputService.UU_PACKAGE
                uid = TARGET_UID
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        backend = RecordingHidBackend()
        service = SystemInputService(context, backend, HidFocusGuard { name ->
            if (name == "window") focusQueries++
            if (!focusSafe || focusQueries > allowedFocusQueries) null else if (name == "window") {
                HidFocusGuardTest.WINDOW
            } else {
                HidFocusGuardTest.INPUT
            }
        })
    }

    /** Permits keyless registration outside a remote session while refusing key reports. */
    @Test
    fun readyPreflightNeverSendsKeysOrQueriesFocus() {
        focusSafe = false
        assertEquals(SystemInputStatus.READY, service.checkCapabilities(TARGET_UID, 0))
        assertEquals(1, backend.preparations)
        assertEquals(0, backend.calls)
        assertEquals(0, focusQueries)
    }

    /** Rejects another Binder caller before inspecting or creating privileged state. */
    @Test
    fun rejectsCallerBeforeInspectingTarget() {
        ShadowBinder.setCallingUid(TARGET_UID)
        assertEquals(SystemInputStatus.CALLER_REJECTED, service.checkCapabilities(TARGET_UID, 0))
        assertEquals(SystemInputStatus.CALLER_REJECTED, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(0, backend.preparations)
    }

    /** Retains the Android 14 rollout boundary even when a HID implementation might exist earlier. */
    @Test
    @Config(sdk = [33])
    fun rejectsUnsupportedAndroid() {
        assertEquals(SystemInputStatus.UNSUPPORTED_ANDROID, service.checkCapabilities(TARGET_UID, 0))
        assertEquals(SystemInputStatus.UNSUPPORTED_ANDROID, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(0, backend.preparations)
    }

    /** Rejects privileged, self, mismatched-package, and non-default-display requests. */
    @Test
    fun validatesUidAndDisplayBeforeRegistration() {
        for (target in listOf(Process.SHELL_UID, context.applicationInfo.uid, TARGET_UID + 1)) {
            assertEquals(SystemInputStatus.INVALID_TARGET, service.checkCapabilities(target, 0))
        }
        assertEquals(SystemInputStatus.INVALID_TARGET, service.checkCapabilities(TARGET_UID, -1))
        assertEquals(SystemInputStatus.UNSUPPORTED_DISPLAY, service.checkCapabilities(TARGET_UID, 1))
        assertEquals(SystemInputStatus.UNSUPPORTED_DISPLAY, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 1))
        assertEquals(0, backend.preparations)
    }

    /** Distinguishes a missing UU installation from a failed virtual-device registration. */
    @Test
    fun missingUuHasDedicatedStatus() {
        shadowOf(context.packageManager).removePackage(SystemInputService.UU_PACKAGE)
        assertEquals(SystemInputStatus.UU_UNAVAILABLE, service.checkCapabilities(TARGET_UID, 0))
        assertEquals(0, backend.preparations)
    }

    /** Exposes HID registration failure without falling back to the old injection path. */
    @Test
    fun unavailableHidHasDedicatedStatus() {
        backend.ready = false
        assertEquals(SystemInputStatus.HID_UNAVAILABLE, service.checkCapabilities(TARGET_UID, 0))
        assertEquals(SystemInputStatus.HID_UNAVAILABLE, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(0, backend.calls)
    }

    /** Rejects unknown keys and unapproved modifiers before any privileged backend call. */
    @Test
    fun invalidKeysAreRejected() {
        assertEquals(SystemInputStatus.INVALID_KEY, service.sendKey(KeyEvent.KEYCODE_UNKNOWN, 0, TARGET_UID, 0))
        assertEquals(SystemInputStatus.INVALID_KEY, service.sendKey(KeyEvent.KEYCODE_A, Int.MIN_VALUE, TARGET_UID, 0))
        assertEquals(0, backend.preparations)
    }

    /** Allows keyless registration but refuses reports outside UU physical-key input. */
    @Test
    fun unsafeFocusNeverSubmitsReports() {
        focusSafe = false
        assertEquals(SystemInputStatus.FOCUS_UNSAFE, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(1, backend.preparations)
        assertEquals(1, backend.calls)
        assertEquals(0, backend.accepted)
    }

    /** Checks focus after registration and aborts before the first report if it is unsafe. */
    @Test
    fun focusChangeBeforeFirstReportHasDedicatedStatus() {
        allowedFocusQueries = 0
        assertEquals(SystemInputStatus.FOCUS_UNSAFE, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(0, backend.accepted)
    }

    /** Preserves partial counts rather than claiming an incomplete modifier sequence succeeded. */
    @Test
    fun focusChangeBetweenDownsKeepsPartialCount() {
        allowedFocusQueries = 1
        assertEquals(1, service.sendKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, TARGET_UID, 0))
        assertEquals(1, backend.accepted)
    }

    /** Reports full submission only after both the down and up events have been accepted. */
    @Test
    fun countsSubmittedEvents() {
        assertEquals(2, service.sendKey(KeyEvent.KEYCODE_A, 0, TARGET_UID, 0))
        assertEquals(2, backend.accepted)
        assertEquals(1, focusQueries)
    }

    /** Converts permission exceptions without retaining sensitive exception messages. */
    @Test
    fun permissionFailureHasDedicatedStatus() {
        backend.permissionDenied = true
        assertEquals(SystemInputStatus.PERMISSION_DENIED, service.checkCapabilities(TARGET_UID, 0))
    }

    companion object {
        private const val TARGET_UID = 12345
    }
}

/** Simulates a report backend without opening UHID or storing typed text. */
private class RecordingHidBackend : SystemHidBackend() {
    var preparations = 0
    var calls = 0
    var accepted = 0
    var ready = true
    var permissionDenied = false

    /** Counts registration checks and optionally simulates denied shell permissions. */
    override fun ensureReady(): Boolean {
        preparations++
        if (permissionDenied) throw SecurityException("private")
        return ready
    }

    /** Mimics the transport's required focus check before each down event. */
    override fun send(plan: List<RawKeyEventSpec>, canSend: () -> Boolean): Int {
        calls++
        for (event in plan) {
            if (event.action == KeyEvent.ACTION_DOWN && !canSend()) break
            accepted++
        }
        return accepted
    }
}
