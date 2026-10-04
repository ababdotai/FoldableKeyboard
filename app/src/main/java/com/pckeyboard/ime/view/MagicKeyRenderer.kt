package com.pckeyboard.ime.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import com.pckeyboard.ime.model.Key
import com.pckeyboard.ime.model.KeyType
import com.pckeyboard.ime.model.ModifierState
import com.pckeyboard.ime.theme.KeyboardTheme
import java.util.Locale

/** Fixed physical engravings, independent of the character currently being dispatched. */
internal data class MagicKeyLegend(val primary: String, val secondary: String? = null)

/** Returns keycap engravings without changing layout data, modifiers, or emitted characters. */
internal fun magicKeyLegend(key: Key): MagicKeyLegend = when (key.type) {
    KeyType.LETTER -> MagicKeyLegend(key.label.uppercase(Locale.ROOT))
    KeyType.CHAR -> if (key.shiftLabel != null && key.shiftLabel != key.label) {
        MagicKeyLegend(key.shiftLabel, key.label)
    } else MagicKeyLegend(key.label)
    KeyType.SPACE -> MagicKeyLegend("")
    KeyType.CTRL -> if (key.label == "⌃") MagicKeyLegend("⌃", "control") else MagicKeyLegend(key.label)
    KeyType.ALT -> if (key.label == "⌥") MagicKeyLegend("⌥", "option") else MagicKeyLegend(key.label)
    KeyType.META -> if (key.label == "⌘") MagicKeyLegend("⌘", "command") else MagicKeyLegend(key.label)
    KeyType.CAPS_LOCK -> MagicKeyLegend("caps lock")
    KeyType.BACKSPACE -> MagicKeyLegend("delete")
    KeyType.DELETE -> MagicKeyLegend("⌦")
    KeyType.ENTER -> MagicKeyLegend("return")
    KeyType.TAB -> MagicKeyLegend("tab")
    KeyType.ESC -> MagicKeyLegend("esc")
    KeyType.SHIFT -> MagicKeyLegend("shift")
    KeyType.PAGE_UP -> MagicKeyLegend("page", "up")
    KeyType.PAGE_DOWN -> MagicKeyLegend("page", "down")
    KeyType.ARROW_LEFT -> MagicKeyLegend("←")
    KeyType.ARROW_RIGHT -> MagicKeyLegend("→")
    KeyType.ARROW_UP -> MagicKeyLegend("↑")
    KeyType.ARROW_DOWN -> MagicKeyLegend("↓")
    else -> MagicKeyLegend(key.label)
}

/** Keeps blank or symbolic keycaps identifiable to accessibility services. */
internal fun magicKeyAccessibilityLabel(key: Key): String = when (key.type) {
    KeyType.SPACE -> "Space"
    KeyType.CTRL -> "Control"
    KeyType.ALT -> if (key.label == "⌥") "Option" else key.label
    KeyType.META -> if (key.label == "⌘") "Command" else key.label
    KeyType.CAPS_LOCK -> "Caps Lock"
    KeyType.BACKSPACE -> "Backspace"
    KeyType.DELETE -> "Forward Delete"
    KeyType.ENTER -> "Return"
    KeyType.SHIFT -> "Shift"
    KeyType.ARROW_LEFT -> "Left Arrow"
    KeyType.ARROW_RIGHT -> "Right Arrow"
    KeyType.ARROW_UP -> "Up Arrow"
    KeyType.ARROW_DOWN -> "Down Arrow"
    KeyType.PAGE_UP -> "Page Up"
    KeyType.PAGE_DOWN -> "Page Down"
    else -> key.label
}

/** Draws understated physical keycaps while leaving all interaction in [KeyView]. */
internal class MagicKeyRenderer(private val metrics: DisplayMetrics) {
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val face = RectF()
    private val edge = RectF()

