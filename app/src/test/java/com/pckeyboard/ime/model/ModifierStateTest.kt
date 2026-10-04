package com.pckeyboard.ime.model

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies modifier state conversion into Android key-event flags. */
class ModifierStateTest {

    /** Applies held Command immediately and keeps it active across several consumed keys. */
    @Test
    fun heldCommandSurvivesConsumptionAndDoesNotBecomeSticky() {
        val modifiers = ModifierState()
        val command = Key.fn("⌘", KeyType.META, KeyEvent.KEYCODE_META_LEFT)

        assertTrue(modifiers.pressModifier(command))
        assertTrue(modifiers.isMetaActive())
        repeat(3) {
            modifiers.consumeAfterChar()
            assertTrue(modifiers.isMetaActive())
        }
        modifiers.releaseModifier(command)

        assertFalse(modifiers.isMetaActive())
        assertEquals(ModifierState.State.OFF, modifiers.meta)
    }

    /** Preserves tap-to-arm and tap-again-to-lock independently from touch-held state. */
    @Test
    fun unusedModifierTapsCycleStickyState() {
        val modifiers = ModifierState()
        val command = Key.fn("⌘", KeyType.META, KeyEvent.KEYCODE_META_RIGHT)

        modifiers.pressModifier(command)
        modifiers.releaseModifier(command)
        assertEquals(ModifierState.State.ONCE, modifiers.meta)
        modifiers.pressModifier(command)
        modifiers.releaseModifier(command)
        assertEquals(ModifierState.State.LOCKED, modifiers.meta)
        modifiers.consumeAfterChar()
        assertTrue(modifiers.isMetaActive())
        modifiers.pressModifier(command)
        modifiers.releaseModifier(command)
        assertFalse(modifiers.isMetaActive())
    }

    /** Retains each side and all chord modifiers when fingers release in a different order. */
    @Test
    fun snapshotRetainsRightOptionCommandAndExplicitShift() {
        val modifiers = ModifierState()
        val command = Key.fn("⌘", KeyType.META, KeyEvent.KEYCODE_META_RIGHT)
        val option = Key.fn("⌥", KeyType.ALT, KeyEvent.KEYCODE_ALT_RIGHT)
        val shift = Key.fn("⇧", KeyType.SHIFT)
        listOf(command, option, shift).forEach { modifiers.pressModifier(it) }
        modifiers.markHeldModifiersUsed()
        val chord = modifiers.snapshot()
        listOf(command, option, shift).forEach { modifiers.releaseModifier(it) }

        assertEquals(0, modifiers.toMetaState())
        assertEquals(
            KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON or
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            chord.toMetaState(),
        )
    }

    /** Does not leave one-shot state behind when a gesture or keyboard session is cancelled. */
    @Test
    fun cancellationAndResetClearHeldState() {
        val modifiers = ModifierState()
        val command = Key.fn("⌘", KeyType.META, KeyEvent.KEYCODE_META_LEFT)
        modifiers.pressModifier(command)
        modifiers.releaseModifier(command, cancelled = true)
        assertEquals(0, modifiers.toMetaState())
        modifiers.pressModifier(command)
        modifiers.tapAlt()
        modifiers.reset()
        modifiers.releaseModifier(command)
        assertEquals(0, modifiers.toMetaState())
    }

    /** Keeps Caps Lock uppercase state separate from shortcut Shift flags. */
    @Test
    fun capsLockDoesNotTurnCommandCIntoCommandShiftC() {
        val modifiers = ModifierState()
        modifiers.toggleCapsLock()
        modifiers.tapMeta()

        assertTrue(modifiers.isShiftActive())
        assertFalse(modifiers.isShiftModifierActive())
        assertEquals(ModifierState.State.OFF, modifiers.shift)
        assertEquals(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON, modifiers.toMetaState())
        modifiers.tapShift()
        assertTrue(modifiers.toMetaState() and KeyEvent.META_SHIFT_ON != 0)
    }

    /** Does not mistake the function row for a touch-held Fn modifier. */
    @Test
    fun functionRowKeyIsNotAHeldModifier() {
        assertFalse(ModifierState().pressModifier(Key.fn("F1", KeyType.FN, KeyEvent.KEYCODE_F1)))
    }

    /** Verifies the Mac fn toggle contributes Android's function meta flag. */
    @Test
    fun functionModifierAddsFunctionMetaState() {
        val modifiers = ModifierState()

        modifiers.tapFn()

        assertEquals(KeyEvent.META_FUNCTION_ON, modifiers.toMetaState())
    }

    /** Verifies right Alt and Meta preserve their side in state and emitted meta flags. */
    @Test
    fun rightAltAndMetaUseRightSidedFlags() {
        val modifiers = ModifierState()

        modifiers.tapAlt(ModifierSide.RIGHT)
        modifiers.tapMeta(ModifierSide.RIGHT)

        assertEquals(ModifierState.State.OFF, modifiers.altState(ModifierSide.LEFT))
        assertEquals(ModifierState.State.ONCE, modifiers.altState(ModifierSide.RIGHT))
        assertEquals(ModifierState.State.OFF, modifiers.metaState(ModifierSide.LEFT))
        assertEquals(ModifierState.State.ONCE, modifiers.metaState(ModifierSide.RIGHT))
        assertEquals(KeyEvent.KEYCODE_ALT_RIGHT, modifiers.altKeyCode())
        assertEquals(KeyEvent.KEYCODE_META_RIGHT, modifiers.metaKeyCode())
        assertEquals(
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON or
                KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON,
            modifiers.toMetaState(),
        )
    }

    /** Verifies legacy taps remain left-sided and switching sides arms only the new side. */
    @Test
    fun legacyTapDefaultsLeftAndSideSwitchMovesHighlight() {
        val modifiers = ModifierState()

        modifiers.tapAlt()
        assertEquals(ModifierState.State.ONCE, modifiers.altState(ModifierSide.LEFT))
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, modifiers.altKeyCode())

        modifiers.tapAlt(ModifierSide.RIGHT)
        assertEquals(ModifierState.State.OFF, modifiers.altState(ModifierSide.LEFT))
        assertEquals(ModifierState.State.ONCE, modifiers.altState(ModifierSide.RIGHT))
    }
}
