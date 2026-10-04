package com.pckeyboard.ime.model

import android.view.KeyEvent

/** Identifies the physical side used by a duplicated desktop modifier key. */
enum class ModifierSide {
    LEFT,
    RIGHT;

    companion object {
        /** Maps right-sided Android key codes to Right and defaults all legacy inputs to Left. */
        fun fromKeyCode(keyCode: Int): ModifierSide = when (keyCode) {
            KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_RIGHT -> RIGHT
            else -> LEFT
        }
    }
}

/**
 * Tracks current state of modifier keys (Shift, Ctrl, Alt, Meta, Fn).
 *
 * Each modifier has three logical states:
 *  - OFF: not active
 *  - ONCE: active for the next key press, then released
 *  - LOCKED: stays on until tapped again (Caps Lock style)
 * Touch-held modifiers are independent of these sticky states and remain active until released.
 * Caps Lock affects letter casing but never implicitly adds Shift to a shortcut.
 */
class ModifierState {

    enum class State { OFF, ONCE, LOCKED }

    var shift: State = State.OFF
        private set
    var ctrl: State = State.OFF
        private set
    var alt: State = State.OFF
        private set
    var meta: State = State.OFF
        private set
    var fn: State = State.OFF
        private set
    var capsLock: Boolean = false
        private set
    private var stickyAltSide = ModifierSide.LEFT
    private var stickyMetaSide = ModifierSide.LEFT
    private data class HeldModifier(val type: KeyType, val side: ModifierSide)
    private val held = linkedMapOf<HeldModifier, Boolean>()

    val altSide: ModifierSide
        get() = held.keys.lastOrNull { it.type == KeyType.ALT }?.side ?: stickyAltSide
    val metaSide: ModifierSide
        get() = held.keys.lastOrNull { it.type == KeyType.META }?.side ?: stickyMetaSide

    fun tapShift() {
        shift = when (shift) {
            State.OFF -> State.ONCE
            State.ONCE -> State.LOCKED
            State.LOCKED -> State.OFF
        }
    }

    fun tapCtrl() { ctrl = cycle(ctrl) }
    /** Cycles Alt on [side], starting a fresh one-shot state when sides change. */
    fun tapAlt(side: ModifierSide = ModifierSide.LEFT) {
        alt = cycleSided(alt, stickyAltSide, side)
        stickyAltSide = side
    }

    /** Cycles Meta on [side], starting a fresh one-shot state when sides change. */
    fun tapMeta(side: ModifierSide = ModifierSide.LEFT) {
        meta = cycleSided(meta, stickyMetaSide, side)
        stickyMetaSide = side
    }
    fun tapFn() { fn = cycle(fn) }

    fun toggleCapsLock() {
        capsLock = !capsLock
    }

    /** Activates a touch-held modifier immediately, independently of its sticky state. */
    fun pressModifier(key: Key): Boolean {
        val modifier = heldModifier(key) ?: return false
        held.putIfAbsent(modifier, false)
        return true
    }

    /** Releases a hold, cycling sticky state only when the gesture was an unused tap. */
    fun releaseModifier(key: Key, cancelled: Boolean = false): Boolean {
        val modifier = heldModifier(key) ?: return false
        val used = held.remove(modifier) ?: return true
        if (!used && !cancelled) {
            when (key.type) {
                KeyType.SHIFT -> tapShift()
                KeyType.CTRL -> tapCtrl()
                KeyType.ALT -> tapAlt(modifier.side)
                KeyType.META -> tapMeta(modifier.side)
                KeyType.FN -> tapFn()
                else -> Unit
            }
        }
        return true
    }

    /** Marks held modifiers as chord participants so release cannot leave a sticky modifier. */
    fun markHeldModifiersUsed() {
        held.keys.toList().forEach { held[it] = true }
    }

    /** Copies the current chord so releasing a modifier before the action key preserves it. */
    fun snapshot(): ModifierState = ModifierState().also {
        it.shift = shift; it.ctrl = ctrl; it.alt = alt; it.meta = meta; it.fn = fn
        it.capsLock = capsLock
        it.stickyAltSide = stickyAltSide; it.stickyMetaSide = stickyMetaSide
        it.held.putAll(held)
    }

    /** Identifies only real modifier keys, excluding function-row action keys. */
    private fun heldModifier(key: Key): HeldModifier? = when (key.type) {
        KeyType.SHIFT, KeyType.CTRL, KeyType.ALT, KeyType.META ->
            HeldModifier(key.type, ModifierSide.fromKeyCode(key.keyCode))
        KeyType.FN -> if (key.keyCode == KeyEvent.KEYCODE_FUNCTION) {
            HeldModifier(KeyType.FN, ModifierSide.LEFT)
        } else null
        else -> null
    }

