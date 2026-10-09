package com.screenly.app

internal data class ScreenObservation(
    val packageName: String,
    val windowId: Int,
    val elements: List<AccessibleUiElement>
)

/** A revision also rejects old picker callbacks after navigating away and back. */
internal class ScreenObservationState {
    var snapshot: ScreenObservation? = null
        private set
    var revision: Long = 0
        private set

    fun update(next: ScreenObservation): Boolean {
        val changed = next != snapshot
        if (changed) invalidateSelection()
        snapshot = next
        return changed
    }

    fun invalidateSelection() {
        revision++
    }

    fun clear() {
        snapshot = null
        invalidateSelection()
    }

    fun canSelect(expected: ScreenObservation, expectedRevision: Long, element: AccessibleUiElement): Boolean =
        revision == expectedRevision && snapshot == expected && element in expected.elements &&
            element.clickable && element.enabled && element.right > element.left && element.bottom > element.top
}

/** A fixed deadline, rather than a trailing debounce that can starve under continuous events. */
internal fun observationDelayMillis(lastCapture: Long?, now: Long): Long =
    if (lastCapture == null) 0 else (250L - (now - lastCapture)).coerceIn(0L, 250L)
