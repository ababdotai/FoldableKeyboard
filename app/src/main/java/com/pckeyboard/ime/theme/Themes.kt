package com.pckeyboard.ime.theme

import android.graphics.Color

/**
 * Built-in themes shipped with the keyboard.
 * Magic palettes use physical white or black keycaps on a neutral metal chassis.
 */
object Themes {

    val MAGIC = KeyboardTheme(
        id = "light",
        name = "Magic Keyboard",
        isDark = false,
        backgroundColor       = Color.parseColor("#D8DBDE"),
        keyBackgroundColor    = Color.parseColor("#FAFAF9"),
        keyPressedColor       = Color.parseColor("#E3E5E6"),
        keyTextColor          = Color.parseColor("#36383A"),
        secondaryTextColor    = Color.parseColor("#595C60"),
        modifierKeyColor      = Color.parseColor("#FAFAF9"),
        modifierTextColor     = Color.parseColor("#36383A"),
        accentColor           = Color.parseColor("#52575D"),
        accentTextColor       = Color.parseColor("#FFFFFF"),
        dividerColor          = Color.parseColor("#AEB3B8"),
        keyCornerRadiusDp = 4,
        keyElevationDp = 1,
        keySpacingDp = 6,
        keyStyle = KeyStyle.MAGIC,
    )

    /** Retains the existing preference ID and source references for the light theme. */
    val LIGHT = MAGIC

    val DARK = KeyboardTheme(
        id = "dark",
        name = "Magic Keyboard (Black)",
        isDark = true,
        backgroundColor       = Color.parseColor("#56585A"),
        keyBackgroundColor    = Color.parseColor("#202123"),
        keyPressedColor       = Color.parseColor("#383A3C"),
        keyTextColor          = Color.parseColor("#F4F4F2"),
        secondaryTextColor    = Color.parseColor("#CACCCD"),
        modifierKeyColor      = Color.parseColor("#202123"),
        modifierTextColor     = Color.parseColor("#F4F4F2"),
        accentColor           = Color.parseColor("#D9DBDD"),
        accentTextColor       = Color.parseColor("#202123"),
        dividerColor          = Color.parseColor("#131415"),
        keyCornerRadiusDp = 4,
        keyElevationDp = 1,
        keySpacingDp = 6,
        keyStyle = KeyStyle.MAGIC,
    )

    val BLACK = KeyboardTheme(
        id = "black",
        name = "Black (AMOLED)",
        isDark = true,
        backgroundColor       = Color.parseColor("#000000"),
        keyBackgroundColor    = Color.parseColor("#121212"),
        keyPressedColor       = Color.parseColor("#2A2A2A"),
        keyTextColor          = Color.parseColor("#FFFFFF"),
        secondaryTextColor    = Color.parseColor("#9AA0A6"),
        modifierKeyColor      = Color.parseColor("#0A0A0A"),
        modifierTextColor     = Color.parseColor("#E8EAED"),
        accentColor           = Color.parseColor("#BB86FC"),
        accentTextColor       = Color.parseColor("#000000"),
        dividerColor          = Color.parseColor("#1F1F1F"),
        keyCornerRadiusDp = 10,
        keyElevationDp = 0,
        keySpacingDp = 3
    )

    val builtIn: List<KeyboardTheme> = listOf(MAGIC, DARK, BLACK)
}
