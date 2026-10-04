package com.pckeyboard.ime.settings

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.pckeyboard.ime.R
import com.pckeyboard.ime.databinding.ActivitySettingsBinding
import com.pckeyboard.ime.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks settings organization without starting Shizuku or the update checker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class SettingsPresentationTest {
    /** Ensures compact option labels fit a narrow phone without hidden text. */
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowPhoneKeepsOptionLabelsVisible() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_PcKeyboard)
            val binding = ActivitySettingsBinding.inflate(activity.layoutInflater)
            binding.root.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            binding.root.layout(0, 0, 320, 640)
            listOf(
                binding.rightOfSpaceSymbols, binding.rightOfSpaceEmoji,
                binding.rightOfSpaceAlt, binding.rightOfSpaceMeta,
                binding.interval12h, binding.interval24h,
            ).forEach { button ->
                val available = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                assertTrue("${button.text} has room for its label", available > 0)
                val textLayout = requireNotNull(button.layout)
                for (line in 0 until textLayout.lineCount) {
                    assertEquals("${button.text} must not be ellipsized", 0, textLayout.getEllipsisCount(line))
                    assertTrue("${button.text} must fit", textLayout.getLineWidth(line) <= available + 1f)
                }
            }
            val label = binding.intervalContainer.getChildAt(0)
            assertTrue("Interval label stays above its controls", label.bottom <= binding.intervalToggle.top)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Keeps the working UU entry separate from the unrelated IME dispatch selector. */
    @Test
    fun uuSetupIsFirstAndUpdatesAreLast() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_PcKeyboard)
            val binding = ActivitySettingsBinding.inflate(activity.layoutInflater)
            val sections = binding.scrollView.getChildAt(0) as ViewGroup
            assertEquals(card(binding.uuStartKeyboard), sections.getChildAt(0))
            assertNotSame(card(binding.uuStartKeyboard), card(binding.dispatchModeGroup))
            assertEquals(card(binding.btnCheckUpdates), sections.getChildAt(sections.childCount - 1))
            assertEquals("启动 UU 悬浮键盘", binding.uuStartKeyboard.text.toString())
            assertEquals("普通文字", binding.dispatchNormal.text.toString())
            assertTrue(activity.getString(R.string.settings_input_mode_hint).contains("与 UU 悬浮键盘独立"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Localizes built-in theme labels without changing persisted identifiers. */
    @Test
    fun builtInThemeLabelsAreChinese() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_PcKeyboard)
            val parent = android.widget.FrameLayout(activity)
            val adapter = ThemeAdapter({}, {}, {}, Themes.DARK.id)
            adapter.submit(listOf(Themes.DARK))
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("深色", holder.itemView.findViewById<TextView>(R.id.themeSubtitle).text.toString())
            assertEquals("Magic Keyboard · 黑色", holder.itemView.findViewById<TextView>(R.id.themeName).text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Finds the containing settings card for ordering and separation assertions. */
    private fun card(view: View): MaterialCardView {
        var parent = view.parent
        while (parent !is MaterialCardView) parent = parent.parent
        return parent
    }
}
