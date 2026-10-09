package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement

/** Structural validation for the historical text evaluation harness; never selects a target. */
internal object NavigationRules {
    @Suppress("UNUSED_PARAMETER")
    fun select(goal: String, elements: List<AccessibleUiElement>, candidateIndices: List<Int>): Int? = null

    @Suppress("UNUSED_PARAMETER")
    fun rejection(goal: String, index: Int, elements: List<AccessibleUiElement>, candidates: List<Int>,
        verifiedRouteIndices: Set<Int>? = null): NavigationRejection? = when {
        !NavigationProtocol.validInput(goal, elements, candidates) -> NavigationRejection.INVALID_INPUT
        !NavigationProtocol.validTarget(index, elements, candidates) -> NavigationRejection.TARGET_NOT_ALLOWED
        else -> null
    }
}

internal enum class NavigationRejection {
    INVALID_INPUT, TARGET_NOT_ALLOWED, UNLABELLED_TARGET, AMBIGUOUS_TARGET, CONTRADICTS_EXACT_TARGET,
    TARGET_UNAVAILABLE, UNSUPPORTED_TARGET, UNSAFE_TOGGLE
}
