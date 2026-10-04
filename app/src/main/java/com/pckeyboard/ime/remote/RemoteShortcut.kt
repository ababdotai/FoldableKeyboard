package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.R
import com.pckeyboard.ime.dispatch.RawKeyStroke
import com.pckeyboard.ime.layout.KeyboardPlatform

/** Explicit remote commands that never inspect the Android clipboard or sticky modifiers. */
internal enum class RemoteShortcut(val labelRes: Int, private val keyCode: Int, private val letter: String) {
    SELECT_ALL(R.string.uu_shortcut_select_all, KeyEvent.KEYCODE_A, "A"),
    COPY(R.string.uu_shortcut_copy, KeyEvent.KEYCODE_C, "C"),
    PASTE(R.string.uu_shortcut_paste, KeyEvent.KEYCODE_V, "V"),
    UNDO(R.string.uu_shortcut_undo, KeyEvent.KEYCODE_Z, "Z"),
    REDO(R.string.uu_shortcut_redo, KeyEvent.KEYCODE_Y, "Y"),
    FIND(R.string.uu_shortcut_find, KeyEvent.KEYCODE_F, "F"),
    SAVE(R.string.uu_shortcut_save, KeyEvent.KEYCODE_S, "S");

    /** Returns exactly one logical shortcut using the selected desktop convention. */
    fun stroke(platform: KeyboardPlatform): RawKeyStroke {
        val mac = platform == KeyboardPlatform.MAC
        val modifier = if (mac) KeyEvent.META_META_ON else KeyEvent.META_CTRL_ON
        return if (mac && this == REDO) {
            RawKeyStroke(KeyEvent.KEYCODE_Z, modifier or KeyEvent.META_SHIFT_ON)
        } else RawKeyStroke(keyCode, modifier)
    }

    /** Labels the logical desktop shortcut, before UU's optional Command compatibility mapping. */
    fun hint(platform: KeyboardPlatform): String = when {
        platform != KeyboardPlatform.MAC -> "Ctrl+$letter"
        this == REDO -> "⇧⌘Z"
        else -> "⌘$letter"
    }
}
