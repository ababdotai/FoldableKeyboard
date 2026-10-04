package com.pckeyboard.ime.theme

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import com.pckeyboard.ime.R
import com.pckeyboard.ime.remote.RemoteShortcutBar
import com.pckeyboard.ime.remote.ISystemInputService
import com.pckeyboard.ime.remote.ShadowShizukuStartupApi
import com.pckeyboard.ime.remote.SystemConnectionState
import com.pckeyboard.ime.remote.SystemInputService
import com.pckeyboard.ime.remote.SystemInputStatus
import com.pckeyboard.ime.remote.SystemKeyboardDiagnostics
import com.pckeyboard.ime.remote.SystemKeyboardDiagnosticsPanel
import com.pckeyboard.ime.remote.UuKeyboardOverlayService
import com.pckeyboard.ime.remote.resetDiagnosticFileProviderCache
import com.pckeyboard.ime.service.PcKeyboardService
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.view.KeyboardView
import com.pckeyboard.ime.view.KeyView
import com.pckeyboard.ime.view.SuggestionBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises real IME and overlay configuration callbacks without remote input or update jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1000dp-port-notnight-mdpi")
class SystemThemeSurfaceTest {
    /** Disables credential-storage update work and candidate model loading in IME tests. */
    @Before
    fun disableBackgroundWork() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        KeyboardPrefs(app).autocorrectMode = KeyboardPrefs.AUTOCORRECT_OFF
    }

    /** Updates already mounted IME keys on system changes and immediate preference toggles. */
    @Test
    fun visibleImeTracksConfigurationAndRestoresItsManualTheme() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        repository.selectTheme(Themes.BLACK.id)
        val controller = Robolectric.buildService(PcKeyboardService::class.java).create()
        val service = controller.get()
        val keyboard = service.onCreateInputView() as KeyboardView
        try {
            startEditor(service)
            assertKeyThemes(keyboard, Themes.BLACK)
            repository.followSystemTheme = true
            shadowOf(Looper.getMainLooper()).idle()
            assertKeyThemes(keyboard, Themes.LIGHT)
            RuntimeEnvironment.setQualifiers("+night")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            assertKeyThemes(keyboard, Themes.DARK)
            repository.followSystemTheme = false
            shadowOf(Looper.getMainLooper()).idle()
            assertKeyThemes(keyboard, Themes.BLACK)
        } finally {
            controller.destroy()
        }
        repository.selectTheme(Themes.LIGHT.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertKeyThemes(keyboard, Themes.BLACK)
    }

    /** Keeps terminal colors authoritative during system changes and restores automatic mode afterward. */
    @Test
    fun terminalSessionOverridesSystemAppearanceUntilTheEditorChanges() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication()).apply { followSystemTheme = true }
        val controller = Robolectric.buildService(PcKeyboardService::class.java).create()
        val service = controller.get()
        val keyboard = service.onCreateInputView() as KeyboardView
        val extras = Bundle().apply {
            putInt(TerminalThemeBridge.EXTRA_BACKGROUND, Color.rgb(12, 28, 44))
            putInt(TerminalThemeBridge.EXTRA_FOREGROUND, Color.rgb(224, 238, 214))
        }
        try {
            startEditor(service, extras)
            assertKeyThemes(keyboard, requireNotNull(TerminalThemeBridge.fromExtras(extras, Themes.LIGHT)))
            RuntimeEnvironment.setQualifiers("+night")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            assertKeyThemes(keyboard, requireNotNull(TerminalThemeBridge.fromExtras(extras, Themes.DARK)))
            repository.selectTheme(Themes.BLACK.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertKeyThemes(keyboard, requireNotNull(TerminalThemeBridge.fromExtras(extras, Themes.BLACK)))
            repository.followSystemTheme = true
            shadowOf(Looper.getMainLooper()).idle()
            service.onFinishInputView(false)
            startEditor(service)
            assertKeyThemes(keyboard, Themes.DARK)
        } finally {
            controller.destroy()
        }
    }

    /** Preserves mounted suggestion words and scroll offset while replacing stale auxiliary colors. */
    @Test
    fun imeAppearanceChangePreservesCandidatesAndDismissesOldEmojiSurface() {
        ThemeRepository(RuntimeEnvironment.getApplication()).followSystemTheme = true
        val controller = Robolectric.buildService(PcKeyboardService::class.java).create()
        val service = controller.get()
        val keyboard = service.onCreateInputView() as KeyboardView
        val words = mutableListOf("candidate-one", "candidate-two", "candidate-three", "candidate-four")
        try {
            startEditor(service)
            keyboard.setSuggestionBarVisible(true)
            keyboard.setSuggestions(words)
            val bar = descendants(keyboard).filterIsInstance<SuggestionBarView>().single()
            bar.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(42, View.MeasureSpec.EXACTLY))
            bar.layout(0, 0, 200, 42)
            bar.scrollTo(80, 0)
            val previousScroll = bar.scrollX
            assertTrue(previousScroll > 0)
            val originalWords = words.toList()
            words.clear()
            RuntimeEnvironment.setQualifiers("+night")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            assertSame(bar, descendants(keyboard).filterIsInstance<SuggestionBarView>().single())
            assertEquals(originalWords, descendants(bar).filterIsInstance<TextView>().map { it.text.toString() }.toList())
            assertEquals(previousScroll, bar.scrollX)
            assertEquals(Themes.DARK.backgroundColor, (bar.background as ColorDrawable).color)
            val chips = descendants(bar).filterIsInstance<TextView>().toList()
            assertEquals(Themes.DARK.accentTextColor, chips.first().currentTextColor)
            assertEquals(Themes.DARK.keyTextColor, chips.last().currentTextColor)
            keyboard.showEmojiPicker()
            assertTrue(keyboard.isEmojiOpen())
            RuntimeEnvironment.setQualifiers("+notnight")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            assertFalse(keyboard.isEmojiOpen())
            assertKeyThemes(keyboard, Themes.LIGHT)
        } finally {
            controller.destroy()
        }
    }

    /** Recolors all overlay surfaces without replacing or rebinding the privileged session. */
    @Test
    @Config(shadows = [ShadowShizukuStartupApi::class])
    fun overlayRecolorsKeysChromeShortcutsAndDiagnosticsWithoutReconnecting() {
        resetDiagnosticFileProviderCache()
        ShadowShizukuStartupApi.bindCount = 0
        ShadowShizukuStartupApi.unbindCount = 0
        ShadowShizukuStartupApi.connection = null
        ShadowShizukuStartupApi.boundTags.clear()
        ShadowShizukuStartupApi.removedTags.clear()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = UuKeyboardOverlayService.UU_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = UuKeyboardOverlayService.UU_PACKAGE
                uid = 12345
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val repository = ThemeRepository(app).apply { followSystemTheme = true }
        val controller = Robolectric.buildService(UuKeyboardOverlayService::class.java,
            Intent(app, UuKeyboardOverlayService::class.java).setAction(UuKeyboardOverlayService.ACTION_START))
            .create().startCommand(0, 1)
        val service = controller.get()
        val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        val panel = windows.views.single()
        val bridge = ReflectionHelpers.getField<Any>(service, "bridge")
        val worker = ReflectionHelpers.getField<ExecutorService>(bridge, "worker")
        val endpoint = NoInputEndpoint()
        try {
            requireNotNull(ShadowShizukuStartupApi.connection).onServiceConnected(
                ComponentName(service, SystemInputService::class.java), endpoint)
            worker.submit { }.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SystemConnectionState.CONNECTED, SystemKeyboardDiagnostics.snapshot().connection)
            assertEquals(1, endpoint.checkCount)
            assertOverlayTheme(panel, service, Themes.LIGHT)
            assertEquals(1, ShadowShizukuStartupApi.bindCount)
            RuntimeEnvironment.setQualifiers("+night")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            shadowOf(Looper.getMainLooper()).idle()
            assertOverlayTheme(panel, service, Themes.DARK)
            repository.selectTheme(Themes.BLACK.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertOverlayTheme(panel, service, Themes.BLACK)
            repository.followSystemTheme = true
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit { }.get(2, TimeUnit.SECONDS)
            assertOverlayTheme(panel, service, Themes.DARK)
            assertSame(bridge, ReflectionHelpers.getField<Any>(service, "bridge"))
            assertSame(endpoint, ReflectionHelpers.getField<ISystemInputService>(bridge, "service"))
            assertEquals(SystemConnectionState.CONNECTED, SystemKeyboardDiagnostics.snapshot().connection)
            assertEquals(0, endpoint.sendCount)
            assertEquals(1, ShadowShizukuStartupApi.bindCount)
            assertEquals(0, ShadowShizukuStartupApi.unbindCount)
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        repository.selectTheme(Themes.LIGHT.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isEmpty())
        assertEquals(1, ShadowShizukuStartupApi.bindCount)
    }

    /** Models an established HID service connection without registering devices or sending input. */
    private class NoInputEndpoint : ISystemInputService.Stub() {
        var checkCount = 0
        var sendCount = 0

        /** Provides capability evidence only, allowing configuration checks to complete normally. */
        override fun checkCapabilities(targetUid: Int, displayId: Int): Int {
            checkCount++
            return SystemInputStatus.READY
        }

        /** Detects accidental key generation while performing no external input operation. */
        override fun sendKey(keyCode: Int, metaState: Int, targetUid: Int, displayId: Int): Int {
            sendCount++
            return SystemInputStatus.INVALID_KEY
        }

        /** Leaves this local Binder available until the test discards it. */
        override fun destroy() = Unit
    }

    /** Checks keyboard models and independently colored auxiliary surfaces against the resolved theme. */
    private fun assertOverlayTheme(panel: View, service: UuKeyboardOverlayService, theme: KeyboardTheme) {
        assertKeyThemes(panel, theme)
        val header = ReflectionHelpers.getField<View>(service, "header")
        assertEquals(theme.backgroundColor, (header.background as ColorDrawable).color)
        val shortcuts = descendants(panel).filterIsInstance<RemoteShortcutBar>().single()
        assertEquals(theme.backgroundColor, (shortcuts.background as ColorDrawable).color)
        val diagnostics = descendants(panel).filterIsInstance<SystemKeyboardDiagnosticsPanel>().single()
        assertEquals(theme.backgroundColor, (diagnostics.background as ColorDrawable).color)
        assertEquals(theme.keyTextColor, descendants(diagnostics).filterIsInstance<TextView>()
            .first { it !is Button }.currentTextColor)
        val expectedButtonText = if (theme.keyStyle == KeyStyle.MAGIC) theme.keyTextColor else theme.modifierTextColor
        descendants(panel).filterIsInstance<Button>().forEach { assertEquals(expectedButtonText, it.currentTextColor) }
    }

    /** Sets realistic framework editor metadata while avoiding text suggestions or dictionary warmup. */
    private fun startEditor(service: PcKeyboardService, extras: Bundle? = null) {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_NULL
            packageName = "test.editor"
            this.extras = extras
        }
        ReflectionHelpers.setField(service, "mInputEditorInfo", editor)
        service.onStartInputView(editor, false)
    }

    /** Verifies every mounted key, not merely the repository's selected theme identifier. */
    private fun assertKeyThemes(root: View, theme: KeyboardTheme) {
        val keys = descendants(root).filterIsInstance<KeyView>().toList()
        assertTrue(keys.isNotEmpty())
        keys.forEach { assertEquals(theme, it.theme) }
    }

    /** Traverses actual Android children including shortcut controls and stacked arrow groups. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
