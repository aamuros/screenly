package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement

/** Compatibility with the historical evaluation harness. No route hints are supplied. */
internal object SettingsWorkflow {
    fun canonicalGoal(goal: String): String = goal
    @Suppress("UNUSED_PARAMETER")
    fun verifiedRoutes(goal: String, packageName: String, stockSettings: Boolean,
        elements: List<AccessibleUiElement>, candidates: List<Int>, sdkInt: Int? = null): Set<Int> = emptySet()
}
