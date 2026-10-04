package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyStroke
import com.pckeyboard.ime.layout.KeyboardPlatform

/** Avoids Android Meta shortcuts using UU's opt-in Right Ctrl to Command replacement. */
internal object UuShortcutMapper {
    private const val COMMAND_MASK = KeyEvent.META_META_ON or
        KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON
    private const val CONTROL_SIDES = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON

    /** Maps Command only for the Mac overlay, retaining real Control as a separate left key. */
    fun map(stroke: RawKeyStroke, platform: KeyboardPlatform, enabled: Boolean): RawKeyStroke {
        val originalMeta = stroke.requiredMetaState
        if (!enabled || platform != KeyboardPlatform.MAC || originalMeta and COMMAND_MASK == 0) {
            return stroke
        }
        var meta = originalMeta and COMMAND_MASK.inv()
        if (meta and KeyEvent.META_CTRL_ON != 0 && meta and CONTROL_SIDES == 0) {
            meta = meta or KeyEvent.META_CTRL_LEFT_ON
        }
        return stroke.copy(requiredMetaState = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON)
    }
}
