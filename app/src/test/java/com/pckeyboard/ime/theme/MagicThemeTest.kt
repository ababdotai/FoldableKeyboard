package com.pckeyboard.ime.theme

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Protects persisted themes and the intentionally neutral physical-keyboard palette. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MagicThemeTest {
    /** Retains the old light selection while replacing its visual renderer. */
    @Test
    fun lightThemeKeepsPersistedIdentity() {
        assertSame(Themes.MAGIC, Themes.LIGHT)
        assertEquals("light", Themes.MAGIC.id)
        assertEquals("Magic Keyboard", Themes.MAGIC.name)
        assertEquals(KeyStyle.MAGIC, Themes.MAGIC.keyStyle)
        assertEquals(listOf("light", "dark", "black"), Themes.builtIn.map { it.id })
    }

    /** Round-trips renderer selection along with all theme colors. */
    @Test
    fun rendererSurvivesThemeSerialization() {
        assertEquals(Themes.MAGIC, KeyboardTheme.fromMap(Themes.MAGIC.toMap()))
        assertEquals(Themes.DARK, KeyboardTheme.fromMap(Themes.DARK.toMap()))
    }

    /** Does not opt old or unknown custom themes into different rendering rules. */
    @Test
    fun unknownAndMissingStylesUseClassicRenderer() {
        assertEquals(KeyStyle.CLASSIC,
            KeyboardTheme.fromMap(Themes.MAGIC.toMap() - "keyStyle").keyStyle)
        assertEquals(KeyStyle.CLASSIC,
            KeyboardTheme.fromMap(Themes.MAGIC.toMap() + ("keyStyle" to "FUTURE")).keyStyle)
        assertEquals(KeyStyle.CLASSIC, Themes.BLACK.keyStyle)
    }

    /** Keeps modifiers white instead of the previous contrasting blue action keys. */
    @Test
    fun physicalPaletteUsesMatchingKeycapsAndSmallCorners() {
        assertFalse(Themes.MAGIC.isDark)
        assertEquals(Themes.MAGIC.keyBackgroundColor, Themes.MAGIC.modifierKeyColor)
        assertEquals(Themes.MAGIC.keyTextColor, Themes.MAGIC.modifierTextColor)
        assertEquals(4, Themes.MAGIC.keyCornerRadiusDp)
        assertEquals(6, Themes.MAGIC.keySpacingDp)
    }

    /** Upgrades the saved dark theme without changing the separate AMOLED option. */
    @Test
    fun darkThemeKeepsIdentityAndUsesMatchingBlackMagicKeycaps() {
        val theme = Themes.DARK
        assertEquals("dark", theme.id)
        assertEquals("Magic Keyboard (Black)", theme.name)
        assertTrue(theme.isDark)
        assertEquals(KeyStyle.MAGIC, theme.keyStyle)
        assertEquals(theme.keyBackgroundColor, theme.modifierKeyColor)
        assertEquals(theme.keyTextColor, theme.modifierTextColor)
        assertTrue(ColorUtils.calculateLuminance(theme.keyBackgroundColor) < 0.03)
        assertTrue(ColorUtils.calculateLuminance(theme.backgroundColor) >
            ColorUtils.calculateLuminance(theme.keyBackgroundColor))
        assertEquals(4, theme.keyCornerRadiusDp)
        assertEquals(1, theme.keyElevationDp)
        assertEquals(6, theme.keySpacingDp)
        assertEquals("black", Themes.BLACK.id)
        assertEquals("Black (AMOLED)", Themes.BLACK.name)
        assertEquals(KeyStyle.CLASSIC, Themes.BLACK.keyStyle)
    }

    /** Keeps primary and secondary engravings legible on idle and pressed black caps. */
    @Test
    fun blackMagicEngravingsMeetNormalTextContrast() {
        val theme = Themes.DARK
        for (background in listOf(theme.keyBackgroundColor, theme.keyPressedColor)) {
            for (foreground in listOf(theme.keyTextColor, theme.secondaryTextColor)) {
                assertTrue(ColorUtils.calculateContrast(foreground, background) >= 4.5)
            }
        }
        assertTrue(ColorUtils.calculateContrast(theme.keyTextColor, theme.backgroundColor) >= 4.5)
    }
}
