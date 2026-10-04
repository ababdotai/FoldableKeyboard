package com.pckeyboard.ime.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.ViewGroup
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyboardLayout
import com.pckeyboard.ime.view.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Checks live theme changes and persistence separately from native keycap rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MagicThemeIntegrationTest {
    /** Upgrades built-in identities without overriding stored selections or custom themes. */
    @Test
    fun selectionPreservesExistingThemeIdentities() {
        val context: Context = RuntimeEnvironment.getApplication()
        val repository = ThemeRepository(context)
        assertSame(Themes.MAGIC, repository.getSelectedTheme())
        repository.selectTheme("light")
        assertEquals(KeyStyle.MAGIC, ThemeRepository(context).getSelectedTheme().keyStyle)
        repository.selectTheme(Themes.DARK.id)
        assertEquals(Themes.DARK, ThemeRepository(context).getSelectedTheme())
        assertEquals(KeyStyle.MAGIC, ThemeRepository(context).getSelectedTheme().keyStyle)
        val custom = Themes.DARK.copy(id = "custom_saved", name = "Saved")
        repository.saveCustomTheme(custom)
        repository.selectTheme(custom.id)
        assertEquals(custom, ThemeRepository(context).getSelectedTheme())
    }

    /** Unsubscribes live consumers so a closed overlay cannot be revived by later settings edits. */
    @Test
    fun observerOnlyReportsRelevantChangesUntilClosed() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        var changes = 0
        val close = repository.observeSelectedTheme { changes++ }
        repository.selectTheme(Themes.DARK.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        repository.saveCustomTheme(Themes.DARK.copy(id = "unselected_custom"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        close()
        repository.selectTheme(Themes.MAGIC.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
    }

    /** Maintains transparent popup space and changes only the row chassis when previewing themes. */
    @Test
    fun liveThemePreviewNeverPaintsOverTransparentPopupSpace() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication())
        keyboard.bind(KeyboardLayout("test", "Test", listOf(listOf(Key.letter("a")))), Themes.DARK)
        val main = keyboard.getChildAt(0) as ViewGroup
        val rows = main.getChildAt(main.childCount - 1)
        keyboard.updateTheme(Themes.MAGIC)
        assertEquals(Color.TRANSPARENT, (keyboard.background as ColorDrawable).color)
        assertTrue(rows.background is GradientDrawable)
        assertTrue(rows.paddingTop > 0)
        keyboard.updateTheme(Themes.DARK)
        assertEquals(Color.TRANSPARENT, (keyboard.background as ColorDrawable).color)
        assertTrue(rows.background is GradientDrawable)
        assertTrue(rows.paddingTop > 0)
        keyboard.updateTheme(Themes.BLACK)
        assertEquals(Color.TRANSPARENT, (keyboard.background as ColorDrawable).color)
        assertEquals(Themes.BLACK.backgroundColor, (rows.background as ColorDrawable).color)
        assertEquals(0, rows.paddingTop)
    }
}
