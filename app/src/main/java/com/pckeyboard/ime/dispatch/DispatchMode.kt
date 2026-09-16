package com.pckeyboard.ime.dispatch

/** Selects whether keys use Android editor APIs or hardware-like key events. */
enum class DispatchMode(val preferenceValue: String) {
    NORMAL("normal"),
    RAW_REMOTE("raw_remote");

    companion object {
        /** Parses a persisted value, preserving normal Android behavior for unknown values. */
        fun fromPreference(value: String?): DispatchMode =
            entries.firstOrNull { it.preferenceValue == value } ?: NORMAL
    }
}
