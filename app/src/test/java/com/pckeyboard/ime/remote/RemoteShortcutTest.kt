package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.layout.KeyboardPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies explicit desktop shortcuts independently of keyboard modifier state. */
class RemoteShortcutTest {
    /** Keeps Windows commands on Control, including the conventional Control-Y redo. */
    @Test
    fun windowsCommandsUseOnlyControl() {
        val keys = listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_S)
        RemoteShortcut.entries.zip(keys).forEach { (shortcut, code) ->
            val stroke = shortcut.stroke(KeyboardPlatform.WIN)
            assertEquals(code, stroke.keyCode)
            assertEquals(KeyEvent.META_CTRL_ON, stroke.requiredMetaState)
        }
    }

    /** Uses Command and Shift-Command-Z without importing any sticky keyboard modifiers. */
    @Test
    fun macCommandsUseCommandWithShiftOnlyForRedo() {
        RemoteShortcut.entries.forEach { shortcut ->
            val stroke = shortcut.stroke(KeyboardPlatform.MAC)
            val shift = if (shortcut == RemoteShortcut.REDO) KeyEvent.META_SHIFT_ON else 0
            assertEquals(KeyEvent.META_META_ON or shift, stroke.requiredMetaState)
        }
        assertEquals(KeyEvent.KEYCODE_Z, RemoteShortcut.REDO.stroke(KeyboardPlatform.MAC).keyCode)
        assertEquals("⇧⌘Z", RemoteShortcut.REDO.hint(KeyboardPlatform.MAC))
        assertEquals("Ctrl+Y", RemoteShortcut.REDO.hint(KeyboardPlatform.WIN))
    }

    /** Shares the existing opt-in Right-Control mapping instead of bypassing UU compatibility. */
    @Test
    fun macRedoPreservesShiftAcrossCompatibilityMapping() {
        val original = RemoteShortcut.REDO.stroke(KeyboardPlatform.MAC)
        assertEquals(original, UuShortcutMapper.map(original, KeyboardPlatform.MAC, false))
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_SHIFT_ON,
            UuShortcutMapper.map(original, KeyboardPlatform.MAC, true).requiredMetaState)
    }
}
