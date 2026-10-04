package com.pckeyboard.ime.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.pckeyboard.ime.BuildConfig
import com.pckeyboard.ime.dispatch.DispatchMode
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.settings.KeyboardPrefs
import rikka.shizuku.Shizuku

/** Allowlisted environment metadata; no device identifiers or input content are collected. */
internal data class SystemKeyboardEnvironment(
    val androidSdk: Int,
    val appVersionCode: Int,
    val uuVersionCode: Long?,
    val overlayAllowed: Boolean,
    val shizukuRunning: Boolean,
    val shizukuVersion: Int?,
    val shizukuAuthorized: Boolean,
    val platform: KeyboardPlatform,
    val imeMode: DispatchMode,
    val commandCompatibility: Boolean,
)

/** Reads current prerequisites without starting a service, requesting access, or sending keys. */
internal fun readSystemKeyboardEnvironment(context: Context): SystemKeyboardEnvironment {
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val version = if (running) runCatching { Shizuku.getVersion() }.getOrNull() else null
    val authorized = running && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    val uuVersion = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(UuKeyboardOverlayService.UU_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull()
    val prefs = KeyboardPrefs(context)
    return SystemKeyboardEnvironment(
        Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE, uuVersion,
        Settings.canDrawOverlays(context), running, version, authorized,
        prefs.keyboardPlatform, prefs.dispatchMode, prefs.uuCommandCompatibility,
    )
}

/** Serializes only fixed metadata and aggregate counters, never arbitrary logs or preferences. */
internal fun systemKeyboardDiagnosticReport(
    environment: SystemKeyboardEnvironment,
    snapshot: SystemKeyboardSnapshot,
): String = buildString {
    appendLine("FoldableKeyboard UU keyboard diagnostics / schema 2")
    appendLine("scope=UU_FLOATING_SYSTEM_KEYBOARD_ONLY")
    appendLine("android_sdk=${environment.androidSdk}")
    appendLine("app_version_code=${environment.appVersionCode}")
    appendLine("uu_version_code=${environment.uuVersionCode ?: "unavailable"}")
    appendLine("overlay_allowed=${environment.overlayAllowed}")
    appendLine("shizuku_running=${environment.shizukuRunning}")
    appendLine("shizuku_version=${environment.shizukuVersion ?: "unavailable"}")
    appendLine("shizuku_authorized=${environment.shizukuAuthorized}")
    appendLine("keyboard_platform=${environment.platform.name}")
    appendLine("ime_mode_preference=${environment.imeMode.name}")
    appendLine("command_right_ctrl_compatibility=${environment.commandCompatibility}")
    appendLine("service_connection=${snapshot.connection.name}")
    appendLine("capability_code=${snapshot.capabilityCode ?: "not_checked"}")
    appendLine("attempted_sequences=${snapshot.attempted}")
    appendLine("hid_submitted_sequences=${snapshot.accepted}")
    appendLine("failed_sequences=${snapshot.failed}")
    appendLine("dropped_sequences=${snapshot.dropped}")
    appendLine("queue_overflow_dropped=${snapshot.overflowDropped}")
    appendLine("queue_expired_dropped=${snapshot.expiredDropped}")
    appendLine("cancelled_sequences=${snapshot.cancelled}")
    appendLine("last_result_code=${snapshot.lastResultCode ?: "none"}")
    appendLine("last_expected_event_count=${snapshot.lastExpectedEvents ?: "none"}")
    appendLine("remote_delivery=UNCONFIRMED")
    appendLine("uu_view_focus=CHECKED_BEFORE_KEY_DOWN_NOT_ATOMIC")
    appendLine("uu_command_replacement=NOT_OBSERVABLE")
    appendLine("mac_input_source=NOT_OBSERVABLE")
    appendLine("Capability checks may register an HID device but never press keys.")
    appendLine("HID submission does not confirm Android dispatch, UU forwarding or Mac receipt.")
    appendLine("HID follows system focus; preflight checks cannot eliminate focus-change races.")
    appendLine("Counts cover this app process's latest floating-keyboard session, not ordinary IME RAW.")
    appendLine("No text, key codes, modifiers, clipboard, account, device ID, or per-key timestamps collected.")
}
