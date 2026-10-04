package com.pckeyboard.ime.dispatch

import android.view.KeyEvent
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierSide
import com.pckeyboard.ime.model.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises the same visible-key route used by the service, including printable RAW keys. */
class RawKeyRouterTest {

    /** Protects all letters against accidental text routing and missing up/down scan codes. */
    @Test
    fun routesEveryLowercaseAndUppercaseLetter() {
        val expectedScans = listOf(
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44,
        )
        for ((index, character) in ('a'..'z').withIndex()) {
            for (uppercase in listOf(false, true)) {
                val modifiers = ModifierState().apply { if (uppercase) tapShift() }
                val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.letter("$character"), modifiers))
                val metaState = if (uppercase) SHIFT_META else 0
                assertEquals(RawKeyStroke(KeyEvent.KEYCODE_A + index, metaState), stroke)
                val pair = rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
                    .filter { it.keyCode == stroke.keyCode }
                assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), pair.map { it.action })
                assertEquals(listOf(expectedScans[index], expectedScans[index]), pair.map { it.scanCode })
                assertEquals(listOf(metaState, metaState), pair.map { it.metaState })

                if (uppercase) {
                    assertEquals(
                        stroke,
                        RawKeyRouter.strokeFor(Key.char(character.uppercase()), ModifierState()),
                    )
                }
            }
        }
    }

    /** Keeps every digit and shifted US symbol on its physical key position. */
    @Test
    fun routesDigitsAndAllPrintableUsPunctuation() {
        val shiftedDigits = ")!@#$%^&*("
        for (digit in 0..9) {
            val keyCode = KeyEvent.KEYCODE_0 + digit
            val scanCode = if (digit == 0) 11 else digit + 1
            assertCharacterStroke(('0' + digit).toString(), keyCode, scanCode)
            assertCharacterStroke(shiftedDigits[digit].toString(), keyCode, scanCode, SHIFT_META)
        }
        val pairs = listOf(
            Triple("`~", KeyEvent.KEYCODE_GRAVE, 41),
            Triple("-_", KeyEvent.KEYCODE_MINUS, 12),
            Triple("=+", KeyEvent.KEYCODE_EQUALS, 13),
            Triple("[{", KeyEvent.KEYCODE_LEFT_BRACKET, 26),
            Triple("]}", KeyEvent.KEYCODE_RIGHT_BRACKET, 27),
            Triple("\\|", KeyEvent.KEYCODE_BACKSLASH, 43),
            Triple(";:", KeyEvent.KEYCODE_SEMICOLON, 39),
            Triple("'\"", KeyEvent.KEYCODE_APOSTROPHE, 40),
            Triple(",<", KeyEvent.KEYCODE_COMMA, 51),
            Triple(".>", KeyEvent.KEYCODE_PERIOD, 52),
            Triple("/?", KeyEvent.KEYCODE_SLASH, 53),
        )
        for ((characters, keyCode, scanCode) in pairs) {
            assertCharacterStroke(characters[0].toString(), keyCode, scanCode)
            assertCharacterStroke(characters[1].toString(), keyCode, scanCode, SHIFT_META)
            assertEquals(
                RawKeyStroke(keyCode, SHIFT_META),
                RawKeyRouter.strokeFor(
                    Key.char(characters[0].toString(), shift = characters[1].toString()),
                    ModifierState().apply { tapShift() },
                ),
            )
        }
    }

    /** Ensures space and navigation never take editor actions or local composing shortcuts. */
    @Test
    fun routesWhitespaceEditingNavigationAndFunctionKeys() {
        val keys = listOf(
            Triple(KeyType.SPACE, KeyEvent.KEYCODE_SPACE, 57),
            Triple(KeyType.ENTER, KeyEvent.KEYCODE_ENTER, 28),
            Triple(KeyType.BACKSPACE, KeyEvent.KEYCODE_DEL, 14),
            Triple(KeyType.DELETE, KeyEvent.KEYCODE_FORWARD_DEL, 111),
            Triple(KeyType.TAB, KeyEvent.KEYCODE_TAB, 15),
            Triple(KeyType.ESC, KeyEvent.KEYCODE_ESCAPE, 1),
            Triple(KeyType.ARROW_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, 105),
            Triple(KeyType.ARROW_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, 106),
            Triple(KeyType.ARROW_UP, KeyEvent.KEYCODE_DPAD_UP, 103),
            Triple(KeyType.ARROW_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 108),
            Triple(KeyType.HOME, KeyEvent.KEYCODE_MOVE_HOME, 102),
            Triple(KeyType.END, KeyEvent.KEYCODE_MOVE_END, 107),
            Triple(KeyType.PAGE_UP, KeyEvent.KEYCODE_PAGE_UP, 104),
            Triple(KeyType.PAGE_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 109),
            Triple(KeyType.INSERT, KeyEvent.KEYCODE_INSERT, 110),
        )
        for ((type, keyCode, scanCode) in keys) {
            val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.fn("key", type), ModifierState()))
            assertEquals(RawKeyStroke(keyCode), stroke)
            assertEquals(listOf(scanCode, scanCode), rawKeyEventPlan(keyCode, 0).map { it.scanCode })
        }
        val functionScans = (59..68).toList() + listOf(87, 88)
        for ((index, scanCode) in functionScans.withIndex()) {
            val keyCode = KeyEvent.KEYCODE_F1 + index
            val stroke = RawKeyRouter.strokeFor(Key.fn("F", KeyType.FN, keyCode), ModifierState())
            assertEquals(RawKeyStroke(keyCode), stroke)
            assertEquals(scanCode, RawScanCodeMapper.forKeyCode(keyCode))
        }
    }

    /** Checks that shortcut modifier scan codes preserve left and right desktop keys. */
    @Test
    fun routesCtrlAltMetaAndFnShortcutsWithoutAltTextSubstitution() {
        val variants = listOf(
            ModifierState().apply { tapCtrl() } to 29,
            ModifierState().apply { tapAlt(ModifierSide.LEFT) } to 56,
            ModifierState().apply { tapAlt(ModifierSide.RIGHT) } to 100,
            ModifierState().apply { tapMeta(ModifierSide.LEFT) } to 125,
            ModifierState().apply { tapMeta(ModifierSide.RIGHT) } to 126,
            ModifierState().apply { tapFn() } to 464,
        )
        for ((modifiers, modifierScan) in variants) {
            val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.letter("c", alt = "©"), modifiers))
            assertEquals(RawKeyStroke(KeyEvent.KEYCODE_C, modifiers.toMetaState()), stroke)
            val plan = rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
            assertEquals(listOf(modifierScan, 46, 46, modifierScan), plan.map { it.scanCode })
            assertEquals(0, plan.last().metaState)
        }
    }

    /** Leaves local menus and unsupported glyphs to their explicit handlers without text fallback. */
    @Test
    fun excludesLocalActionsAndUnsupportedText() {
        for (type in listOf(
            KeyType.SYMBOL_SWITCH, KeyType.LAYOUT_SWITCH, KeyType.LANGUAGE_SWITCH,
            KeyType.EMOJI, KeyType.HIDE, KeyType.SETTINGS, KeyType.SHIFT, KeyType.CTRL,
            KeyType.ALT, KeyType.META, KeyType.CAPS_LOCK,
        )) {
            assertNull(RawKeyRouter.strokeFor(Key.fn("action", type), ModifierState()))
        }
        assertNull(RawKeyRouter.strokeFor(Key.fn("fn", KeyType.FN), ModifierState()))
        assertNull(RawKeyRouter.strokeFor(Key.char("你"), ModifierState()))
        assertNull(RawKeyRouter.strokeFor(Key.char("ni"), ModifierState()))
        assertEquals(0, RawScanCodeMapper.forKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }

    /** Verifies direct character labels produce the same key identity on press and release. */
    private fun assertCharacterStroke(label: String, keyCode: Int, scanCode: Int, metaState: Int = 0) {
        val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.char(label), ModifierState()))
        assertEquals(RawKeyStroke(keyCode, metaState), stroke)
        assertEquals(
            listOf(scanCode, scanCode),
            rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
                .filter { it.keyCode == keyCode }.map { it.scanCode },
        )
    }

    companion object {
        private const val SHIFT_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
