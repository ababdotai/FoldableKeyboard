package com.pckeyboard.ime.remote

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.pckeyboard.ime.layout.KeyboardPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the compact toolbar without any injection or clipboard access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteShortcutBarTest {
    /** Keeps all seven explicit commands horizontally reachable at a narrow width. */
    @Test
    fun narrowToolbarPreservesOrderAndDispatchesOneCommandPerTap() {
        val clicked = mutableListOf<RemoteShortcut>()
        val bar = RemoteShortcutBar(RuntimeEnvironment.getApplication(), KeyboardPlatform.MAC, clicked::add)
        bar.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.AT_MOST))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        val row = bar.getChildAt(0) as LinearLayout
        assertEquals(7, row.childCount)
        assertTrue(row.measuredWidth > bar.measuredWidth)
        assertFalse(bar.isFocusable)
        for (index in 0 until row.childCount) {
            val button = row.getChildAt(index) as Button
            assertFalse(button.isFocusable)
            button.performClick()
        }
        assertEquals(RemoteShortcut.entries.toList(), clicked)
        bar.dispose()
        (row.getChildAt(0) as Button).performClick()
        assertEquals(7, clicked.size)
    }
}
