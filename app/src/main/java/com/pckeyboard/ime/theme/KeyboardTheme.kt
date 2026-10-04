package com.pckeyboard.ime.theme

import android.graphics.Color

/** Selects the visual renderer without changing key behavior or layout. */
enum class KeyStyle { CLASSIC, MAGIC }

/**
 * A keyboard theme. Defines the colors used by the renderer.
 * Custom themes built in the Theme Editor are stored as instances of this
 * class serialised to SharedPreferences.
 */
data class KeyboardTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,

    val backgroundColor: Int,
    val keyBackgroundColor: Int,
    val keyPressedColor: Int,
    val keyTextColor: Int,
    val secondaryTextColor: Int,
    val modifierKeyColor: Int,
    val modifierTextColor: Int,
    val accentColor: Int,
    val accentTextColor: Int,
    val dividerColor: Int,
    val keyCornerRadiusDp: Int = 8,
    val keyElevationDp: Int = 1,
    val keySpacingDp: Int = 3,
    val keyStyle: KeyStyle = KeyStyle.CLASSIC,
) {
    /** Serializes the palette and renderer for custom theme persistence. */
    fun toMap(): Map<String, String> = mapOf(
        "id" to id,
        "name" to name,
        "isDark" to isDark.toString(),
        "backgroundColor" to backgroundColor.toString(),
        "keyBackgroundColor" to keyBackgroundColor.toString(),
        "keyPressedColor" to keyPressedColor.toString(),
        "keyTextColor" to keyTextColor.toString(),
        "secondaryTextColor" to secondaryTextColor.toString(),
        "modifierKeyColor" to modifierKeyColor.toString(),
        "modifierTextColor" to modifierTextColor.toString(),
        "accentColor" to accentColor.toString(),
        "accentTextColor" to accentTextColor.toString(),
        "dividerColor" to dividerColor.toString(),
        "keyCornerRadiusDp" to keyCornerRadiusDp.toString(),
        "keyElevationDp" to keyElevationDp.toString(),
        "keySpacingDp" to keySpacingDp.toString(),
        "keyStyle" to keyStyle.name,
    )

    companion object {
        /** Reads older themes with the classic renderer when no known style is stored. */
        fun fromMap(map: Map<String, String>): KeyboardTheme = KeyboardTheme(
            id = map["id"] ?: "custom",
            name = map["name"] ?: "Custom",
            isDark = map["isDark"]?.toBoolean() ?: false,
            backgroundColor = map["backgroundColor"]?.toInt() ?: Color.WHITE,
            keyBackgroundColor = map["keyBackgroundColor"]?.toInt() ?: Color.LTGRAY,
            keyPressedColor = map["keyPressedColor"]?.toInt() ?: Color.GRAY,
            keyTextColor = map["keyTextColor"]?.toInt() ?: Color.BLACK,
            secondaryTextColor = map["secondaryTextColor"]?.toInt() ?: Color.DKGRAY,
            modifierKeyColor = map["modifierKeyColor"]?.toInt() ?: Color.GRAY,
            modifierTextColor = map["modifierTextColor"]?.toInt() ?: Color.WHITE,
            accentColor = map["accentColor"]?.toInt() ?: Color.BLUE,
            accentTextColor = map["accentTextColor"]?.toInt() ?: Color.WHITE,
            dividerColor = map["dividerColor"]?.toInt() ?: Color.GRAY,
            keyCornerRadiusDp = map["keyCornerRadiusDp"]?.toInt() ?: 8,
            keyElevationDp = map["keyElevationDp"]?.toInt() ?: 1,
            keySpacingDp = map["keySpacingDp"]?.toInt() ?: 3,
            keyStyle = KeyStyle.entries.firstOrNull { it.name == map["keyStyle"] }
                ?: KeyStyle.CLASSIC,
        )
    }
}
