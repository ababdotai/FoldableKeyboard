package com.pckeyboard.ime.dispatch

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the Phase 1 US physical-key mapping used by remote dispatch. */
class RawKeyMapperTest {

    /** Verifies lowercase and uppercase letters share a key code and differ by Shift. */
    @Test
    fun mapsAsciiLetters() {
        val lower = requireNotNull(RawKeyMapper.forCharacter('n'))
        val upper = requireNotNull(RawKeyMapper.forCharacter('N'))

        assertEquals(KeyEvent.KEYCODE_N, lower.keyCode)
        assertEquals(0, lower.requiredMetaState)
        assertEquals(KeyEvent.KEYCODE_N, upper.keyCode)
        assertTrue(upper.requiredMetaState and KeyEvent.META_SHIFT_ON != 0)
        assertTrue(upper.requiredMetaState and KeyEvent.META_SHIFT_LEFT_ON != 0)
    }

    /** Verifies all decimal digits map to Android's contiguous digit key-code range. */
    @Test
    fun mapsDigits() {
        for (digit in '0'..'9') {
            assertEquals(
                KeyEvent.KEYCODE_0 + (digit - '0'),
                RawKeyMapper.forCharacter(digit)?.keyCode,
            )
        }
    }

    /** Verifies the required punctuation and shifted punctuation use physical base keys. */
    @Test
    fun mapsPunctuationAndShiftPairs() {
        val pairs = mapOf(
            '!' to KeyEvent.KEYCODE_1,
            '@' to KeyEvent.KEYCODE_2,
            '{' to KeyEvent.KEYCODE_LEFT_BRACKET,
            ':' to KeyEvent.KEYCODE_SEMICOLON,
            '|' to KeyEvent.KEYCODE_BACKSLASH,
            '?' to KeyEvent.KEYCODE_SLASH,
        )

        for ((character, keyCode) in pairs) {
            val stroke = requireNotNull(RawKeyMapper.forCharacter(character))
            assertEquals(keyCode, stroke.keyCode)
            assertTrue(stroke.requiredMetaState and KeyEvent.META_SHIFT_ON != 0)
        }
        assertEquals(
            KeyEvent.KEYCODE_COMMA,
            RawKeyMapper.forCharacter(',')?.keyCode,
        )
    }

    /** Verifies Unicode characters never silently fall back to editor text insertion. */
    @Test
    fun rejectsUnmappedCharacters() {
        assertNull(RawKeyMapper.forCharacter('é'))
        assertNull(RawKeyMapper.forCharacter('你'))
    }

    /** Verifies remote shortcut modifiers are pressed in a stable physical order. */
    @Test
    fun ordersActiveModifiers() {
        val metaState = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or
            KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_META_LEFT,
            ),
            activeModifierKeyCodes(metaState),
        )
    }
}
