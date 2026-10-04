package com.pckeyboard.ime.branding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import com.pckeyboard.ime.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Validates launcher assets using Android's adaptive icon rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    /** Loads fractional insets on the minimum Android version without relying on legacy graphics. */
    @Test
    @Config(sdk = [26])
    fun minimumAndroidVersionLoadsAdaptiveResources() {
        val context = RuntimeEnvironment.getApplication()
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(resource) as AdaptiveIconDrawable
            assertTrue(icon.foreground.intrinsicWidth > 0)
        }
    }

    /** Keeps both launcher variants loadable and exports a masked preview for visual review. */
    @Test
    fun launcherVariantsUseTheSameNewArtwork() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("FoldableKeyboard", context.getString(R.string.app_name))
        val icons = listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round).map { resource ->
            val icon = requireNotNull(context.getDrawable(resource))
            assertTrue(icon is AdaptiveIconDrawable)
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(Canvas(bitmap))
            bitmap
        }
        assertTrue(icons[0].sameAs(icons[1]))
        val output = File("../temp/branding/launcher-api-${android.os.Build.VERSION.SDK_INT}.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { icons[0].compress(Bitmap.CompressFormat.PNG, 100, it) }
        val circle = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(circle)
        canvas.clipPath(Path().apply { addCircle(96f, 96f, 96f, Path.Direction.CW) })
        canvas.drawBitmap(icons[0], 0f, 0f, null)
        File(output.parentFile, "launcher-circle.png").outputStream().use {
            circle.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        circle.recycle()
        icons.forEach { it.recycle() }
    }
}
