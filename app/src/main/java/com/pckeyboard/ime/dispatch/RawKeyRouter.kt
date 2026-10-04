package com.pckeyboard.ime.dispatch

import android.view.KeyEvent
import com.pckeyboard.ime.layout.KeyboardPlatform
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierState

/** Resolves visible keys into raw strokes without text or composing operations. */
internal object RawKeyRouter {

    /**
     * Resolves one remote key, retaining shortcut modifiers and shifted character positions.
     *
     * Local keyboard actions and unsupported characters return null. Alt labels are deliberately
     * excluded because the remote host, rather than the local IME, owns shortcut interpretation.
     */
    fun strokeFor(
        key: Key,
        modifiers: ModifierState,
        platform: KeyboardPlatform = KeyboardPlatform.WIN,
    ): RawKeyStroke? {
        val stroke = when (key.type) {
            KeyType.LETTER, KeyType.CHAR -> {
                val shifted = modifiers.isShiftModifierActive() ||
                    (key.type == KeyType.LETTER && modifiers.capsLock &&
                        !modifiers.shouldSendAsKeyEvent())
                val label = if (shifted) key.shiftLabel ?: key.label else key.label
                val character = label.singleOrNull() ?: return null
                RawKeyMapper.forCharacter(character) ?: return null
            }
            KeyType.SPACE -> RawKeyStroke(KeyEvent.KEYCODE_SPACE)
            KeyType.BACKSPACE -> RawKeyStroke(KeyEvent.KEYCODE_DEL)
            KeyType.ENTER -> RawKeyStroke(KeyEvent.KEYCODE_ENTER)
            KeyType.DELETE -> RawKeyStroke(KeyEvent.KEYCODE_FORWARD_DEL)
            KeyType.TAB -> RawKeyStroke(KeyEvent.KEYCODE_TAB)
            KeyType.ESC -> RawKeyStroke(KeyEvent.KEYCODE_ESCAPE)
            KeyType.ARROW_LEFT -> RawKeyStroke(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyType.ARROW_RIGHT -> RawKeyStroke(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyType.ARROW_UP -> RawKeyStroke(KeyEvent.KEYCODE_DPAD_UP)
            KeyType.ARROW_DOWN -> RawKeyStroke(KeyEvent.KEYCODE_DPAD_DOWN)
            KeyType.HOME -> RawKeyStroke(KeyEvent.KEYCODE_MOVE_HOME)
            KeyType.END -> RawKeyStroke(KeyEvent.KEYCODE_MOVE_END)
            KeyType.PAGE_UP -> RawKeyStroke(KeyEvent.KEYCODE_PAGE_UP)
            KeyType.PAGE_DOWN -> RawKeyStroke(KeyEvent.KEYCODE_PAGE_DOWN)
            KeyType.INSERT -> RawKeyStroke(KeyEvent.KEYCODE_INSERT)
            KeyType.FN -> if (key.keyCode != 0) RawKeyStroke(key.keyCode) else return null
            else -> return null
        }
        val metaState = stroke.requiredMetaState or modifiers.toMetaState()
        if (platform == KeyboardPlatform.MAC && modifiers.isFnActive()) {
            val translatedCode = when (key.type) {
                KeyType.BACKSPACE -> KeyEvent.KEYCODE_FORWARD_DEL
                KeyType.ARROW_LEFT -> KeyEvent.KEYCODE_MOVE_HOME
                KeyType.ARROW_RIGHT -> KeyEvent.KEYCODE_MOVE_END
                KeyType.ARROW_UP -> KeyEvent.KEYCODE_PAGE_UP
                KeyType.ARROW_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
                else -> null
            }
            if (translatedCode != null) {
                return RawKeyStroke(translatedCode, metaState and KeyEvent.META_FUNCTION_ON.inv())
            }
        }
        return stroke.copy(requiredMetaState = metaState)
    }
}
