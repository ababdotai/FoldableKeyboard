package com.pckeyboard.ime.theme

import android.content.res.Configuration
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Verifies opt-in system theme resolution without overwriting the remembered manual selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class SystemThemeRepositoryTest {
    /** Keeps existing installations in manual mode even when the device changes appearance. */
    @Test
    fun manualModeIsTheDefaultAndIgnoresSystemNightChanges() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        assertFalse(repository.followSystemTheme)
        repository.selectTheme(Themes.BLACK.id)
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals(Themes.BLACK, repository.getSelectedTheme())
        assertFalse(ThemeRepository(RuntimeEnvironment.getApplication()).followSystemTheme)
    }

    /** Resolves light and dark dynamically and restores a saved custom choice on opt-out. */
    @Test
    fun followingSystemPreservesManualCustomThemeAcrossRepositoryRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val repository = ThemeRepository(app)
        val custom = Themes.BLACK.copy(id = "saved_custom", name = "Saved custom")
        repository.saveCustomTheme(custom)
        repository.selectTheme(custom.id)
        repository.followSystemTheme = true
        assertEquals(Themes.LIGHT, repository.getSelectedTheme())
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals(Themes.DARK, repository.getSelectedTheme())
        val restored = ThemeRepository(app)
        assertTrue(restored.followSystemTheme)
        assertEquals(Themes.DARK, restored.getSelectedTheme())
        restored.followSystemTheme = false
        assertEquals(custom, repository.getSelectedTheme())
    }

    /** Makes an explicit theme-card selection leave automatic mode in one user action. */
    @Test
    fun selectingAThemeTurnsFollowingOff() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        repository.followSystemTheme = true
        repository.selectTheme(Themes.BLACK.id)
        assertFalse(repository.followSystemTheme)
        assertEquals(Themes.BLACK, repository.getSelectedTheme())
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals(Themes.BLACK, repository.getSelectedTheme())
    }

    /** Deletes an unavailable manual fallback without accidentally disabling automatic appearance. */
    @Test
    fun deletingRememberedCustomThemeKeepsSystemFollowingEnabled() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        val custom = Themes.BLACK.copy(id = "deleted_custom", name = "Deleted custom")
        repository.saveCustomTheme(custom)
        repository.selectTheme(custom.id)
        repository.followSystemTheme = true
        RuntimeEnvironment.setQualifiers("+night")
        repository.deleteCustomTheme(custom.id)
        assertTrue(repository.followSystemTheme)
        assertEquals(Themes.DARK, repository.getSelectedTheme())
        repository.followSystemTheme = false
        assertEquals(Themes.LIGHT, repository.getSelectedTheme())
    }

    /** Reads the caller's configuration rather than silently resolving the application context. */
    @Test
    fun contextualNightModeOverridesApplicationLightMode() {
        val app = RuntimeEnvironment.getApplication()
        val night = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val repository = ThemeRepository(app.createConfigurationContext(night))
        repository.followSystemTheme = true
        assertEquals(Themes.DARK, repository.getSelectedTheme())
        assertEquals(Themes.LIGHT, ThemeRepository(app).getSelectedTheme())
    }

    /** Notifies mounted consumers on opt-in and opt-out, then releases the preference listener. */
    @Test
    fun observersReceiveToggleChangesUntilUnsubscribed() {
        val repository = ThemeRepository(RuntimeEnvironment.getApplication())
        repository.selectTheme(Themes.BLACK.id)
        shadowOf(Looper.getMainLooper()).idle()
        val resolved = mutableListOf<KeyboardTheme>()
        val close = repository.observeSelectedTheme { resolved.add(repository.getSelectedTheme()) }
        repository.followSystemTheme = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Themes.LIGHT), resolved)
        repository.followSystemTheme = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Themes.LIGHT, Themes.BLACK), resolved)
        close()
        repository.followSystemTheme = true
        repository.selectTheme(Themes.DARK.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, resolved.size)
    }
}
