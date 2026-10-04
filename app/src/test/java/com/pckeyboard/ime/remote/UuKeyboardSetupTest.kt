package com.pckeyboard.ime.remote

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.google.android.material.materialswitch.MaterialSwitch
import com.pckeyboard.ime.R
import com.pckeyboard.ime.settings.KeyboardPrefs
import com.pckeyboard.ime.settings.UuKeyboardSetupController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the visible Command compatibility switch persists its explicit user choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UuKeyboardSetupTest {

    /** Defaults to UU compatibility and preserves a disabled choice when settings reopen. */
    @Test
    fun commandCompatibilityDefaultsOnAndPersistsSwitchChanges() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val context = ContextThemeWrapper(activity, R.style.Theme_PcKeyboard)
        try {
            assertTrue(KeyboardPrefs(activity).uuCommandCompatibility)
            val firstRoot = LayoutInflater.from(context).inflate(R.layout.activity_settings, null, false)
            val first = UuKeyboardSetupController(activity, firstRoot)
            try {
                val shortcutToggle = firstRoot.findViewById<MaterialSwitch>(R.id.uuShortcutBar)
                assertTrue(shortcutToggle.isChecked)
                shortcutToggle.performClick()
                assertFalse(KeyboardPrefs(activity).uuShortcutBar)
                val toggle = firstRoot.findViewById<MaterialSwitch>(R.id.uuCommandCompatibility)
                assertTrue(toggle.isChecked)
                toggle.performClick()
                assertFalse(KeyboardPrefs(activity).uuCommandCompatibility)
            } finally {
                first.close()
            }

            val secondRoot = LayoutInflater.from(context).inflate(R.layout.activity_settings, null, false)
            val second = UuKeyboardSetupController(activity, secondRoot)
            try {
                assertFalse(secondRoot.findViewById<MaterialSwitch>(R.id.uuShortcutBar).isChecked)
                val toggle = secondRoot.findViewById<MaterialSwitch>(R.id.uuCommandCompatibility)
                assertFalse(toggle.isChecked)
                toggle.performClick()
                assertTrue(KeyboardPrefs(activity).uuCommandCompatibility)
            } finally {
                second.close()
            }
        } finally {
            activityController.pause().stop().destroy()
        }
    }
}
