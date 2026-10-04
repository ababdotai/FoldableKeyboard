package com.pckeyboard.ime.dispatch

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/** Flags that keep touch focus without marking remote raw events as soft-keyboard text input. */
internal const val RAW_REMOTE_EVENT_FLAGS: Int = KeyEvent.FLAG_KEEP_TOUCH_MODE

/** Sends well-formed physical-style key sequences through an input connection. */
class RawKeyDispatcher {

    /**
     * Sends active modifier downs, the requested key pair, and modifier ups.
     *
     * Dispatch stops at the first rejected event or [RuntimeException]. No event is replayed on a
     * second channel, preventing duplicate characters after a partial sequence was accepted.
     */
    fun send(
        inputConnection: InputConnection,
        keyCode: Int,
        metaState: Int = 0,
    ): RawDispatchResult {
        val normalizedMeta = KeyEvent.normalizeMetaState(metaState)
        val downTime = SystemClock.uptimeMillis()
        val events = rawKeyEventPlan(keyCode, normalizedMeta).map { spec ->
            event(downTime, spec)
        }
        return dispatchUntilRejected(events, inputConnection::sendKeyEvent)
    }

    /** Builds one keyboard event with virtual-keyboard identity and hardware-compatible flags. */
    private fun event(
        downTime: Long,
        spec: RawKeyEventSpec,
    ): KeyEvent = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        spec.action,
        spec.keyCode,
        0,
        spec.metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        spec.scanCode,
        RAW_REMOTE_EVENT_FLAGS,
        InputDevice.SOURCE_KEYBOARD,
    )
}

/** Pure description of one event in a raw key sequence. */
internal data class RawKeyEventSpec(
    val action: Int,
    val keyCode: Int,
    val metaState: Int,
    val scanCode: Int = RawScanCodeMapper.forKeyCode(keyCode),
)

/** Number of events accepted by the sole dispatch channel before it stopped. */
data class RawDispatchResult(
    val acceptedCount: Int,
    val total: Int,
) {
    /** Returns true only when every event in the raw sequence was accepted. */
    val fullyAccepted: Boolean
        get() = acceptedCount == total
}

/**
 * Sends [events] in order and stops permanently at the first rejection or runtime failure.
 *
 * This pure helper keeps acceptance accounting host-JVM testable without constructing KeyEvents.
 */
internal fun <T> dispatchUntilRejected(
    events: List<T>,
    send: (T) -> Boolean,
): RawDispatchResult {
    var acceptedCount = 0
    for (event in events) {
        val accepted = try {
            send(event)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) break
        acceptedCount++
    }
    return RawDispatchResult(acceptedCount, events.size)
}

/**
 * Builds the deterministic modifier-down, key-pair, modifier-up sequence for one raw key.
 *
 * The result is Android-runtime independent apart from integer constants, so host-JVM tests can
 * verify shortcut ordering without constructing framework [KeyEvent] instances.
 */
internal fun rawKeyEventPlan(keyCode: Int, metaState: Int): List<RawKeyEventSpec> {
    val modifierCodes = activeModifierKeyCodes(metaState)
    val progressiveStates = modifierCodes.runningFold(0) { state, modifierCode ->
        state or modifierMetaBits(modifierCode, metaState)
    }
    return buildList {
        modifierCodes.forEachIndexed { index, modifierCode ->
            add(RawKeyEventSpec(KeyEvent.ACTION_DOWN, modifierCode, progressiveStates[index + 1]))
        }
        add(RawKeyEventSpec(KeyEvent.ACTION_DOWN, keyCode, progressiveStates.last()))
        add(RawKeyEventSpec(KeyEvent.ACTION_UP, keyCode, progressiveStates.last()))
        modifierCodes.indices.reversed().forEach { index ->
            // Reuse the earlier state so releasing one side preserves a held opposite side.
            add(RawKeyEventSpec(KeyEvent.ACTION_UP, modifierCodes[index], progressiveStates[index]))
        }
    }
}

/** Returns this key's generic modifier flag and only its requested side flag. */
private fun modifierMetaBits(keyCode: Int, metaState: Int): Int {
    val (generic, sided) = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON to KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON to KeyEvent.META_SHIFT_RIGHT_ON
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON to KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON to KeyEvent.META_CTRL_RIGHT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON to KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON to KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON to KeyEvent.META_META_LEFT_ON
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON to KeyEvent.META_META_RIGHT_ON
        KeyEvent.KEYCODE_FUNCTION -> KeyEvent.META_FUNCTION_ON to 0
        else -> 0 to 0
    }
    return generic or (metaState and sided)
}

/** Lists active physical modifier keys in deterministic press order. */
internal fun activeModifierKeyCodes(metaState: Int): List<Int> = buildList {
    addModifierSides(metaState, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON,
        KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT)
    addModifierSides(metaState, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON,
        KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT)
    addModifierSides(metaState, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON,
        KeyEvent.META_ALT_RIGHT_ON, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)
    addModifierSides(metaState, KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON,
        KeyEvent.META_META_RIGHT_ON, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT)
    if (metaState and KeyEvent.META_FUNCTION_ON != 0) add(KeyEvent.KEYCODE_FUNCTION)
}

/** Preserves both requested sides, using Left only when a modifier has no explicit side. */
private fun MutableList<Int>.addModifierSides(
    metaState: Int,
    generic: Int,
    left: Int,
    right: Int,
    leftCode: Int,
    rightCode: Int,
) {
    if (metaState and (generic or left or right) == 0) return
    if (metaState and left != 0 || metaState and right == 0) add(leftCode)
    if (metaState and right != 0) add(rightCode)
}
