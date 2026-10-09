package com.screenly.app

/** Display pixels from Android window bounds, never inferred from model output. */
internal data class ImageCropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid: Boolean get() = right > left && bottom > top
    private val area: Long get() = (right.toLong() - left) * (bottom.toLong() - top)

    /** Exclude an edge overlay by trimming a whole band. Interior occlusions are rejected. */
    fun excluding(other: ImageCropBounds): ImageCropBounds? {
        if (other.right <= left || other.left >= right || other.bottom <= top || other.top >= bottom) return this
        val candidates = mutableListOf<ImageCropBounds>()
        if (other.left <= left) candidates += copy(left = other.right)
        if (other.right >= right) candidates += copy(right = other.left)
        if (other.top <= top) candidates += copy(top = other.bottom)
        if (other.bottom >= bottom) candidates += copy(bottom = other.top)
        return candidates.filter { it.valid }.maxByOrNull { it.area }
    }
}
