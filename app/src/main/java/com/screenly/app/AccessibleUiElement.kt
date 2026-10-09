package com.screenly.app

/** A transient snapshot. Bounds are screen coordinates in pixels. */
data class AccessibleUiElement(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    internal fun intersectsScreen(width: Int, height: Int): Boolean =
        right > left && bottom > top && width > 0 && height > 0 &&
            right > 0 && bottom > 0 && left < width && top < height
}

private val observationWhitespace = Regex("[\\p{Cc}\\p{Cf}\\p{Z}\\s]+")

/** Keeps each Logcat value short and on one line, including untrusted app text. */
internal fun sanitizeObservationText(value: CharSequence?): String? {
    return value?.toString()
        ?.replace(observationWhitespace, " ")
        ?.trim()
        ?.take(160)
        ?.let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        ?.takeIf { it.isNotEmpty() }
}