    /** Consumes the ONCE modifiers after a character key is pressed. */
    fun consumeAfterChar() {
        markHeldModifiersUsed()
        if (shift == State.ONCE) shift = State.OFF
        if (ctrl == State.ONCE) ctrl = State.OFF
        if (alt == State.ONCE) alt = State.OFF
        if (meta == State.ONCE) meta = State.OFF
        if (fn == State.ONCE) fn = State.OFF
    }

    /** Returns explicit Shift state without treating Caps Lock as a shortcut modifier. */
    fun isShiftModifierActive(): Boolean = shift != State.OFF || isHeld(KeyType.SHIFT)
    fun isShiftActive(): Boolean = isShiftModifierActive() || capsLock
    fun isCtrlActive(): Boolean = ctrl != State.OFF || isHeld(KeyType.CTRL)
    fun isAltActive(): Boolean = alt != State.OFF || isHeld(KeyType.ALT)
    fun isMetaActive(): Boolean = meta != State.OFF || isHeld(KeyType.META)
    fun isFnActive(): Boolean = fn != State.OFF || isHeld(KeyType.FN)

    /** Returns whether any touch currently holds this modifier type. */
    private fun isHeld(type: KeyType): Boolean = held.keys.any { it.type == type }

    /** Returns Alt's state only when [side] owns the active Alt state. */
    fun altState(side: ModifierSide): State =
        if (HeldModifier(KeyType.ALT, side) in held) State.ONCE
        else if (stickyAltSide == side) alt else State.OFF

    /** Returns Meta's state only when [side] owns the active Meta state. */
    fun metaState(side: ModifierSide): State =
        if (HeldModifier(KeyType.META, side) in held) State.ONCE
        else if (stickyMetaSide == side) meta else State.OFF

    /** Returns the Android key code for the active Alt side. */
    fun altKeyCode(): Int = if (altSide == ModifierSide.RIGHT) {
        KeyEvent.KEYCODE_ALT_RIGHT
    } else {
        KeyEvent.KEYCODE_ALT_LEFT
    }

    /** Returns the Android key code for the active Meta side. */
    fun metaKeyCode(): Int = if (metaSide == ModifierSide.RIGHT) {
        KeyEvent.KEYCODE_META_RIGHT
    } else {
        KeyEvent.KEYCODE_META_LEFT
    }

    /** Returns Android KeyEvent meta flags for the active modifiers. */
    fun toMetaState(): Int {
        var meta = 0
        if (isShiftModifierActive()) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (isCtrlActive())  meta = meta or KeyEvent.META_CTRL_ON  or KeyEvent.META_CTRL_LEFT_ON
        if (isAltActive()) {
            meta = meta or KeyEvent.META_ALT_ON or if (altSide == ModifierSide.RIGHT) {
                KeyEvent.META_ALT_RIGHT_ON
            } else {
                KeyEvent.META_ALT_LEFT_ON
            }
        }
        if (isMetaActive()) {
            meta = meta or KeyEvent.META_META_ON or if (metaSide == ModifierSide.RIGHT) {
                KeyEvent.META_META_RIGHT_ON
            } else {
                KeyEvent.META_META_LEFT_ON
            }
        }
        if (isFnActive())    meta = meta or KeyEvent.META_FUNCTION_ON
        return meta
    }

    /** Clears every modifier and restores legacy left-side defaults. */
    fun reset() {
        shift = State.OFF; ctrl = State.OFF; alt = State.OFF
        meta = State.OFF; fn = State.OFF; capsLock = false
        stickyAltSide = ModifierSide.LEFT; stickyMetaSide = ModifierSide.LEFT
        held.clear()
    }

    /** Returns true when any non-shift modifier is held (so taps should send key events). */
    fun shouldSendAsKeyEvent(): Boolean =
        isCtrlActive() || isAltActive() || isMetaActive() || isFnActive()

    private fun cycle(s: State): State = when (s) {
        State.OFF -> State.ONCE
        State.ONCE -> State.LOCKED
        State.LOCKED -> State.OFF
    }

    /** Cycles one side repeatedly but arms a newly selected side once. */
    private fun cycleSided(
        state: State,
        currentSide: ModifierSide,
        requestedSide: ModifierSide,
    ): State = if (state != State.OFF && currentSide != requestedSide) {
        State.ONCE
    } else {
        cycle(state)
    }
}
