package com.pckeyboard.ime.dispatch

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

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
            event(downTime, spec.action, spec.keyCode, spec.metaState)
        }
        return dispatchUntilRejected(events, inputConnection::sendKeyEvent)
    }

    /** Builds one keyboard event with virtual-keyboard identity and soft-keyboard provenance. */
    private fun event(
        downTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
    ): KeyEvent = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        metaState,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        InputDevice.SOURCE_KEYBOARD,
    )
}

/** Pure description of one event in a raw key sequence. */
internal data class RawKeyEventSpec(
    val action: Int,
    val keyCode: Int,
    val metaState: Int,
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
    return buildList {
        var progressiveMeta = 0
        modifierCodes.forEach { modifierCode ->
            progressiveMeta = progressiveMeta or modifierMetaBits(modifierCode, metaState)
            add(RawKeyEventSpec(KeyEvent.ACTION_DOWN, modifierCode, progressiveMeta))
        }
        add(RawKeyEventSpec(KeyEvent.ACTION_DOWN, keyCode, progressiveMeta))
        add(RawKeyEventSpec(KeyEvent.ACTION_UP, keyCode, progressiveMeta))
        modifierCodes.asReversed().forEach { modifierCode ->
            progressiveMeta = progressiveMeta and modifierMetaMask(modifierCode).inv()
            add(RawKeyEventSpec(KeyEvent.ACTION_UP, modifierCode, progressiveMeta))
        }
    }
}

/** Extracts the requested meta bits represented by one physical modifier key. */
private fun modifierMetaBits(keyCode: Int, metaState: Int): Int =
    metaState and modifierMetaMask(keyCode)

/** Returns all generic and sided meta bits represented by one modifier key. */
private fun modifierMetaMask(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_SHIFT_LEFT ->
        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
    KeyEvent.KEYCODE_CTRL_LEFT ->
        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON
    KeyEvent.KEYCODE_ALT_LEFT ->
        KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON
    KeyEvent.KEYCODE_META_LEFT ->
        KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON
    else -> 0
}

/** Lists active physical modifier keys in deterministic press order. */
internal fun activeModifierKeyCodes(metaState: Int): List<Int> = buildList {
    if (metaState and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
    if (metaState and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
    if (metaState and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
    if (metaState and KeyEvent.META_META_ON != 0) add(KeyEvent.KEYCODE_META_LEFT)
}
