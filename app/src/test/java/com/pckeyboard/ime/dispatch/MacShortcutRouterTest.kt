package com.pckeyboard.ime.dispatch

import android.view.KeyEvent
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierSide
import com.pckeyboard.ime.model.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Protects Mac shortcut identity and complete physical-style modifier framing. */
class MacShortcutRouterTest {

    /** Checks editing, search, save, and redo shortcuts with either Command key. */
    @Test
    fun routesCommandLettersAndRedoWithBothCommandSides() {
        for (side in ModifierSide.entries) {
            val command = if (side == ModifierSide.LEFT) KeyEvent.KEYCODE_META_LEFT else KeyEvent.KEYCODE_META_RIGHT
            for (character in "acvxzsf") {
                assertShortcut(
                    Key.letter(character.toString()), ModifierState().apply { tapMeta(side) },
                    KeyEvent.KEYCODE_A + character.code - 'a'.code, listOf(command),
                )
            }
            assertShortcut(
                Key.letter("z"), ModifierState().apply { tapMeta(side); tapShift() },
                KeyEvent.KEYCODE_Z, listOf(KeyEvent.KEYCODE_SHIFT_LEFT, command),
            )
        }
    }

    /** Preserves shifted digit positions used by Mac screenshot shortcuts. */
    @Test
    fun routesCommandShiftScreenshotDigits() {
        for ((digit, symbol) in listOf('3' to "#", '4' to "$", '5' to "%")) {
            assertShortcut(
                Key.char(digit.toString(), shift = symbol),
                ModifierState().apply { tapMeta(); tapShift() },
                KeyEvent.KEYCODE_0 + digit.code - '0'.code,
                listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_META_LEFT),
            )
        }
    }

    /** Leaves Option word movement/deletion and Command line/document movement to the host. */
    @Test
    fun routesOptionAndCommandNavigationWithoutLocalSelection() {
        val navigation = listOf(
            KeyType.ARROW_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            KeyType.ARROW_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyType.ARROW_UP to KeyEvent.KEYCODE_DPAD_UP,
            KeyType.ARROW_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            KeyType.BACKSPACE to KeyEvent.KEYCODE_DEL,
        )
        for (side in ModifierSide.entries) {
            val option = if (side == ModifierSide.LEFT) KeyEvent.KEYCODE_ALT_LEFT else KeyEvent.KEYCODE_ALT_RIGHT
            val command = if (side == ModifierSide.LEFT) KeyEvent.KEYCODE_META_LEFT else KeyEvent.KEYCODE_META_RIGHT
            for ((type, code) in navigation) {
                assertShortcut(Key.fn("key", type), ModifierState().apply { tapAlt(side) }, code, listOf(option))
                assertShortcut(Key.fn("key", type), ModifierState().apply { tapMeta(side) }, code, listOf(command))
                assertShortcut(
                    Key.fn("key", type), ModifierState().apply { tapShift(); tapAlt(side) },
                    code, listOf(KeyEvent.KEYCODE_SHIFT_LEFT, option),
                )
            }
        }
    }

    /** Covers input-source switching, Spotlight, and Force Quit without AltGr character output. */
    @Test
    fun routesSpaceForceQuitAndOptionLetters() {
        assertShortcut(
            Key.fn("space", KeyType.SPACE), ModifierState().apply { tapCtrl() },
            KeyEvent.KEYCODE_SPACE, listOf(KeyEvent.KEYCODE_CTRL_LEFT),
        )
        assertShortcut(
            Key.fn("space", KeyType.SPACE), ModifierState().apply { tapMeta() },
            KeyEvent.KEYCODE_SPACE, listOf(KeyEvent.KEYCODE_META_LEFT),
        )
        assertShortcut(
            Key.fn("Esc", KeyType.ESC), ModifierState().apply { tapAlt(); tapMeta() },
            KeyEvent.KEYCODE_ESCAPE, listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT),
        )
        assertShortcut(
            Key.letter("c", alt = "©"), ModifierState().apply { tapAlt() },
            KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_ALT_LEFT),
        )
    }

    /** Expands Mac Fn navigation locally, removing only Fn from the transmitted shortcut. */
    @Test
    fun translatesMacFnNavigationWhileRetainingEveryOtherModifier() {
        val mappings = listOf(
            KeyType.BACKSPACE to KeyEvent.KEYCODE_FORWARD_DEL,
            KeyType.ARROW_LEFT to KeyEvent.KEYCODE_MOVE_HOME,
            KeyType.ARROW_RIGHT to KeyEvent.KEYCODE_MOVE_END,
            KeyType.ARROW_UP to KeyEvent.KEYCODE_PAGE_UP,
            KeyType.ARROW_DOWN to KeyEvent.KEYCODE_PAGE_DOWN,
        )
        for ((type, code) in mappings) {
            assertShortcut(Key.fn("key", type), ModifierState().apply { tapFn() }, code, emptyList())
            val modifiers = ModifierState().apply {
                tapFn(); tapShift(); tapCtrl(); tapAlt(ModifierSide.RIGHT); tapMeta(ModifierSide.RIGHT)
            }
            assertShortcut(
                Key.fn("key", type), modifiers, code,
                listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_RIGHT),
            )
            val stroke = requireNotNull(RawKeyRouter.strokeFor(Key.fn("key", type), modifiers, KeyboardPlatform.MAC))
            assertEquals(modifiers.toMetaState() and KeyEvent.META_FUNCTION_ON.inv(), stroke.requiredMetaState)
            assertFalse(rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState).any {
                it.keyCode == KeyEvent.KEYCODE_FUNCTION || it.metaState and KeyEvent.META_FUNCTION_ON != 0
            })
        }
    }

    /** Keeps Win Fn events unchanged and does not invent mappings for unsupported Mac Fn keys. */
    @Test
    fun preservesWindowsAndUnmappedFnBehavior() {
        val modifiers = ModifierState().apply { tapFn() }
        val key = Key.fn("Backspace", KeyType.BACKSPACE)
        assertEquals(RawKeyStroke(KeyEvent.KEYCODE_DEL, KeyEvent.META_FUNCTION_ON), RawKeyRouter.strokeFor(key, modifiers))
        assertEquals(
            RawKeyStroke(KeyEvent.KEYCODE_C, KeyEvent.META_FUNCTION_ON),
            RawKeyRouter.strokeFor(Key.letter("c"), modifiers, KeyboardPlatform.MAC),
        )
    }

    /** Keeps Caps Lock uppercase for text without changing shortcut or numeric key identities. */
    @Test
    fun capsLockDoesNotTurnCommandCIntoCommandShiftCOrDigitsIntoSymbols() {
        val modifiers = ModifierState().apply { toggleCapsLock() }
        assertEquals(
            RawKeyStroke(KeyEvent.KEYCODE_C, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
            RawKeyRouter.strokeFor(Key.letter("c"), modifiers, KeyboardPlatform.MAC),
        )
        assertEquals(RawKeyStroke(KeyEvent.KEYCODE_3), RawKeyRouter.strokeFor(Key.char("3", "#"), modifiers, KeyboardPlatform.MAC))
        modifiers.tapMeta()
        assertShortcut(Key.letter("c"), modifiers, KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_META_LEFT))
    }

    /** Asserts balanced modifier downs/ups, requested sides, key identity, and fully released state. */
    private fun assertShortcut(key: Key, modifiers: ModifierState, code: Int, modifierCodes: List<Int>) {
        val stroke = requireNotNull(RawKeyRouter.strokeFor(key, modifiers, KeyboardPlatform.MAC))
        assertEquals(code, stroke.keyCode)
        val events = rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
        assertEquals(modifierCodes + listOf(code, code) + modifierCodes.asReversed(), events.map { it.keyCode })
        assertEquals(
            List(modifierCodes.size + 1) { KeyEvent.ACTION_DOWN } + List(modifierCodes.size + 1) { KeyEvent.ACTION_UP },
            events.map { it.action },
        )
        assertEquals(stroke.requiredMetaState, events[modifierCodes.size].metaState)
        assertEquals(0, events.last().metaState)
    }
}
