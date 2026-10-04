package com.pckeyboard.ime.settings

import com.pckeyboard.ime.R
import com.pckeyboard.ime.updater.UpdateDownloader
import com.pckeyboard.ime.updater.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Keeps the visible product identity consistent without changing its installed Android identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBrandingTest {
    /** Presents the new brand throughout setup while preserving upgrade-compatible package identity. */
    @Test
    fun appAndSetupUseFoldableKeyboardBrand() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("FoldableKeyboard", context.getString(R.string.app_name))
        listOf(R.string.setup_step_enable, R.string.setup_step_select, R.string.uu_ime_entry_hint,
            R.string.uu_overlay_channel).forEach { resource ->
            assertTrue(context.getString(resource).contains("FoldableKeyboard"))
        }
        assertEquals("com.pckeyboard.ime", context.packageName)
    }

    /** Names Gradle's project consistently with the installed app's visible label. */
    @Test
    fun gradleProjectUsesFoldableKeyboardBrand() {
        val settings = File("../settings.gradle.kts").readText()
        assertTrue(settings.lineSequence().any { it.trim() == "rootProject.name = \"FoldableKeyboard\"" })
    }

    /** Gives downloaded APKs a recognizable product name without weakening filename sanitization. */
    @Test
    fun downloadedApkUsesBrandAndSanitizedVersion() {
        val context = RuntimeEnvironment.getApplication()
        val info = UpdateInfo("1.2/test", "", null, "https://github.com/ababdotai/FoldableKeyboard/releases")
        val destination = UpdateDownloader.apkFileFor(context, info)
        assertEquals("FoldableKeyboard-1.2_test.apk", destination.name)
        assertEquals(context.cacheDir, destination.parentFile)
    }
}
