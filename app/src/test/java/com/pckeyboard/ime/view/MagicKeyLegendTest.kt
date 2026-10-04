package com.pckeyboard.ime.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Verifies fixed physical engravings without changing the underlying input model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MagicKeyLegendTest {
    /** Uses physical Latin capitals even when the device locale has special casing. */
    @Test
    fun uppercaseEngravingsDoNotChangeInputOrExposeAltHints() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val key = Key.letter("i", alt = "ï")
            assertEquals(MagicKeyLegend("I"), magicKeyLegend(key))
            assertEquals("i", key.label)
            assertEquals("ï", key.altLabel)
            assertEquals('i'.code, key.code)
        } finally {
            Locale.setDefault(before)
        }
    }

    /** Engraves shifted punctuation above its original character. */
    @Test
    fun punctuationKeepsBothPhysicalLegends() {
        val key = Key.char("1", "!", alt = "¡")
        assertEquals(MagicKeyLegend("!", "1"), magicKeyLegend(key))
        assertEquals("1", key.label)
        assertEquals("!", key.shiftLabel)
    }

    /** Keeps a blank Space visually while retaining a spoken label and the key event. */
    @Test
    fun blankSpaceRemainsAccessible() {
        val key = Key.fn("space", KeyType.SPACE, KeyEvent.KEYCODE_SPACE)
        val view = KeyView(RuntimeEnvironment.getApplication(), key, Themes.MAGIC, ModifierState())
        assertEquals(MagicKeyLegend(""), magicKeyLegend(key))
        assertEquals("Space", view.contentDescription)
        assertEquals(KeyEvent.KEYCODE_SPACE, view.key.keyCode)
    }

    /** Labels actual Mac modifiers without relabeling the Windows layout. */
    @Test
    fun modifierCaptionsMatchTheirPlatform() {
        assertEquals(MagicKeyLegend("⌃", "control"), magicKeyLegend(Key.fn("⌃", KeyType.CTRL)))
        assertEquals(MagicKeyLegend("⌥", "option"), magicKeyLegend(Key.fn("⌥", KeyType.ALT)))
        assertEquals(MagicKeyLegend("⌘", "command"), magicKeyLegend(Key.fn("⌘", KeyType.META)))
        assertEquals(MagicKeyLegend("Ctrl"), magicKeyLegend(Key.fn("Ctrl", KeyType.CTRL)))
        assertEquals(MagicKeyLegend("Alt"), magicKeyLegend(Key.fn("Alt", KeyType.ALT)))
    }

    /** Keeps navigation and action engravings honest rather than showing inactive media keys. */
    @Test
    fun actionLegendsPreserveRealFunctions() {
        assertEquals(MagicKeyLegend("page", "up"), magicKeyLegend(Key.fn("PageUp", KeyType.PAGE_UP)))
        assertEquals(MagicKeyLegend("page", "down"), magicKeyLegend(Key.fn("PageDn", KeyType.PAGE_DOWN)))
        assertEquals(MagicKeyLegend("F12"), magicKeyLegend(Key.fn("F12", KeyType.FN)))
        assertEquals("Forward Delete", magicKeyAccessibilityLabel(Key.fn("Del", KeyType.DELETE)))
    }

    /** Uses consistent thin arrows even when the layout uses filled directional glyphs. */
    @Test
    fun directionalEngravingsUseConsistentArrows() {
        assertEquals(MagicKeyLegend("←"), magicKeyLegend(Key.fn("◀", KeyType.ARROW_LEFT)))
        assertEquals(MagicKeyLegend("↑"), magicKeyLegend(Key.fn("▲", KeyType.ARROW_UP)))
        assertEquals(MagicKeyLegend("↓"), magicKeyLegend(Key.fn("▼", KeyType.ARROW_DOWN)))
        assertEquals(MagicKeyLegend("→"), magicKeyLegend(Key.fn("▶", KeyType.ARROW_RIGHT)))
    }

    /** Drawing pressed and sticky states must never consume modifiers or mutate key labels. */
    @Test
    fun renderingDoesNotChangeKeyOrModifierState() {
        val modifiers = ModifierState().apply { tapShift(); tapAlt() }
        val key = Key.letter("q", alt = "ä")
        val view = KeyView(RuntimeEnvironment.getApplication(), key, Themes.MAGIC, modifiers)
        view.layout(0, 0, 70, 40)
        val bitmap = Bitmap.createBitmap(70, 40, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        view.isDown = true
        view.draw(Canvas(bitmap))
        assertEquals(ModifierState.State.ONCE, modifiers.shift)
        assertEquals(ModifierState.State.ONCE, modifiers.alt)
        assertEquals("q", key.label)
        assertFalse(modifiers.capsLock)
        bitmap.recycle()
    }
}
