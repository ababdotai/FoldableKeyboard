package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyRouter
import com.pckeyboard.ime.dispatch.RawKeyStroke
import com.pckeyboard.ime.dispatch.rawKeyEventPlan
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierSide
import com.pckeyboard.ime.model.ModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Verifies Mac shortcuts use UU's replacement without leaking Meta into Android policy. */
class UuShortcutMapperTest {

    /** Covers editing, redo, Spotlight, fullscreen, and paste special with both Command keys. */
    @Test
    fun mapsCommandShortcutMatrixToBalancedRightControlSequences() {
        for (side in ModifierSide.entries) {
            for (letter in "acvxzsf") {
                assertShortcut(Key.letter(letter.toString()), ModifierState().apply { tapMeta(side) },
                    listOf(KeyEvent.KEYCODE_CTRL_RIGHT))
            }
            assertShortcut(Key.letter("z"), ModifierState().apply { tapShift(); tapMeta(side) },
                listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT))
            assertShortcut(Key.fn("space", KeyType.SPACE), ModifierState().apply { tapMeta(side) },
                listOf(KeyEvent.KEYCODE_CTRL_RIGHT))
            assertShortcut(Key.letter("f"), ModifierState().apply { tapCtrl(); tapMeta(side) },
                listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT))
            for (optionSide in ModifierSide.entries) {
                val option = if (optionSide == ModifierSide.LEFT) KeyEvent.KEYCODE_ALT_LEFT else KeyEvent.KEYCODE_ALT_RIGHT
                assertShortcut(Key.letter("v"), ModifierState().apply { tapAlt(optionSide); tapMeta(side) },
                    listOf(KeyEvent.KEYCODE_CTRL_RIGHT, option))
            }
        }
    }

    /** Keeps real Control down after releasing the separate Right Ctrl used for Command. */
    @Test
    fun retainsLeftControlMetaUntilBothControlKeysHaveBeenReleased() {
        val stroke = UuShortcutMapper.map(
            RawKeyStroke(KeyEvent.KEYCODE_F, COMMAND_LEFT or CONTROL_LEFT), KeyboardPlatform.MAC, true,
        )
        val plan = rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
        assertEquals(listOf(CONTROL_LEFT, CONTROL_BOTH, CONTROL_BOTH, CONTROL_BOTH, CONTROL_LEFT, 0),
            plan.map { it.metaState })
        assertEquals(KeyEvent.KEYCODE_CTRL_RIGHT, plan[4].keyCode)
        assertEquals(KeyEvent.ACTION_UP, plan[4].action)
    }

    /** Assigns an unsided genuine Control request to Left before adding compatibility Right. */
    @Test
    fun preservesGenericControlAlongsideCommand() {
        val stroke = UuShortcutMapper.map(
            RawKeyStroke(KeyEvent.KEYCODE_F, COMMAND_LEFT or KeyEvent.META_CTRL_ON), KeyboardPlatform.MAC, true,
        )
        assertEquals(CONTROL_BOTH, stroke.requiredMetaState)
    }

    /** Does not remap Windows shortcuts, disabled compatibility, or non-Command keys. */
    @Test
    fun leavesOtherPlatformsModesAndModifiersUnchanged() {
        val command = RawKeyStroke(KeyEvent.KEYCODE_C, COMMAND_LEFT)
        assertEquals(command, UuShortcutMapper.map(command, KeyboardPlatform.WIN, true))
        assertEquals(command, UuShortcutMapper.map(command, KeyboardPlatform.MAC, false))
        for (meta in listOf(0, CONTROL_LEFT, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON)) {
            val stroke = RawKeyStroke(KeyEvent.KEYCODE_C, meta)
            assertEquals(stroke, UuShortcutMapper.map(stroke, KeyboardPlatform.MAC, true))
        }
    }

    /** Also handles side-only Command metadata without requiring framework normalization. */
    @Test
    fun removesEveryCommandBitBeforeInjection() {
        for (meta in listOf(KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_RIGHT_ON,
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON)) {
            assertEquals(CONTROL_RIGHT, UuShortcutMapper.map(
                RawKeyStroke(KeyEvent.KEYCODE_C, meta), KeyboardPlatform.MAC, true,
            ).requiredMetaState)
        }
    }

    /** Checks action balance, scan codes, shortcut metadata, and final release state. */
    private fun assertShortcut(key: Key, modifiers: ModifierState, codes: List<Int>) {
        val original = requireNotNull(RawKeyRouter.strokeFor(key, modifiers, KeyboardPlatform.MAC))
        val stroke = UuShortcutMapper.map(original, KeyboardPlatform.MAC, true)
        assertEquals(original.keyCode, stroke.keyCode)
        val plan = rawKeyEventPlan(stroke.keyCode, stroke.requiredMetaState)
        assertEquals(codes + listOf(stroke.keyCode, stroke.keyCode) + codes.asReversed(), plan.map { it.keyCode })
        assertEquals(List(codes.size + 1) { KeyEvent.ACTION_DOWN } + List(codes.size + 1) { KeyEvent.ACTION_UP },
            plan.map { it.action })
        assertEquals(stroke.requiredMetaState, plan[codes.size].metaState)
        assertFalse(plan.any { it.keyCode == KeyEvent.KEYCODE_META_LEFT || it.keyCode == KeyEvent.KEYCODE_META_RIGHT })
        assertFalse(plan.any { it.metaState and (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or
            KeyEvent.META_META_RIGHT_ON) != 0 || it.scanCode == 0 })
        assertEquals(0, plan.last().metaState)
    }

    companion object {
        private const val COMMAND_LEFT = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        private const val CONTROL_LEFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        private const val CONTROL_RIGHT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        private const val CONTROL_BOTH = CONTROL_LEFT or KeyEvent.META_CTRL_RIGHT_ON
    }
}
