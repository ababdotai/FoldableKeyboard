package com.pckeyboard.ime.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.pckeyboard.ime.R
import com.pckeyboard.ime.remote.SystemKeyboardDiagnosticsActivity
import com.pckeyboard.ime.remote.UuKeyboardOverlayService
import com.pckeyboard.ime.remote.supportsTargetedSystemInput
import rikka.shizuku.Shizuku

/** Guides explicit setup of the optional, UU-only system keyboard transport. */
class UuKeyboardSetupController(private val activity: Activity, root: View) {
    private val status: TextView = root.findViewById(R.id.uuKeyboardStatus)
    private val start: View = root.findViewById(R.id.uuStartKeyboard)
    private val authorize: View = root.findViewById(R.id.uuAuthorize)
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == PERMISSION_REQUEST) refresh()
    }

    init {
        val prefs = KeyboardPrefs(activity)
        root.findViewById<MaterialSwitch>(R.id.uuCommandCompatibility).apply {
            isChecked = prefs.uuCommandCompatibility
            setOnCheckedChangeListener { _, checked -> prefs.uuCommandCompatibility = checked }
        }
        root.findViewById<MaterialSwitch>(R.id.uuShortcutBar).apply {
            isChecked = prefs.uuShortcutBar
            setOnCheckedChangeListener { _, checked -> prefs.uuShortcutBar = checked }
        }
        root.findViewById<View>(R.id.uuOverlayPermission).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")))
        }
        authorize.setOnClickListener { requestAuthorization() }
        root.findViewById<View>(R.id.uuSetupGuide).setOnClickListener {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/guide/setup/")))
        }
        root.findViewById<View>(R.id.uuDiagnostics).setOnClickListener {
            open(Intent(activity, SystemKeyboardDiagnosticsActivity::class.java))
        }
        start.setOnClickListener { startKeyboard() }
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    /** Updates setup status without requesting privileges in the background. */
    fun refresh() {
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            val readyMessage = when {
                !supportsTargetedSystemInput(Build.VERSION.SDK_INT) -> R.string.uu_keyboard_need_android
                !hasUu() -> R.string.uu_keyboard_need_uu
                !Settings.canDrawOverlays(activity) -> R.string.uu_keyboard_need_overlay
                !binderAvailable() -> R.string.uu_keyboard_need_shizuku
                !isAuthorized() -> R.string.uu_keyboard_need_authorization
                else -> R.string.uu_keyboard_ready
            }
            status.setText(readyMessage)
            start.isEnabled = readyMessage == R.string.uu_keyboard_ready
            val authorized = isAuthorized()
            (authorize as TextView).setText(
                if (authorized) R.string.uu_keyboard_authorized else R.string.uu_keyboard_authorize,
            )
            authorize.isEnabled = supportsTargetedSystemInput(Build.VERSION.SDK_INT) &&
                binderAvailable() && !authorized
        }
    }

    /** Detaches callbacks when the settings screen is destroyed. */
    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    /** Requests Shizuku access only after an explicit tap in settings. */
    private fun requestAuthorization() {
        try {
            if (binderAvailable() && !isAuthorized()) {
                Shizuku.requestPermission(PERMISSION_REQUEST)
            }
        } catch (error: RuntimeException) {
            showError(error)
        }
        refresh()
    }

    /** Starts a visible, user-stoppable overlay after validating its prerequisites again. */
    private fun startKeyboard() {
        refresh()
        if (!start.isEnabled) return
        try {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, UuKeyboardOverlayService::class.java)
                    .setAction(UuKeyboardOverlayService.ACTION_START),
            )
            activity.finish()
        } catch (error: RuntimeException) {
            showError(error)
        }
    }

    /** Checks the explicitly queried UU package without inspecting other installed apps. */
    private fun hasUu(): Boolean = try {
        activity.packageManager.getApplicationInfo("com.netease.uuremote", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Treats a stopped or unsupported Shizuku server as unavailable. */
    private fun binderAvailable(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 13
    } catch (_: RuntimeException) {
        false
    }

    /** Reads authorization only while the Shizuku binder is available. */
    private fun isAuthorized(): Boolean = try {
        binderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    /** Opens a system or official setup screen and reports unavailable handlers. */
    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (error: RuntimeException) {
            showError(error)
        }
    }

    /** Makes setup failures visible without logging any typed content. */
    private fun showError(error: RuntimeException) {
        Toast.makeText(activity, activity.getString(R.string.uu_keyboard_error,
            error.localizedMessage ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PERMISSION_REQUEST = 4101
    }
}
