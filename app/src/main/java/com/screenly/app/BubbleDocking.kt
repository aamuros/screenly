package com.screenly.app

/** Horizontal docking only; release height remains under the user's control. */
internal object BubbleDocking {
    fun nearestSide(centerX: Int, left: Int, right: Int): Boolean =
        centerX >= left + (right - left) / 2

    fun position(
        left: Int, top: Int, right: Int, bottom: Int,
        width: Int, height: Int, margin: Int,
        rightSide: Boolean, preferredY: Int
    ): Pair<Int, Int> {
        val horizontalMargin = margin.coerceAtMost(((right - left - width).coerceAtLeast(0)) / 2)
        val verticalMargin = margin.coerceAtMost(((bottom - top - height).coerceAtLeast(0)) / 2)
        val minX = left + horizontalMargin
        val maxX = (right - width - horizontalMargin).coerceAtLeast(minX)
        val minY = top + verticalMargin
        val maxY = (bottom - height - verticalMargin).coerceAtLeast(minY)
        return Pair(if (rightSide) maxX else minX, preferredY.coerceIn(minY, maxY))
    }

    fun panelPosition(
        left: Int, top: Int, right: Int, bottom: Int,
        width: Int, height: Int, margin: Int,
        rightSide: Boolean, bubbleY: Int, bubbleSize: Int
    ): Pair<Int, Int> = position(
        left, top, right, bottom, width, height, margin, rightSide,
        bubbleY + bubbleSize / 2 - height / 2
    )
}
