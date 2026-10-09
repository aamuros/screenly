package com.screenly.app

internal data class BubbleCorner(val right: Boolean, val bottom: Boolean)

/** Math-only docking behavior, independent of the Android view/window lifecycle. */
internal object BubbleDocking {
    fun nearestCorner(
        centerX: Int, centerY: Int,
        left: Int, top: Int, right: Int, bottom: Int
    ): BubbleCorner = BubbleCorner(
        right = centerX >= left + (right - left) / 2,
        bottom = centerY >= top + (bottom - top) / 2
    )

    fun position(
        left: Int, top: Int, right: Int, bottom: Int,
        size: Int, margin: Int, corner: BubbleCorner
    ): Pair<Int, Int> {
        val xMin = left + margin
        val xMax = (right - size - margin).coerceAtLeast(xMin)
        val yMin = top + margin
        val yMax = (bottom - size - margin).coerceAtLeast(yMin)
        return Pair(if (corner.right) xMax else xMin, if (corner.bottom) yMax else yMin)
    }
}
