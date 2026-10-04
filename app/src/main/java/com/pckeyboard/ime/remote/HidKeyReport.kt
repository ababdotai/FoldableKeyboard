package com.pckeyboard.ime.remote

import android.view.KeyEvent
import com.pckeyboard.ime.dispatch.RawKeyEventSpec

/** Encodes keyboard-page usages independently of Android/Linux scan codes. */
internal fun hidUsage(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 4 + keyCode - KeyEvent.KEYCODE_A
    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 30 + keyCode - KeyEvent.KEYCODE_1
    KeyEvent.KEYCODE_0 -> 39
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 58 + keyCode - KeyEvent.KEYCODE_F1
    KeyEvent.KEYCODE_ENTER -> 40
    KeyEvent.KEYCODE_ESCAPE -> 41
    KeyEvent.KEYCODE_DEL -> 42
    KeyEvent.KEYCODE_TAB -> 43
    KeyEvent.KEYCODE_SPACE -> 44
    KeyEvent.KEYCODE_MINUS -> 45
    KeyEvent.KEYCODE_EQUALS -> 46
    KeyEvent.KEYCODE_LEFT_BRACKET -> 47
    KeyEvent.KEYCODE_RIGHT_BRACKET -> 48
    KeyEvent.KEYCODE_BACKSLASH -> 49
    KeyEvent.KEYCODE_SEMICOLON -> 51
    KeyEvent.KEYCODE_APOSTROPHE -> 52
    KeyEvent.KEYCODE_GRAVE -> 53
    KeyEvent.KEYCODE_COMMA -> 54
    KeyEvent.KEYCODE_PERIOD -> 55
    KeyEvent.KEYCODE_SLASH -> 56
    KeyEvent.KEYCODE_CAPS_LOCK -> 57
    KeyEvent.KEYCODE_SYSRQ -> 70
    KeyEvent.KEYCODE_SCROLL_LOCK -> 71
    KeyEvent.KEYCODE_BREAK -> 72
    KeyEvent.KEYCODE_INSERT -> 73
    KeyEvent.KEYCODE_MOVE_HOME -> 74
    KeyEvent.KEYCODE_PAGE_UP -> 75
    KeyEvent.KEYCODE_FORWARD_DEL -> 76
    KeyEvent.KEYCODE_MOVE_END -> 77
    KeyEvent.KEYCODE_PAGE_DOWN -> 78
    KeyEvent.KEYCODE_DPAD_RIGHT -> 79
    KeyEvent.KEYCODE_DPAD_LEFT -> 80
    KeyEvent.KEYCODE_DPAD_DOWN -> 81
    KeyEvent.KEYCODE_DPAD_UP -> 82
    KeyEvent.KEYCODE_CTRL_LEFT -> 224
    KeyEvent.KEYCODE_SHIFT_LEFT -> 225
    KeyEvent.KEYCODE_ALT_LEFT -> 226
    KeyEvent.KEYCODE_META_LEFT -> 227
    KeyEvent.KEYCODE_CTRL_RIGHT -> 228
    KeyEvent.KEYCODE_SHIFT_RIGHT -> 229
    KeyEvent.KEYCODE_ALT_RIGHT -> 230
    KeyEvent.KEYCODE_META_RIGHT -> 231
    else -> null
}

/** Emits one report per planned event and an unconditional release, never replaying input. */
internal fun sendHidReports(
    plan: List<RawKeyEventSpec>,
    canSend: () -> Boolean,
    write: (List<Int>) -> Unit,
): Int {
    if (plan.any { hidUsage(it.keyCode) == null || it.action !in 0..1 }) {
        return SystemInputStatus.INVALID_KEY
    }
    val pressed = linkedSetOf<Int>()
    var submitted = 0
    try {
        for (event in plan) {
            if (event.action == KeyEvent.ACTION_DOWN && !canSend()) {
                return if (submitted == 0) SystemInputStatus.FOCUS_UNSAFE else submitted
            }
            val usage = requireNotNull(hidUsage(event.keyCode))
            if (event.action == KeyEvent.ACTION_DOWN) pressed.add(usage) else pressed.remove(usage)
            val modifiers = pressed.filter { it >= 224 }.fold(0) { mask, code ->
                mask or (1 shl (code - 224))
            }
            val keys = pressed.filter { it < 224 }
            require(keys.size <= 6) { "HID keyboard rollover exceeded" }
            write(listOf(modifiers, 0) + keys + List(6 - keys.size) { 0 })
            submitted++
        }
        return submitted
    } finally {
        write(List(8) { 0 })
    }
}
