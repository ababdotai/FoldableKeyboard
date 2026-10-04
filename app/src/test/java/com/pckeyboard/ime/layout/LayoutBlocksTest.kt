package com.pckeyboard.ime.layout

import android.view.KeyEvent
import com.pckeyboard.ime.model.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies shared PC-layout row invariants. */
class LayoutBlocksTest {

    /** Verifies missing and unknown saved values preserve Windows as the compatibility default. */
    @Test
    fun keyboardPlatformDefaultsToWin() {
        assertEquals(KeyboardPlatform.WIN, KeyboardPlatform.fromPreference(null))
        assertEquals(KeyboardPlatform.WIN, KeyboardPlatform.fromPreference("unknown"))
        assertEquals(KeyboardPlatform.MAC, KeyboardPlatform.fromPreference("mac"))
    }

    /** Verifies F12 is visible without changing the function row's total width. */
    @Test
    fun functionRowIncludesF12AndKeepsFourteenUnits() {
        val row = LayoutBlocks.fnRow()

        assertTrue(row.any { it.label == "F12" && it.keyCode == KeyEvent.KEYCODE_F12 })
        assertTrue(row.any { it.label == "Home" })
        assertTrue(row.any { it.label == "End" })
        assertEquals(15, row.size)
        assertEquals(14f, row.sumOf { it.widthWeight.toDouble() }.toFloat(), 0.0001f)
    }

    /** Verifies the default platform preserves the established Windows control layout. */
    @Test
    fun winPlatformKeepsExistingControlAndNavigationRows() {
        val layout = LayoutBlocks.applyPlatform(EnglishLayout.main(), KeyboardPlatform.WIN)

        assertEquals(
            listOf("Ctrl", "🌐", "Alt", "space", "123", "◀", "▼", "▶"),
            layout.rows.last().map { it.label },
        )
        assertTrue(layout.rows.first().any { it.type == KeyType.HOME })
        assertTrue(layout.rows.first().any { it.type == KeyType.END })
        assertEquals(14f, layout.rows.last().totalWeight(), 0.0001f)
    }

    /** Verifies the Mac layout has MacBook modifiers, navigation, and stable row widths. */
    @Test
    fun macPlatformBuildsMacBookModifierAndArrowRows() {
        val layout = LayoutBlocks.applyPlatform(EnglishLayout.main(), KeyboardPlatform.MAC)
        val controlRow = layout.rows.last()
        val bottomLetterRow = layout.rows[layout.rows.lastIndex - 1]

        assertEquals(
            listOf("fn", "⌃", "⌥", "⌘", "space", "⌘", "⌥", "←", "↓", "→"),
            controlRow.map { it.label },
        )
        assertEquals(
            listOf(
                KeyType.FN,
                KeyType.CTRL,
                KeyType.ALT,
                KeyType.META,
                KeyType.SPACE,
                KeyType.META,
                KeyType.ALT,
                KeyType.ARROW_LEFT,
                KeyType.ARROW_DOWN,
                KeyType.ARROW_RIGHT,
            ),
            controlRow.map { it.type },
        )
        assertEquals(KeyEvent.KEYCODE_FUNCTION, controlRow.first().keyCode)
        assertEquals(
            listOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT),
            controlRow.filter { it.type == KeyType.META }.map { it.keyCode },
        )
        assertEquals(
            listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT),
            controlRow.filter { it.type == KeyType.ALT }.map { it.keyCode },
        )
        assertEquals(5f, controlRow.first { it.type == KeyType.SPACE }.widthWeight, 0f)
        assertEquals(14f, controlRow.totalWeight(), 0.0001f)
        assertFalse(controlRow.any { it.type == KeyType.SYMBOL_SWITCH })
        assertTrue(bottomLetterRow.any { it.type == KeyType.ARROW_UP })
        assertEquals(14f, bottomLetterRow.totalWeight(), 0.0001f)
        assertTrue(layout.rows.first().any { it.type == KeyType.PAGE_UP && it.label == "PageUp" })
        assertTrue(layout.rows.first().any { it.type == KeyType.PAGE_DOWN && it.label == "PageDn" })
        assertFalse(layout.rows.first().any { it.type == KeyType.HOME || it.type == KeyType.END })
    }

    /** Verifies every shipped locale keeps 14-unit letter and control rows on Mac. */
    @Test
    fun macPlatformPreservesRowWidthsAcrossLocales() {
        LayoutRegistry.available.forEach { pack ->
            val layout = LayoutBlocks.applyPlatform(pack.main, KeyboardPlatform.MAC)

            assertEquals(pack.id, 14f, layout.rows.last().totalWeight(), 0.0001f)
            assertEquals(
                pack.id,
                14f,
                layout.rows[layout.rows.lastIndex - 1].totalWeight(),
                0.0001f,
            )
        }
    }

    /** Verifies desktop platform selection does not rewrite either symbol page. */
    @Test
    fun macPlatformLeavesSymbolPagesUnchanged() {
        val symbols = EnglishLayout.symbols()
        val shiftedSymbols = EnglishLayout.symbolsShift()

        assertEquals(symbols, LayoutBlocks.applyPlatform(symbols, KeyboardPlatform.MAC))
        assertEquals(
            shiftedSymbols,
            LayoutBlocks.applyPlatform(shiftedSymbols, KeyboardPlatform.MAC),
        )
    }

    /** Returns the sum of row key weights for concise invariant assertions. */
    private fun List<com.pckeyboard.ime.model.Key>.totalWeight(): Float =
        sumOf { it.widthWeight.toDouble() }.toFloat()
}
