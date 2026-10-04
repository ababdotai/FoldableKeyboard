package com.pckeyboard.ime.remote

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import com.pckeyboard.ime.R
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.theme.ThemeRepository
import com.pckeyboard.ime.theme.Themes
import com.pckeyboard.ime.view.KeyboardView
import com.pckeyboard.ime.view.KeyView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import kotlin.math.roundToInt

/** Exercises docked window sizing with native layout and no privileged key injection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UuDockIntegrationTest {
    /** Grants only local window prerequisites and selects the complete Mac layout. */
    @Before
    fun prepareOverlay() {
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
        KeyboardPrefs(application).apply {
            keyboardPlatform = KeyboardPlatform.MAC
            showFunctionRow = true
            uuShortcutBar = true
            splitEnabled = false
            sideSplitEnabled = false
            horizontalPadding = 0f
            heightScale = 1f
        }
        ThemeRepository(application).selectTheme(Themes.MAGIC.id)
    }

    /** Retains floating defaults while allowing an explicit full-width dock and reversible collapse. */
    @Test
    fun modeSwitchCollapseAndNotificationRestoreKeepDockedRegion() {
        val controller = startOverlay()
        val service = controller.get()
        val panel = panel(service)
        val prefs = KeyboardPrefs(service)
        try {
            assertFalse(prefs.uuOverlayDocked)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params(panel).height)
            assertTrue(params(panel).width < usableSize(service).first)
            click(panel, service.getString(R.string.uu_overlay_dock))
            measurePanel(panel)
            assertTrue(prefs.uuOverlayDocked)
            assertDocked(service, panel)
            val dockHeight = params(panel).height
            click(panel, service.getString(R.string.uu_overlay_collapse))
            measurePanel(panel)
            assertEquals(56, params(panel).width)
            assertTrue(descendants(panel).filterIsInstance<KeyboardView>().none())
            service.onStartCommand(Intent(service, UuKeyboardOverlayService::class.java)
                .setAction(UuKeyboardOverlayService.ACTION_SHOW), 0, 2)
            measurePanel(panel)
            assertEquals(dockHeight, params(panel).height)
            assertDocked(service, panel)
            click(panel, service.getString(R.string.uu_overlay_float))
            measurePanel(panel)
            assertFalse(prefs.uuOverlayDocked)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params(panel).height)
        } finally {
            controller.destroy()
        }
    }

    /** Persists orientation-specific calibration, including across service recreation. */
    @Test
    fun calibrationSurvivesRestartAndRotatesIndependently() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = KeyboardPrefs(app).apply {
            uuOverlayDocked = true
            setUuDockHeightRatio(false, 0.35f)
            setUuDockHeightRatio(true, 0.6f)
        }
        val controller = startOverlay()
        try {
            val service = controller.get()
            val panel = panel(service)
            measurePanel(panel)
            assertDocked(service, panel)
            val landscapeHeight = params(panel).height
            assertEquals((usableSize(service).second * 0.6f).roundToInt(), landscapeHeight)
            click(panel, service.getString(R.string.uu_overlay_dock_grow))
            measurePanel(panel)
            assertTrue(params(panel).height > landscapeHeight)
            assertTrue(prefs.uuDockHeightRatio(true) > 0.6f)
            assertEquals(0.35f, prefs.uuDockHeightRatio(false), 0.001f)
            RuntimeEnvironment.setQualifiers("w800dp-h1200dp-port-mdpi")
            service.onConfigurationChanged(Configuration(service.resources.configuration))
            measurePanel(panel)
            assertDocked(service, panel)
            assertEquals((usableSize(service).second * 0.35f).roundToInt(), params(panel).height)
            assertEquals(0.35f, prefs.uuDockHeightRatio(false), 0.001f)
            click(panel, service.getString(R.string.uu_overlay_dock_shrink))
            measurePanel(panel)
            assertTrue(prefs.uuDockHeightRatio(false) < 0.35f)
        } finally {
            controller.destroy()
        }
        val freshPrefs = KeyboardPrefs(app)
        assertTrue(freshPrefs.uuOverlayDocked)
        assertEquals(prefs.uuDockHeightRatio(true), freshPrefs.uuDockHeightRatio(true), 0f)
        assertEquals(prefs.uuDockHeightRatio(false), freshPrefs.uuDockHeightRatio(false), 0f)
        val restarted = startOverlay()
        try {
            val panel = panel(restarted.get())
            measurePanel(panel)
            assertDocked(restarted.get(), panel)
        } finally {
            restarted.destroy()
        }
    }

    /** Rejects corrupted calibration values instead of producing invalid layout dimensions. */
    @Test
    fun preferencesBoundInvalidCalibrationWithoutCrossingOrientations() {
        val prefs = KeyboardPrefs(RuntimeEnvironment.getApplication())
        prefs.setUuDockHeightRatio(true, 0.6f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f, 10f).forEach { value ->
            prefs.setUuDockHeightRatio(false, value)
            assertTrue(prefs.uuDockHeightRatio(false).isFinite())
            assertTrue(prefs.uuDockHeightRatio(false) in 0.15f..0.85f)
            assertEquals(0.6f, prefs.uuDockHeightRatio(true), 0f)
        }
    }

    /** Keeps diagnostic expansion inside the calibrated region, then restores all key hit targets. */
    @Test
    fun diagnosticsReplaceContentWithoutIncreasingDockHeight() {
        KeyboardPrefs(RuntimeEnvironment.getApplication()).uuOverlayDocked = true
        val controller = startOverlay()
        try {
            val service = controller.get()
            val panel = panel(service)
            measurePanel(panel)
            val height = params(panel).height
            val top = params(panel).y
            click(panel, service.getString(R.string.uu_diagnostics_toggle))
            measurePanel(panel)
            assertEquals(height, params(panel).height)
            assertEquals(top, params(panel).y)
            assertEquals(height, panel.height)
            val diagnostics = descendants(panel).filterIsInstance<SystemKeyboardDiagnosticsPanel>().single()
            assertEquals(View.VISIBLE, diagnostics.visibility)
            assertTrue(diagnostics.height > 0)
            assertContained(panel, diagnostics)
            assertFalse(descendants(panel).filterIsInstance<KeyboardView>().any { it.isShown })
            export(panel, "dock-diagnostics-wide")
            click(panel, service.getString(R.string.uu_diagnostics_toggle))
            measurePanel(panel)
            assertEquals(height, params(panel).height)
            assertContainedKeys(panel)
            export(panel, "dock-magic-wide")
        } finally {
            controller.destroy()
        }
    }

    /** Fits the full keyboard in a narrow portrait dock instead of clipping lower rows. */
    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun narrowDockKeepsEveryKeyInsideTheCalibratedWindow() {
        assertNativeDock("dock-magic-320")
    }

    /** Shrinks key rows for a short landscape window without covering space above the dock. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun shortLandscapeKeepsEveryKeyInsideTheCalibratedWindow() {
        assertNativeDock("dock-magic-short")
    }

    /** Honors the dock limit even when ordinary input-method sizing requests the largest keys. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun largestImeHeightCannotExpandAMinimumDock() {
        KeyboardPrefs(RuntimeEnvironment.getApplication()).apply {
            heightScale = 1.6f
            setUuDockHeightRatio(true, 0.15f)
        }
        assertNativeDock("dock-magic-short-minimum")
    }

    /** Starts and renders a docked surface without ever sending a key to its bridge. */
    private fun assertNativeDock(name: String) {
        KeyboardPrefs(RuntimeEnvironment.getApplication()).uuOverlayDocked = true
        val controller = startOverlay()
        try {
            val service = controller.get()
            val panel = panel(service)
            measurePanel(panel)
            assertDocked(service, panel)
            assertContainedKeys(panel)
            export(panel, name)
        } finally {
            controller.destroy()
        }
    }

    /** Asserts bottom attachment within the system-bar-fitted coordinate frame. */
    private fun assertDocked(service: UuKeyboardOverlayService, panel: View) {
        val (width, height) = usableSize(service)
        val window = params(panel)
        assertEquals(width, window.width)
        assertEquals(0, window.x)
        assertEquals(height, window.y + window.height)
        assertTrue(window.height in 1..(height * 0.85f).toInt())
        assertEquals(window.height, panel.height)
        assertTrue(window.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    /** Ensures actual letter, modifier, function, and arrow views retain nonempty hit targets. */
    private fun assertContainedKeys(panel: ViewGroup) {
        val keyboard = descendants(panel).filterIsInstance<KeyboardView>().single { it.isShown }
        assertContained(panel, keyboard)
        val keys = descendants(keyboard).filterIsInstance<KeyView>().toList()
        assertTrue(keys.size > 50)
        keys.forEach { assertContained(panel, it) }
    }

    /** Converts nested child bounds to the overlay's local coordinates before checking clipping. */
    private fun assertContained(panel: ViewGroup, child: View) {
        assertTrue("${child.javaClass.simpleName} must have a hit target", child.width > 0 && child.height > 0)
        val bounds = Rect(0, 0, child.width, child.height)
        panel.offsetDescendantRectToMyCoords(child, bounds)
        assertTrue("${child.javaClass.simpleName} $bounds exceeds ${panel.width}x${panel.height}",
            Rect(0, 0, panel.width, panel.height).contains(bounds))
    }

    /** Measures the real hierarchy using the exact dimensions requested from WindowManager. */
    private fun measurePanel(panel: View) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            val window = params(panel)
            panel.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(if (window.height > 0) window.height else 1600,
                    if (window.height > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Starts an authorized overlay with no Shizuku endpoint registered in this test. */
    private fun startOverlay(): ServiceController<UuKeyboardOverlayService> =
        Robolectric.buildService(UuKeyboardOverlayService::class.java,
            Intent(RuntimeEnvironment.getApplication(), UuKeyboardOverlayService::class.java)
                .setAction(UuKeyboardOverlayService.ACTION_START)).create().startCommand(0, 1).also {
            shadowOf(Looper.getMainLooper()).idle()
        }

    /** Resolves only the production overlay window created by this service. */
    private fun panel(service: UuKeyboardOverlayService): ViewGroup =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
            .views.single() as ViewGroup

    /** Returns the actual mutable parameters attached to the production window. */
    private fun params(panel: View): WindowManager.LayoutParams = panel.layoutParams as WindowManager.LayoutParams

    /** Mirrors the public fitted metrics contract without depending on private service fields. */
    private fun usableSize(service: UuKeyboardOverlayService): Pair<Int, Int> {
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return metrics.bounds.width() - insets.left - insets.right to
            metrics.bounds.height() - insets.top - insets.bottom
    }

    /** Activates a localized local control without touching keyboard keys. */
    private fun click(panel: View, label: String) {
        descendants(panel).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Walks the measured hierarchy including stacked Mac arrow groups. */
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    /** Exports native Skia screenshots only to the ignored local visual-validation directory. */
    private fun export(panel: View, name: String) {
        val directory = File("../temp/dock-visual").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
