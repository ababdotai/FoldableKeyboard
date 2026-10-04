package com.pckeyboard.ime.remote

import kotlin.math.roundToInt

/** Computes bounded overlay coordinates independently from Android window state. */
internal object UuOverlayGeometry {
    /** Leaves horizontal drag space unless the display is narrower than the usable minimum. */
    fun expandedWidth(availableWidth: Int, minimumWidth: Int): Int =
        minOf(availableWidth, maxOf(minimumWidth, (availableWidth * 0.9f).toInt()))

    /** Keeps the leading edge visible even when content exceeds the available dimension. */
    fun clamp(position: Int, start: Int, available: Int, content: Int): Int =
        position.coerceIn(start, start + (available - content).coerceAtLeast(0))

    /** Fits a manually calibrated dock inside the usable display, leaving remote content visible. */
    fun dockedHeight(availableHeight: Int, ratio: Float, minimumHeight: Int): Int {
        if (availableHeight <= 0) return 0
        val maximum = (availableHeight * 0.85f).toInt().coerceAtLeast(1)
        val minimum = minOf(minimumHeight.coerceAtLeast(1), (availableHeight * 0.45f).toInt().coerceAtLeast(1))
        val safeRatio = if (ratio.isFinite()) ratio else 0.46f
        return (availableHeight * safeRatio.coerceIn(0.15f, 0.85f)).roundToInt().coerceIn(minimum, maximum)
    }

    /** Normalizes a drag height using the same bounds as the rendered dock. */
    fun dockedRatio(availableHeight: Int, height: Int, minimumHeight: Int): Float =
        if (availableHeight <= 0) 0.46f else
            (dockedHeight(availableHeight, height.toFloat() / availableHeight, minimumHeight).toFloat() / availableHeight)
                .coerceIn(0.15f, 0.85f)
}
