package com.pckeyboard.ime.theme

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import kotlin.math.roundToInt

/** Styles auxiliary controls to match the keyboard without changing their touch or click handling. */
internal fun Button.applyKeyboardChrome(theme: KeyboardTheme) {
    val density = resources.displayMetrics.density
    val magic = theme.keyStyle == KeyStyle.MAGIC
    val radius = (if (magic) 4f else theme.keyCornerRadiusDp.toFloat()) * density
    /** Creates an independent drawable so pressed state cannot leak between neighboring buttons. */
    fun face(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(density.roundToInt().coerceAtLeast(1), theme.dividerColor)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), face(theme.keyPressedColor))
        addState(intArrayOf(), face(if (magic) theme.keyBackgroundColor else theme.modifierKeyColor))
    }
    backgroundTintList = null
    background = InsetDrawable(states, (3 * density).roundToInt(), (5 * density).roundToInt(),
        (3 * density).roundToInt(), (5 * density).roundToInt())
    stateListAnimator = null
    elevation = 0f
    isAllCaps = false
    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    textSize = 12f
    setTextColor(if (magic) theme.keyTextColor else theme.modifierTextColor)
    minWidth = (56 * density).roundToInt()
    minimumWidth = minWidth
    minHeight = (48 * density).roundToInt()
    minimumHeight = minHeight
    setPadding((10 * density).roundToInt(), 0, (10 * density).roundToInt(), 0)
}
