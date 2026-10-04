package com.pckeyboard.ime.remote

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.InputEvent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.pckeyboard.ime.R
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.view.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises real overlay widgets and lifecycle without a running Shizuku server or injection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class UuKeyboardOverlayIntegrationTest {

    /** Keeps hide reversible and separates dragging the restore button from tapping it. */
    @Test
    fun hideKeyCreatesDraggableCompactRestoreAndNotificationCanExpand() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        try {
            service.onKey(Key.fn("Hide", KeyType.HIDE), ModifierState())
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(descendants(panel).filterIsInstance<KeyboardView>().none())
            val restore = buttonWithText(panel, service.getString(R.string.uu_overlay_restore))
            assertEquals(View.VISIBLE, restore.visibility)
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertEquals((56 * service.resources.displayMetrics.density).toInt(), params.width)
            params.x = 0
            params.y = 100
            touch(restore, MotionEvent.ACTION_DOWN, 0f, 0f)
            touch(restore, MotionEvent.ACTION_MOVE, 80f, -80f)
            touch(restore, MotionEvent.ACTION_UP, 80f, -80f)
            assertTrue(params.x > 0)
            assertTrue(params.y in 0..99)
            assertTrue(params.x + params.width <= service.getSystemService(WindowManager::class.java)
                .currentWindowMetrics.bounds.width())
            assertTrue(descendants(panel).filterIsInstance<KeyboardView>().none())
            touch(restore, MotionEvent.ACTION_DOWN, 0f, 0f)
            touch(restore, MotionEvent.ACTION_UP, 0f, 0f)
            assertEquals(1, descendants(panel).filterIsInstance<KeyboardView>().count())
            service.onKey(Key.fn("Hide", KeyType.HIDE), ModifierState())
            service.onStartCommand(Intent(service, UuKeyboardOverlayService::class.java)
                .setAction(UuKeyboardOverlayService.ACTION_SHOW), 0, 2)
            assertEquals(1, descendants(panel).filterIsInstance<KeyboardView>().count())
        } finally {
            controller.destroy()
        }
    }

    /** Ensures a stale notification cannot create a fresh privileged session. */
    @Test
    fun restoreActionDoesNotStartAnAbsentSession() {
        val controller = Robolectric.buildService(UuKeyboardOverlayService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(service, UuKeyboardOverlayService::class.java)
                .setAction(UuKeyboardOverlayService.ACTION_SHOW), 0, 1)
            assertTrue(overlayWindows(service).views.isEmpty())
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    /** Delivers a single recycled touch event to a real overlay control. */
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    /** Preserves FIFO and exact explicit modifiers through the real overlay and bridge. */
    @Test
    @Config(sdk = [34], shadows = [ShadowShizukuStartupApi::class])
    fun toolbarRoutesExplicitCommandsAndCancelsKeyboardState() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        val prefs = KeyboardPrefs(service)
        val endpoint = ShortcutEndpoint()
        val firstSendEntered = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        endpoint.beforeSend = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_A) {
                firstSendEntered.countDown()
                assertTrue(releaseFirstSend.await(2, TimeUnit.SECONDS))
            }
        }
        val bridgeField = UuKeyboardOverlayService::class.java.getDeclaredField("bridge").apply { isAccessible = true }
        val bridge = bridgeField.get(service) as ShizukuKeyBridge
        val workerField = ShizukuKeyBridge::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = workerField.get(bridge) as ExecutorService
        try {
            requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(
                ComponentName(service, SystemInputService::class.java), endpoint,
            )
            worker.submit { }.get(2, TimeUnit.SECONDS)
            val oldCopy = descendants(panel).filterIsInstance<Button>().single { it.text.contains("Ctrl+C") }
            prefs.keyboardPlatform = KeyboardPlatform.MAC
            shadowOf(Looper.getMainLooper()).idle()
            oldCopy.performClick()
            val keyboard = descendants(panel).filterIsInstance<KeyboardView>().single()
            val modifierField = KeyboardView::class.java.getDeclaredField("modifiers").apply { isAccessible = true }
            val modifiers = modifierField.get(keyboard) as ModifierState
            modifiers.tapShift()
            modifiers.tapAlt()
            modifiers.tapFn()
            val trackpadField = KeyboardView::class.java.getDeclaredField("trackpadActive").apply { isAccessible = true }
            trackpadField.setBoolean(keyboard, true)
            descendants(panel).filterIsInstance<Button>().single { it.text.contains("⌘A") }.performClick()
            assertTrue(firstSendEntered.await(2, TimeUnit.SECONDS))
            descendants(panel).filterIsInstance<Button>().single { it.text.contains("⌘V") }.performClick()
            descendants(panel).filterIsInstance<Button>().single { it.text.contains("⇧⌘Z") }.performClick()
            releaseFirstSend.countDown()
            worker.submit { }.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_Z), endpoint.keys)
            val command = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            assertEquals(listOf(command, command, command or KeyEvent.META_SHIFT_ON), endpoint.metas)
            assertEquals(ModifierState.State.OFF, modifiers.shift)
            assertEquals(ModifierState.State.OFF, modifiers.alt)
            assertEquals(ModifierState.State.OFF, modifiers.fn)
            assertFalse(trackpadField.getBoolean(keyboard))
            prefs.keyboardPlatform = KeyboardPlatform.WIN
            shadowOf(Looper.getMainLooper()).idle()
            descendants(panel).filterIsInstance<Button>().single { it.text.contains("Ctrl+C") }.performClick()
            worker.submit { }.get(2, TimeUnit.SECONDS)
            assertEquals(KeyEvent.KEYCODE_C, endpoint.keys.last())
            assertEquals(KeyEvent.META_CTRL_ON, endpoint.metas.last())
        } finally {
            releaseFirstSend.countDown()
            controller.destroy()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Captures test-only strokes while modeling a completely accepted event plan. */
    private class ShortcutEndpoint : ISystemInputService.Stub() {
        val keys = mutableListOf<Int>()
        val metas = mutableListOf<Int>()
        var beforeSend: ((Int) -> Unit)? = null

        /** Reports a read-only available capability without injecting anything. */
        override fun checkCapabilities(targetUid: Int, displayId: Int): Int = SystemInputStatus.READY

        /** Records the test command and accepts the existing modifier event plan. */
        override fun sendKey(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int): Int {
            assertEquals(12345, targetUid)
            beforeSend?.invoke(keyCode)
            keys.add(keyCode)
            metas.add(metaState)
            return rawKeyEventPlan(keyCode, metaState).size
        }

        /** Keeps the in-process fake endpoint alive until test teardown. */
        override fun destroy() = Unit
    }

    /** Rebuilds live labels and visibility without restarting or stealing focus from UU. */
    @Test
    fun shortcutToolbarTracksPreferencesAndCollapse() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        val prefs = KeyboardPrefs(service)
        try {
            assertEquals(1, descendants(panel).filterIsInstance<RemoteShortcutBar>().count())
            assertTrue(descendants(panel).filterIsInstance<Button>().any { it.text.contains("Ctrl+C") })
            prefs.keyboardPlatform = KeyboardPlatform.MAC
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(descendants(panel).filterIsInstance<Button>().any { it.text.contains("⌘C") })
            assertFalse(descendants(panel).filterIsInstance<Button>().any { it.text.contains("Ctrl+C") })
            prefs.uuShortcutBar = false
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(descendants(panel).filterIsInstance<RemoteShortcutBar>().none())
            prefs.uuShortcutBar = true
            shadowOf(Looper.getMainLooper()).idle()
            buttonWithText(panel, service.getString(R.string.uu_overlay_collapse)).performClick()
            assertTrue(descendants(panel).filterIsInstance<RemoteShortcutBar>().none())
            buttonWithText(panel, service.getString(R.string.uu_overlay_restore)).performClick()
            assertEquals(1, descendants(panel).filterIsInstance<RemoteShortcutBar>().count())
        } finally {
            controller.destroy()
        }
        prefs.uuShortcutBar = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlayWindows(service).views.isEmpty())
    }

    /** Refuses Android 13 before creating a window or a privileged injection session. */
    @Test
    @Config(sdk = [33])
    fun rejectsAndroid13SystemFallbackBehavior() {
        val controller = startOverlay()
        try {
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(overlayWindows(controller.get()).views.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    /** Grants only the prerequisites needed to display the keyboard over an installed UU app. */
    @Before
    fun allowOverlayForInstalledUu() {
        resetDiagnosticFileProviderCache()
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.packageManager).installPackage(PackageInfo().apply {
            packageName = UuKeyboardOverlayService.UU_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = UuKeyboardOverlayService.UU_PACKAGE
                uid = 12345
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(application.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    /** Ensures service-context widget creation succeeds and the window cannot steal UU focus. */
    @Test
    fun createsNonFocusableOverlayAndDestroysWindowOnClose() {
        val controller = startOverlay()
        val service = controller.get()
        val windows = overlayWindows(service)
        val panel = windows.views.single()
        try {
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(1, descendants(panel).filterIsInstance<KeyboardView>().count())
            assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
            if (Build.VERSION.SDK_INT >= 34) {
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
            }
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(descendants(panel).filterIsInstance<TextView>().any {
                it.text.toString().contains(service.getString(R.string.uu_overlay_title))
            })

            buttonWithText(panel, service.getString(R.string.uu_overlay_close)).performClick()
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(windows.views.isEmpty())
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    /** Keeps diagnostics and a usable keyboard within a short landscape display without focus. */
    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-mdpi")
    fun diagnosticsFitsShortLandscapeWithoutTakingFocus() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        try {
            buttonWithText(panel, service.getString(R.string.uu_diagnostics_toggle)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.AT_MOST),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val diagnostics = descendants(panel).filterIsInstance<SystemKeyboardDiagnosticsPanel>().single()
            val keyboard = descendants(panel).filterIsInstance<KeyboardView>().single()
            assertEquals(View.VISIBLE, diagnostics.visibility)
            assertTrue(panel.measuredHeight <= 360)
            assertTrue(diagnostics.height > 0)
            assertTrue(keyboard.height > 0)
            assertTrue(keyboard.bottom <= panel.height)
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals(null, shadowOf(service).nextStartedActivity)
            assertTrue(descendants(diagnostics).filterIsInstance<TextView>().any {
                it.text.contains("5 · 远端接收：未确认")
            })
        } finally {
            controller.destroy()
        }
    }

    /** Collapses the surface before opening a chooser from a service context with read-only access. */
    @Test
    fun overlayExportCollapsesKeyboardAndLaunchesChooserInNewTask() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        try {
            buttonWithText(panel, service.getString(R.string.uu_diagnostics_toggle)).performClick()
            buttonWithText(panel, service.getString(R.string.uu_diagnostics_export)).performClick()
            val chooser = shadowOf(service).nextStartedActivity
            assertNotNull(chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val share = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, share.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(0, descendants(panel).filterIsInstance<KeyboardView>().count())
            assertEquals(View.GONE, descendants(panel).filterIsInstance<SystemKeyboardDiagnosticsPanel>().single().visibility)
            buttonWithText(panel, service.getString(R.string.uu_overlay_restore)).performClick()
            assertEquals(1, descendants(panel).filterIsInstance<KeyboardView>().count())
        } finally {
            controller.destroy()
        }
    }

    /** Checks collapsing and rotation detach old key listeners before creating a fresh keyboard. */
    @Test
    fun collapsesExpandsAndRebuildsKeyboardWithoutReusingPressedKeys() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = overlayWindows(service).views.single()
        try {
            val initial = descendants(panel).filterIsInstance<KeyboardView>().single()
            buttonWithText(panel, service.getString(R.string.uu_overlay_collapse)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, descendants(panel).filterIsInstance<KeyboardView>().count())
            assertNull(initial.listener)

            buttonWithText(panel, service.getString(R.string.uu_overlay_restore)).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val expanded = descendants(panel).filterIsInstance<KeyboardView>().single()
            assertNotSame(initial, expanded)
            assertNotNull(expanded.listener)

            service.onConfigurationChanged(Configuration(service.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            })
            shadowOf(Looper.getMainLooper()).idle()
            val rotated = descendants(panel).filterIsInstance<KeyboardView>().single()
            assertNotSame(expanded, rotated)
            assertNull(expanded.listener)
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    /** Confirms screen-off and explicit stop requests end sessions instead of requesting restart. */
    @Test
    fun stopsOnScreenOffAndNeverRequestsAutomaticRestart() {
        val controller = startOverlay()
        val service = controller.get()
        try {
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(
                Service.START_NOT_STICKY,
                service.onStartCommand(Intent(service, UuKeyboardOverlayService::class.java)
                    .setAction(UuKeyboardOverlayService.ACTION_STOP), 0, 2),
            )
        } finally {
            controller.destroy()
        }
        assertTrue(overlayWindows(service).views.isEmpty())
    }

    /** Inflates the production settings resource under its real theme without activity side effects. */
    @Test
    fun inflatesSettingsWithOverlaySetupAndStartControls() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_PcKeyboard)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null, false)
        for (id in listOf(R.id.uuOverlayPermission, R.id.uuAuthorize, R.id.uuSetupGuide, R.id.uuStartKeyboard, R.id.uuDiagnostics)) {
            assertNotNull(root.findViewById<View>(id))
        }
        assertEquals(
            context.getString(R.string.uu_keyboard_start),
            root.findViewById<TextView>(R.id.uuStartKeyboard).text.toString(),
        )
    }

    /** Checks each platform's targeted injection signatures without invoking privileged APIs. */
    @Test
    fun exposesUidTargetedInjectionAndDisplaySelectionSignatures() {
        val ownerName = if (Build.VERSION.SDK_INT >= 34) {
            "android.hardware.input.InputManagerGlobal"
        } else {
            "android.hardware.input.InputManager"
        }
        val owner = Class.forName(ownerName)
        val getInstance = owner.getMethod("getInstance")
        assertTrue(Modifier.isStatic(getInstance.modifiers))
        assertEquals(owner, getInstance.returnType)

        val inject = owner.getMethod(
            "injectInputEvent", InputEvent::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        assertEquals(Boolean::class.javaPrimitiveType, inject.returnType)
        assertTrue(Modifier.isPublic(inject.modifiers))
        assertFalse(Modifier.isStatic(inject.modifiers))

        val setDisplay = KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        assertEquals(Void.TYPE, setDisplay.returnType)
        assertFalse(Modifier.isStatic(setDisplay.modifiers))
    }

    /** Starts the real service and flushes its disconnected-Shizuku status callback. */
    private fun startOverlay(): ServiceController<UuKeyboardOverlayService> {
        val intent = Intent(RuntimeEnvironment.getApplication(), UuKeyboardOverlayService::class.java)
            .setAction(UuKeyboardOverlayService.ACTION_START)
        val controller = Robolectric.buildService(UuKeyboardOverlayService::class.java, intent)
            .create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    /** Obtains the window shadow without depending on private service fields or bridge internals. */
    private fun overlayWindows(context: Context): ShadowWindowManagerImpl =
        Shadow.extract(context.getSystemService(WindowManager::class.java))

    /** Finds a visible header action by the same localized label presented to users. */
    private fun buttonWithText(root: View, text: String): Button =
        descendants(root).filterIsInstance<Button>().single { it.text.toString() == text }

    /** Walks actual widget children to verify keyboard replacement and resource inflation. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