    /** Renders a bounded keycap with subtle depth and non-blue modifier indicators. */
    fun draw(
        canvas: Canvas,
        key: Key,
        theme: KeyboardTheme,
        state: ModifierState.State,
        isDown: Boolean,
        width: Int,
        height: Int,
    ) {
        val gap = dp(theme.keySpacingDp / 2f)
        val depth = dp(theme.keyElevationDp.toFloat())
        val offset = if (isDown) depth else 0f
        face.set(gap, gap + offset, width - gap, height - gap - depth + offset)
        if (face.width() <= 0f || face.height() <= 0f) return
        val radius = dp(theme.keyCornerRadiusDp.toFloat()).coerceAtMost(face.height() / 3f)

        shape.style = Paint.Style.FILL
        edge.set(face)
        edge.offset(0f, if (isDown) dp(0.25f) else depth)
        shape.color = theme.dividerColor
        canvas.drawRoundRect(edge, radius, radius, shape)
        shape.color = if (isDown) theme.keyPressedColor else theme.keyBackgroundColor
        canvas.drawRoundRect(face, radius, radius, shape)

        edge.set(face)
        edge.inset(dp(0.35f), dp(0.35f))
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = dp(0.65f)
        shape.color = when {
            isDown -> theme.dividerColor
            theme.isDark -> ColorUtils.blendARGB(theme.keyBackgroundColor, Color.WHITE, 0.14f)
            else -> Color.WHITE
        }
        canvas.drawRoundRect(edge, radius, radius, shape)
        shape.style = Paint.Style.FILL

        drawLegend(canvas, key, theme)
        drawState(canvas, key, theme, state, radius)
    }

    /** Centers fixed engravings and scales them to fit narrow keys and half-height arrows. */
    private fun drawLegend(canvas: Canvas, key: Key, theme: KeyboardTheme) {
        val legend = magicKeyLegend(key)
        if (legend.primary.isEmpty()) return
        val preferred = when (key.type) {
            KeyType.LETTER -> 15f
            KeyType.CHAR -> 13f
            KeyType.FN -> 10f
            KeyType.ARROW_LEFT, KeyType.ARROW_RIGHT, KeyType.ARROW_UP, KeyType.ARROW_DOWN -> 14f
            else -> if (legend.secondary != null && key.type in MODIFIERS) 16f else 10f
        }
        if (legend.secondary == null) {
            drawText(canvas, legend.primary, face.centerY(), preferred,
                face.height() * 0.64f, theme.keyTextColor)
        } else {
            val modifier = key.type in MODIFIERS
            drawText(canvas, legend.primary, face.top + face.height() * 0.34f, preferred,
                face.height() * 0.35f, theme.keyTextColor)
            drawText(canvas, legend.secondary, face.top + face.height() * 0.73f,
                if (modifier) 8.5f else if (key.type == KeyType.CHAR) 13f else 10f,
                face.height() * 0.32f, theme.keyTextColor)
        }
    }

    /** Fits one label within its allotted height and horizontal inset without clipping. */
    private fun drawText(
        canvas: Canvas,
        value: String,
        centerY: Float,
        sizeSp: Float,
        maxHeight: Float,
        color: Int,
    ) {
        text.color = color
        text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        val availableWidth = (face.width() - dp(8f)).coerceAtLeast(1f)
        val scale = minOf(1f, availableWidth / text.measureText(value).coerceAtLeast(1f),
            maxHeight / (text.descent() - text.ascent()).coerceAtLeast(1f))
        text.textSize *= scale
        canvas.drawText(value, face.centerX(), centerY - (text.ascent() + text.descent()) / 2f, text)
    }

    /** Distinguishes one-shot and latched modifiers, with a green Caps Lock indicator. */
    private fun drawState(
        canvas: Canvas,
        key: Key,
        theme: KeyboardTheme,
        state: ModifierState.State,
        radius: Float,
    ) {
        if (key.type == KeyType.CAPS_LOCK) {
            shape.color = if (state == ModifierState.State.LOCKED) {
                Color.rgb(62, 151, 57)
            } else theme.dividerColor
            canvas.drawCircle(face.left + dp(7f), face.top + dp(7f), dp(1.5f), shape)
            return
        }
        if (state == ModifierState.State.OFF) return
        edge.set(face)
        edge.inset(dp(1f), dp(1f))
        shape.color = theme.secondaryTextColor
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = dp(if (state == ModifierState.State.LOCKED) 1.2f else 0.8f)
        canvas.drawRoundRect(edge, radius, radius, shape)
        shape.style = Paint.Style.FILL
        val cy = face.top + dp(5f)
        val cx = face.right - dp(5f)
        canvas.drawCircle(cx, cy, dp(1.3f), shape)
        if (state == ModifierState.State.LOCKED) {
            canvas.drawCircle(cx - dp(4f), cy, dp(1.3f), shape)
        }
    }

    /** Converts physical styling dimensions to screen pixels. */
    private fun dp(value: Float): Float = value * metrics.density

    private companion object {
        val MODIFIERS = setOf(KeyType.CTRL, KeyType.ALT, KeyType.META)
    }
}
