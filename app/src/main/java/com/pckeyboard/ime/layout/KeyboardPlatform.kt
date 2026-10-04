package com.pckeyboard.ime.layout

/** Selects the desktop convention used for modifier and navigation keys. */
enum class KeyboardPlatform(val preferenceValue: String) {
    WIN("win"),
    MAC("mac");

    companion object {
        /** Restores a persisted platform value while preserving Win as the default. */
        fun fromPreference(value: String?): KeyboardPlatform =
            entries.firstOrNull { it.preferenceValue == value } ?: WIN
    }
}
