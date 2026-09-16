package com.pckeyboard.ime.dispatch

import android.view.KeyEvent

/** A physical Android key code plus meta flags required to produce its character. */
data class RawKeyStroke(
    val keyCode: Int,
    val requiredMetaState: Int = 0,
)

/** Maps portable US-keyboard characters to hardware-like Android key strokes. */
object RawKeyMapper {

    private const val SHIFT_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    private val punctuation = mapOf(
        '`' to RawKeyStroke(KeyEvent.KEYCODE_GRAVE),
        '~' to RawKeyStroke(KeyEvent.KEYCODE_GRAVE, SHIFT_META),
        '-' to RawKeyStroke(KeyEvent.KEYCODE_MINUS),
        '_' to RawKeyStroke(KeyEvent.KEYCODE_MINUS, SHIFT_META),
        '=' to RawKeyStroke(KeyEvent.KEYCODE_EQUALS),
        '+' to RawKeyStroke(KeyEvent.KEYCODE_EQUALS, SHIFT_META),
        '[' to RawKeyStroke(KeyEvent.KEYCODE_LEFT_BRACKET),
        '{' to RawKeyStroke(KeyEvent.KEYCODE_LEFT_BRACKET, SHIFT_META),
        ']' to RawKeyStroke(KeyEvent.KEYCODE_RIGHT_BRACKET),
        '}' to RawKeyStroke(KeyEvent.KEYCODE_RIGHT_BRACKET, SHIFT_META),
        '\\' to RawKeyStroke(KeyEvent.KEYCODE_BACKSLASH),
        '|' to RawKeyStroke(KeyEvent.KEYCODE_BACKSLASH, SHIFT_META),
        ';' to RawKeyStroke(KeyEvent.KEYCODE_SEMICOLON),
        ':' to RawKeyStroke(KeyEvent.KEYCODE_SEMICOLON, SHIFT_META),
        '\'' to RawKeyStroke(KeyEvent.KEYCODE_APOSTROPHE),
        '"' to RawKeyStroke(KeyEvent.KEYCODE_APOSTROPHE, SHIFT_META),
        ',' to RawKeyStroke(KeyEvent.KEYCODE_COMMA),
        '<' to RawKeyStroke(KeyEvent.KEYCODE_COMMA, SHIFT_META),
        '.' to RawKeyStroke(KeyEvent.KEYCODE_PERIOD),
        '>' to RawKeyStroke(KeyEvent.KEYCODE_PERIOD, SHIFT_META),
        '/' to RawKeyStroke(KeyEvent.KEYCODE_SLASH),
        '?' to RawKeyStroke(KeyEvent.KEYCODE_SLASH, SHIFT_META),
        '!' to RawKeyStroke(KeyEvent.KEYCODE_1, SHIFT_META),
        '@' to RawKeyStroke(KeyEvent.KEYCODE_2, SHIFT_META),
        '#' to RawKeyStroke(KeyEvent.KEYCODE_3, SHIFT_META),
        '$' to RawKeyStroke(KeyEvent.KEYCODE_4, SHIFT_META),
        '%' to RawKeyStroke(KeyEvent.KEYCODE_5, SHIFT_META),
        '^' to RawKeyStroke(KeyEvent.KEYCODE_6, SHIFT_META),
        '&' to RawKeyStroke(KeyEvent.KEYCODE_7, SHIFT_META),
        '*' to RawKeyStroke(KeyEvent.KEYCODE_8, SHIFT_META),
        '(' to RawKeyStroke(KeyEvent.KEYCODE_9, SHIFT_META),
        ')' to RawKeyStroke(KeyEvent.KEYCODE_0, SHIFT_META),
    )

    /** Returns the physical key stroke for [character], or null when it is not raw-mappable. */
    fun forCharacter(character: Char): RawKeyStroke? = when (character) {
        in 'a'..'z' -> RawKeyStroke(KeyEvent.KEYCODE_A + (character - 'a'))
        in 'A'..'Z' -> RawKeyStroke(
            KeyEvent.KEYCODE_A + (character - 'A'),
            SHIFT_META,
        )
        in '0'..'9' -> RawKeyStroke(KeyEvent.KEYCODE_0 + (character - '0'))
        ' ' -> RawKeyStroke(KeyEvent.KEYCODE_SPACE)
        '\n' -> RawKeyStroke(KeyEvent.KEYCODE_ENTER)
        '\t' -> RawKeyStroke(KeyEvent.KEYCODE_TAB)
        else -> punctuation[character]
    }
}
