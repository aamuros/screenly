package com.screenly.app

/** Minimal window data for deterministic tests without Android framework classes. */
internal data class ApplicationWindowCandidate(
    val id: Int,
    val isApplication: Boolean,
    val isActive: Boolean,
    val isFocused: Boolean
)

/**
 * A focusable Screenly overlay can leave the underlying app window neither active nor focused.
 * Reuse only the exact previously observed application window while that panel is open.
 * Never fall back to an arbitrary window or an accessibility overlay.
 */
internal fun selectApplicationWindowId(
    candidates: List<ApplicationWindowCandidate>,
    previousWindowId: Int?,
    assistantPanelOpen: Boolean
): Int? {
    val applications = candidates.filter { it.isApplication }
    return applications.firstOrNull { it.isActive }?.id
        ?: applications.firstOrNull { it.isFocused }?.id
        ?: if (assistantPanelOpen && previousWindowId != null) {
            applications.firstOrNull { it.id == previousWindowId }?.id
        } else null
}
