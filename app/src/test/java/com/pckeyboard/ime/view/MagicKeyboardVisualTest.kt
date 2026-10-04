package com.pckeyboard.ime.view

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.layout.LayoutBlocks
import com.pckeyboard.ime.layout.LayoutRegistry
import com.pckeyboard.ime.layout.LayoutSelector
import com.pckeyboard.ime.layout.LayoutVariant
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.remote.UuKeyboardOverlayService
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.theme.KeyboardTheme
import com.pckeyboard.ime.theme.ThemeRepository
import com.pckeyboard.ime.theme.Themes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File

/** Renders production Android canvases to verify Magic keycaps at phone and foldable widths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MagicKeyboardVisualTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity

    /** Uses deterministic sizing preferences while retaining the real Android view hierarchy. */
    @Before
    fun createActivity() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        KeyboardPrefs(activity).apply {
            splitEnabled = false
            sideSplitEnabled = false
            horizontalPadding = 0f
            heightScale = 1f
        }
    }

    /** Removes production callbacks and views after each native rendering test. */
    @After
    fun destroyActivity() {
        controller.pause().stop().destroy()
    }

    /** Keeps large action keys white and verifies real labels render on the foldable layout. */
    @Test
    fun foldableKeycapsAreWhiteWithVisibleLegends() {
        val keyboard = keyboard(1200)
        val image = render(keyboard)
        assertEquals(Color.TRANSPARENT, image.getPixel(600, 40))
        listOf(KeyType.SPACE, KeyType.ENTER, KeyType.BACKSPACE).forEach { type ->
            val key = keys(keyboard).single { it.key.type == type }
            assertTrue("$type must use a neutral white keycap", whiteFaceFraction(render(key)) > 0.6)
        }
        listOf("a", "1", "F1", "PageUp").forEach { label ->
            val key = keys(keyboard).single { it.key.label == label }
            assertTrue("$label must contain a drawn legend", darkInteriorPixels(render(key)) > 5)
        }
        assertArrowGeometry(keyboard)
        export(image, "magic-keyboard-wide")
    }

    /** Checks that narrow keyboards retain visible navigation labels and contained hit targets. */
    @Test
    fun phoneLayoutKeepsKeysAndLegendsInsideItsBounds() {
        val keyboard = keyboard(420)
        keys(keyboard).forEach { key ->
            val bounds = bounds(keyboard, key)
            assertTrue("${key.key.label} has a hit target", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("${key.key.label} stays within keyboard", Rect(0, 0, 420, 450).contains(bounds))
        }
        listOf("a", "PageUp", "PageDn").forEach { label ->
            assertTrue(
                "$label must render at phone width",
                darkInteriorPixels(render(keys(keyboard).single { it.key.label == label })) > 2,
            )
        }
        assertArrowGeometry(keyboard)
        export(render(keyboard), "magic-keyboard-narrow")
    }

    /** Distinguishes pressed, armed, and locked modifiers without changing the submitted key model. */
    @Test
    fun modifierFeedbackRemainsVisibleAndDoesNotAlterInput() {
        val keyboard = keyboard(1200)
        val command = keys(keyboard).first { it.key.type == KeyType.META }
        val letter = keys(keyboard).single { it.key.label == "c" }
        val originalKey = letter.key.copy()
        val normal = render(command)
        command.isDown = true
        val pressed = render(command)
        assertFalse("Pressed feedback must differ from idle", normal.sameAs(pressed))
        export(render(keyboard), "magic-keyboard-pressed")
        command.isDown = false
        command.modifiers.tapMeta()
        val armed = render(command)
        assertFalse("One-shot modifier must remain visible", normal.sameAs(armed))
        export(render(keyboard), "magic-keyboard-armed")
        command.modifiers.tapMeta()
        val locked = render(command)
        assertFalse("Lock must differ from one-shot", armed.sameAs(locked))
        export(render(keyboard), "magic-keyboard-locked")

        keyboard.resetModifiers()
        val sentKeys = mutableListOf<Key>()
        keyboard.listener = recordingListener(sentKeys)
        keyboard.onKeyDown(letter)
        keyboard.onKeyUp(letter)
        assertEquals(originalKey, letter.key)
        assertEquals(listOf(originalKey), sentKeys)
        assertEquals("c", sentKeys.single().label)
    }

    /** Retains existing accent-filled action keys for themes that use the classic renderer. */
    @Test
    fun classicThemeRetainsItsActionKeyAppearance() {
        val keyboard = keyboard(1200, Themes.BLACK)
        val space = keys(keyboard).single { it.key.type == KeyType.SPACE }
        val image = render(space)
        assertEquals(Themes.BLACK.accentColor, image.getPixel(image.width / 4, image.height / 2))
    }

    /** Renders black keycaps with readable white engravings at foldable and phone widths. */
    @Test
    fun darkMagicKeycapsKeepWhiteLegendsAndNeutralActionKeys() {
        listOf(1200 to "wide", 420 to "narrow").forEach { (width, name) ->
            val keyboard = keyboard(width, Themes.DARK)
            assertArrowGeometry(keyboard)
            listOf(KeyType.SPACE, KeyType.ENTER, KeyType.BACKSPACE).forEach { type ->
                val key = keys(keyboard).single { it.key.type == type }
                val image = render(key)
                assertEquals(Themes.DARK.keyBackgroundColor, image.getPixel(image.width / 2, 10))
            }
            listOf("a", "PageUp", "PageDn").forEach { label ->
                assertTrue("$label must have a white engraving", brightInteriorPixels(
                    render(keys(keyboard).single { it.key.label == label }),
                ) > 2)
            }
            export(render(keyboard), "magic-keyboard-black-$name")
        }
    }

    /** Preserves visible press, latch, and Caps Lock feedback against black keycaps. */
    @Test
    fun darkMagicModifierStatesRemainDistinct() {
        val keyboard = keyboard(1200, Themes.DARK)
        val command = keys(keyboard).first { it.key.type == KeyType.META }
        val normal = render(command)
        command.isDown = true
        assertFalse(normal.sameAs(render(command)))
        export(render(keyboard), "magic-keyboard-black-pressed")
        command.isDown = false
        command.modifiers.tapMeta()
        val armed = render(command)
        assertFalse(normal.sameAs(armed))
        command.modifiers.tapMeta()
        assertFalse(armed.sameAs(render(command)))
        val caps = keys(keyboard).single { it.key.type == KeyType.CAPS_LOCK }
        val capsOff = render(caps)
        caps.modifiers.toggleCapsLock()
        assertFalse(capsOff.sameAs(render(caps)))
        export(render(keyboard), "magic-keyboard-black-locked")
    }

    /** Removes only floating-window headroom without changing IME defaults or key hit targets. */
    @Test
    fun compactOverlayRemovesReservedPopupHeight() {
        val keyboard = keyboard(1200)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        keyboard.measure(exact(1200), heightSpec)
        val imeHeight = keyboard.measuredHeight
        keyboard.setCompactOverlayMode()
        keyboard.measure(exact(1200), heightSpec)
        keyboard.layout(0, 0, 1200, keyboard.measuredHeight)
        assertEquals(imeHeight - 90, keyboard.measuredHeight)
        assertTrue(keys(keyboard).all { it.height > 0 })
        assertTrue(bounds(keyboard, keys(keyboard).first()).top < 10)
        assertArrowGeometry(keyboard)
        export(render(keyboard), "magic-keyboard-compact", cropPopupZone = false)
    }

    /** Renders the actual floating toolbar and keyboard together without creating any injection endpoint. */
    @Test
    @Config(qualifiers = "w1200dp-h800dp-land-mdpi")
    fun floatingKeyboardChromeUsesTheSelectedMagicTheme() {
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = UuKeyboardOverlayService.UU_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = UuKeyboardOverlayService.UU_PACKAGE
                uid = 12345
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(activity.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        KeyboardPrefs(activity).apply {
            keyboardPlatform = KeyboardPlatform.MAC
            uuShortcutBar = true
            showFunctionRow = true
        }
        ThemeRepository(activity).selectTheme(Themes.MAGIC.id)
        val intent = Intent(activity, UuKeyboardOverlayService::class.java)
            .setAction(UuKeyboardOverlayService.ACTION_START)
        val serviceController = Robolectric.buildService(UuKeyboardOverlayService::class.java, intent)
            .create().startCommand(0, 1)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val windows = Shadow.extract<ShadowWindowManagerImpl>(
                serviceController.get().getSystemService(WindowManager::class.java),
            )
            val panel = windows.views.single {
                (it.layoutParams as? WindowManager.LayoutParams)?.type ==
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            panel.measure(exact(1200), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            assertTrue(panel.height in 1..700)
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(keys(panel).isNotEmpty())
            assertTrue(keys(panel).all { it.theme == Themes.MAGIC })
            export(render(panel), "magic-keyboard-overlay", cropPopupZone = false)
            ThemeRepository(activity).selectTheme(Themes.DARK.id)
            shadowOf(Looper.getMainLooper()).idle()
            panel.measure(exact(1200), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            assertTrue(keys(panel).isNotEmpty())
            assertTrue(keys(panel).all { it.theme == Themes.DARK })
            export(render(panel), "magic-keyboard-black-overlay", cropPopupZone = false)
        } finally {
            serviceController.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Builds the same full English Mac layout used by the remote keyboard overlay. */
    private fun keyboard(width: Int, theme: KeyboardTheme = Themes.MAGIC): KeyboardView {
        val layout = LayoutSelector.apply(
            LayoutBlocks.applyPlatform(LayoutRegistry.get("en_US").main, KeyboardPlatform.MAC),
            LayoutVariant.FULL,
        )
        return KeyboardView(activity).apply {
            bind(layout, theme)
            activity.setContentView(this)
            measure(exact(width), exact(450))
            layout(0, 0, width, 450)
        }
    }

    /** Confirms the up arrow remains above down in the existing inverted-T arrangement. */
    private fun assertArrowGeometry(keyboard: KeyboardView) {
        val up = bounds(keyboard, keys(keyboard).single { it.key.type == KeyType.ARROW_UP })
        val down = bounds(keyboard, keys(keyboard).single { it.key.type == KeyType.ARROW_DOWN })
        val left = bounds(keyboard, keys(keyboard).single { it.key.type == KeyType.ARROW_LEFT })
        val right = bounds(keyboard, keys(keyboard).single { it.key.type == KeyType.ARROW_RIGHT })
        assertTrue("Up and down must align", kotlin.math.abs(up.centerX() - down.centerX()) <= 1)
        assertTrue("Up must be above down", up.bottom <= down.top)
        assertTrue("Left must precede down", left.right <= down.left)
        assertTrue("Right must follow down", down.right <= right.left)
    }

    /** Converts a nested key's measured bounds to keyboard-local coordinates. */
    private fun bounds(keyboard: KeyboardView, key: KeyView): Rect = Rect(0, 0, key.width, key.height).apply {
        keyboard.offsetDescendantRectToMyCoords(key, this)
    }

    /** Traverses real child containers, including stacked arrow groups. */
    private fun keys(root: View): List<KeyView> = when (root) {
        is KeyView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { keys(root.getChildAt(it)) }
        else -> emptyList()
    }

    /** Draws an already measured production view using Robolectric's native Skia implementation. */
    private fun render(view: View): Bitmap = Bitmap.createBitmap(
        view.width, view.height, Bitmap.Config.ARGB_8888,
    ).also { view.draw(Canvas(it)) }

    /** Measures neutral white coverage away from rounded borders and shadows. */
    private fun whiteFaceFraction(image: Bitmap): Double {
        val inset = 8
        var white = 0
        var total = 0
        for (y in inset until image.height - inset) {
            for (x in inset until image.width - inset) {
                val pixel = image.getPixel(x, y)
                val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (Color.alpha(pixel) == 255 && channels.min() >= 230 &&
                    channels.max() - channels.min() <= 8
                ) white++
                total++
            }
        }
        return white.toDouble() / total
    }

    /** Counts dark interior pixels so an empty or broken native text render cannot pass. */
    private fun darkInteriorPixels(image: Bitmap): Int {
        var count = 0
        for (y in 5 until image.height - 5) {
            for (x in 5 until image.width - 5) {
                val pixel = image.getPixel(x, y)
                if (Color.alpha(pixel) > 200 && Color.red(pixel) < 150 &&
                    Color.green(pixel) < 150 && Color.blue(pixel) < 150
                ) count++
            }
        }
        return count
    }

    /** Counts white engravings inside dark keys while excluding keycap borders. */
    private fun brightInteriorPixels(image: Bitmap): Int {
        var count = 0
        for (y in 5 until image.height - 5) {
            for (x in 5 until image.width - 5) {
                val pixel = image.getPixel(x, y)
                if (Color.alpha(pixel) > 200 && Color.red(pixel) > 180 &&
                    Color.green(pixel) > 180 && Color.blue(pixel) > 180
                ) count++
            }
        }
        return count
    }

    /** Writes full and key-only native screenshots only when explicitly requested by the caller. */
    private fun export(image: Bitmap, name: String, cropPopupZone: Boolean = true) {
        val outputPath = System.getenv("PCK_VISUAL_OUTPUT_DIR")?.takeIf { it.isNotBlank() } ?: return
        val directory = File(outputPath)
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        if (!cropPopupZone) return
        val cropped = Bitmap.createBitmap(image, 0, 90, image.width, image.height - 90)
        File(directory, "$name-keys.png").outputStream().use { output ->
            check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    /** Records submitted keys without replacing the production gesture dispatcher. */
    private fun recordingListener(keys: MutableList<Key>): KeyboardView.Listener =
        object : KeyboardView.Listener {
            override fun onKey(key: Key, modifiers: ModifierState) { keys += key }
            override fun onCursorMove(dx: Int, dy: Int) = Unit
            override fun onMenuAction(action: MenuAction) = Unit
            override fun onText(text: String) = Unit
            override fun onSuggestionPicked(word: String) = Unit
            override fun onClipboardEdit(text: String) = Unit
            override fun onOpenAppSettings() = Unit
        }

    /** Creates a fixed-size Android measure specification in mdpi pixels. */
    private fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
