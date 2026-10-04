package com.pckeyboard.ime.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyEventSpec
import com.pckeyboard.ime.dispatch.RawScanCodeMapper
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import kotlin.system.exitProcess

/** Owns a shell-privileged HID keyboard while restricting requests to the UU session. */
class SystemInputService private constructor(
    private val context: Context,
    private val backend: SystemHidBackend,
    private val focusSafe: () -> Boolean,
) : ISystemInputService.Stub() {
    /** Creates the production service without registering a device until requested. */
    constructor(context: Context) : this(context, SystemHidBackend(), HidFocusGuard()::isSafe)

    /** Supplies isolated host-test dependencies without opening a platform HID device. */
    internal constructor(context: Context, backend: SystemHidBackend, guard: HidFocusGuard) :
        this(context, backend, guard::isSafe)

    private val ownerUid = context.applicationInfo.uid
    private val injectionLock = Any()

    /** Sends one non-replayable HID sequence only while UU owns the physical-key input view. */
    override fun sendKey(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int): Int {
        val validation = validateTarget(targetUid, displayId)
        if (validation != SystemInputStatus.READY) return validation
        if (RawScanCodeMapper.forKeyCode(keyCode) == 0 || metaState and ALLOWED_META.inv() != 0) {
            return SystemInputStatus.INVALID_KEY
        }
        return withShellIdentity {
            synchronized(injectionLock) {
                if (!backend.ensureReady()) return@synchronized SystemInputStatus.HID_UNAVAILABLE
                var rejectedFocus = false
                val accepted = backend.send(rawKeyEventPlan(keyCode, KeyEvent.normalizeMetaState(metaState))) {
                    focusSafe().also { if (!it) rejectedFocus = true }
                }
                if (accepted != 0) accepted else if (rejectedFocus) {
                    SystemInputStatus.FOCUS_UNSAFE
                } else {
                    SystemInputStatus.INJECTION_ERROR
                }
            }
        }
    }

    /** Registers the HID device without sending reports or requiring an active UU editor. */
    override fun checkCapabilities(targetUid: Int, displayId: Int): Int {
        val validation = validateTarget(targetUid, displayId)
        if (validation != SystemInputStatus.READY) return validation
        return withShellIdentity {
            synchronized(injectionLock) {
                if (backend.ensureReady()) SystemInputStatus.READY else SystemInputStatus.HID_UNAVAILABLE
            }
        }
    }

    /** Checks the exact installed package UID before any privileged operation is permitted. */
    private fun validateTarget(targetUid: Int, displayId: Int): Int {
        if (Binder.getCallingUid() != ownerUid) return SystemInputStatus.CALLER_REJECTED
        if (!supportsTargetedSystemInput(Build.VERSION.SDK_INT)) return SystemInputStatus.UNSUPPORTED_ANDROID
        if (targetUid < Process.FIRST_APPLICATION_UID || targetUid == ownerUid || displayId < 0) {
            return SystemInputStatus.INVALID_TARGET
        }
        if (displayId != 0) return SystemInputStatus.UNSUPPORTED_DISPLAY
        val remoteUid = try {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(UU_PACKAGE, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return SystemInputStatus.UU_UNAVAILABLE
        } catch (_: SecurityException) {
            return SystemInputStatus.PERMISSION_DENIED
        }
        return if (targetUid == remoteUid) SystemInputStatus.READY else SystemInputStatus.INVALID_TARGET
    }

    /** Restores Binder identity and converts failures to content-free status codes. */
    private fun withShellIdentity(action: () -> Int): Int {
        val identity = Binder.clearCallingIdentity()
        return try {
            action()
        } catch (_: SecurityException) {
            SystemInputStatus.PERMISSION_DENIED
        } catch (_: Exception) {
            SystemInputStatus.UNKNOWN_ERROR
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Releases reports and removes the virtual device before terminating the private process. */
    override fun destroy() {
        val caller = Binder.getCallingUid()
        if (caller != ownerUid && caller != Process.SHELL_UID && caller != Process.ROOT_UID) return
        synchronized(injectionLock) {
            try { backend.close() } finally { exitProcess(0) }
        }
    }

    companion object {
        internal const val UU_PACKAGE = "com.netease.uuremote"
        private const val ALLOWED_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
            KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
            KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
            KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or
            KeyEvent.META_META_RIGHT_ON or KeyEvent.META_FUNCTION_ON
    }
}

/** Provides a narrow injectable boundary around the privileged HID transport. */
internal open class SystemHidBackend {
    private val transport by lazy { HidKeyboardTransport() }

    /** Registers or checks the virtual keyboard without pressing a key. */
    open fun ensureReady(): Boolean = transport.ensureReady()

    /** Checks focus before each down report and returns the submitted plan-event count. */
    open fun send(plan: List<RawKeyEventSpec>, canSend: () -> Boolean): Int = transport.send(plan, canSend)

    /** Tears down the virtual device and any held keys. */
    open fun close() = transport.close()
}

/** Stops after an injection failure and best-effort releases keys without replaying key-downs. */
internal fun injectKeyPlan(plan: List<RawKeyEventSpec>, send: (RawKeyEventSpec) -> Boolean): Int {
    val pressed = linkedMapOf<Int, RawKeyEventSpec>()
    var accepted = 0
    try {
        for (event in plan) {
            if (event.action == KeyEvent.ACTION_DOWN) pressed[event.keyCode] = event
            val delivered = try { send(event) } catch (_: Exception) { false }
            if (!delivered) break
            accepted++
            if (event.action == KeyEvent.ACTION_UP) pressed.remove(event.keyCode)
        }
    } finally {
        pressed.values.toList().asReversed().forEach { event ->
            try { send(event.copy(action = KeyEvent.ACTION_UP, metaState = 0)) } catch (_: Exception) { }
        }
    }
    return accepted
}
