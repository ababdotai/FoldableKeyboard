package com.pckeyboard.ime.remote

/** Computes bounded overlay coordinates independently from Android window state. */
internal object UuOverlayGeometry {
    /** Leaves horizontal drag space unless the display is narrower than the usable minimum. */
    fun expandedWidth(availableWidth: Int, minimumWidth: Int): Int =
        minOf(availableWidth, maxOf(minimumWidth, (availableWidth * 0.9f).toInt()))

    /** Keeps the leading edge visible even when content exceeds the available dimension. */
    fun clamp(position: Int, start: Int, available: Int, content: Int): Int =
        position.coerceIn(start, start + (available - content).coerceAtLeast(0))
}
