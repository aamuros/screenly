package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement

/** Conservative rule helper, not a shared RulePlanner implementation. Null is abstention. */
internal object NavigationRules {
    private val goalAction = Regex("^(open|change|enable|disable|turn on|turn off) (.+)$")
    private val toggleClasses = setOf("Switch", "SwitchCompat", "CheckBox", "CompoundButton")

    fun select(goal: String, elements: List<AccessibleUiElement>, candidateIndices: List<Int>): Int? {
        if (!NavigationProtocol.validInput(goal, elements, candidateIndices)) return null
        val matches = matchingIndices(goal, elements, candidateIndices)
        val index = matches.singleOrNull() ?: return null
        return index.takeIf { rejection(goal, it, elements, candidateIndices) == null }
    }

    internal fun targetLabels(goal: String): Set<String> {
        val normalizedGoal = NavigationProtocol.normalize(goal)
        val target = goalAction.matchEntire(normalizedGoal)?.groupValues?.get(2) ?: normalizedGoal
        return when (target) {
            "dark mode", "dark theme" -> setOf("dark mode", "dark theme")
            "wi-fi", "wifi" -> setOf("wi-fi", "wifi")
            "mobile hotspot", "wi-fi hotspot" -> setOf("mobile hotspot", "wi-fi hotspot")
            else -> setOf(target)
        }
    }

    private fun matchingIndices(goal: String, elements: List<AccessibleUiElement>, candidates: List<Int>): List<Int> {
        val labels = targetLabels(goal)
        return candidates.filter { index ->
            NavigationProtocol.labelsOf(elements[index]).any { NavigationProtocol.normalize(it) in labels }
        }
    }

    /** Applies the same deterministic safeguards to rules and model selections. Not semantic proof. */
    fun rejection(
        goal: String, index: Int, elements: List<AccessibleUiElement>, candidates: List<Int>,
        verifiedRouteIndices: Set<Int>? = null
    ): NavigationRejection? {
        if (!NavigationProtocol.validInput(goal, elements, candidates)) return NavigationRejection.INVALID_INPUT
        if (!NavigationProtocol.validTarget(index, elements, candidates)) return NavigationRejection.TARGET_NOT_ALLOWED
        val labels = NavigationProtocol.labelsOf(elements[index]).map(NavigationProtocol::normalize).toSet()
        if (labels.isEmpty()) return NavigationRejection.UNLABELLED_TARGET
        val verb = goalAction.matchEntire(NavigationProtocol.normalize(goal))?.groupValues?.get(1)
        val directMatches = matchingIndices(goal, elements, candidates)
        val toggleMatches = directMatches.filter { elements[it].className?.substringAfterLast('.') in toggleClasses }
        // A settings entry and its adjacent switch can share a label but perform different
        // actions. The explicit goal and known class distinguish them; identical rows/toggles
        // are still ambiguous. This distinction is only enabled in the live validated path.
        val matches = when {
            verifiedRouteIndices == null -> directMatches
            verb in setOf("open", "change") -> directMatches.filter { it !in toggleMatches }
            verb in setOf("enable", "disable", "turn on", "turn off") && toggleMatches.isNotEmpty() -> toggleMatches
            else -> directMatches
        }
        if (verifiedRouteIndices != null && verb in setOf("open", "change") && index in toggleMatches) {
            return NavigationRejection.UNSAFE_TOGGLE
        }
        if (matches.size > 1) return NavigationRejection.AMBIGUOUS_TARGET
        // Shared labels are ambiguous unless the goal exactly identifies this candidate by another label.
        if (matches.singleOrNull() != index && candidates.any { other ->
                other != index && NavigationProtocol.labelsOf(elements[other]).any { NavigationProtocol.normalize(it) in labels }
            }) return NavigationRejection.AMBIGUOUS_TARGET
        if (matches.size == 1 && matches.single() != index) return NavigationRejection.CONTRADICTS_EXACT_TARGET
        if (matches.isEmpty()) {
            // A visible but unavailable exact target must not be replaced with an unrelated control.
            if (matchingIndices(goal, elements, elements.indices.toList()).isNotEmpty()) {
                return NavigationRejection.TARGET_UNAVAILABLE
            }
            // Bound semantic navigation to a small explicit vocabulary; unsupported routes abstain.
            // These are possible intermediate menus, never an inferred hierarchy or a completion claim.
            val target = targetLabels(goal)
            val routeLabels = when {
                "font size" in target || "dark mode" in target -> setOf("display", "display & brightness")
                "wi-fi" in target || "wi-fi settings" in target || "wifi settings" in target ->
                    setOf("network", "network & internet", "wi-fi", "wifi")
                "mobile hotspot" in target -> setOf("network", "network & internet", "hotspot & tethering")
                else -> emptySet()
            }
            val routes = if (verifiedRouteIndices != null) candidates.filter { it in verifiedRouteIndices }
                else candidates.filter { candidate ->
                    NavigationProtocol.labelsOf(elements[candidate]).any { NavigationProtocol.normalize(it) in routeLabels }
                }
            if (routes.size > 1) return NavigationRejection.AMBIGUOUS_TARGET
            if (routes.singleOrNull() != index) return NavigationRejection.UNSUPPORTED_TARGET
        }
        if (verb in setOf("enable", "disable", "turn on", "turn off")) {
            val element = elements[index]
            val isToggle = element.className?.substringAfterLast('.') in toggleClasses
            // A direct enable/disable match requires an identified toggle; menu routes may be non-toggles.
            if (index in matches && !isToggle) return NavigationRejection.UNSAFE_TOGGLE
            val desiredChecked = verb == "enable" || verb == "turn on"
            if (isToggle && element.checked == desiredChecked) return NavigationRejection.UNSAFE_TOGGLE
        }
        return null
    }
}

internal enum class NavigationRejection {
    INVALID_INPUT, TARGET_NOT_ALLOWED, UNLABELLED_TARGET, AMBIGUOUS_TARGET, CONTRADICTS_EXACT_TARGET,
    TARGET_UNAVAILABLE, UNSUPPORTED_TARGET, UNSAFE_TOGGLE
}
