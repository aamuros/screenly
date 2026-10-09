package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement
import java.util.Locale

/** Conservative rule helper, not a shared RulePlanner implementation. Null is abstention. */
internal object NavigationRules {
    private val whitespace = Regex("\\s+")
    private val goalAction = Regex("^(open|enable|disable|turn on|turn off) (.+)$")
    private val toggleClasses = setOf("Switch", "SwitchCompat", "CheckBox", "CompoundButton")

    fun select(goal: String, elements: List<AccessibleUiElement>, candidateIndices: List<Int>): Int? {
        if (!NavigationProtocol.validCandidates(elements, candidateIndices)) return null
        val normalizedGoal = normalize(goal)
        val action = goalAction.matchEntire(normalizedGoal)
        val target = action?.groupValues?.get(2) ?: normalizedGoal
        val labels = when (target) {
            "dark mode", "dark theme" -> setOf("dark mode", "dark theme")
            "wi-fi", "wifi" -> setOf("wi-fi", "wifi")
            "mobile hotspot", "wi-fi hotspot" -> setOf("mobile hotspot", "wi-fi hotspot")
            else -> setOf(target)
        }
        val matches = candidateIndices.filter { index ->
            NavigationProtocol.labelOf(elements[index])?.let { normalize(it) in labels } == true
        }
        val index = matches.singleOrNull() ?: return null
        val verb = action?.groupValues?.get(1)
        if (verb in setOf("enable", "disable", "turn on", "turn off")) {
            val element = elements[index]
            // checked=false alone does not imply a toggle; an unknown row must not be activated.
            if (element.className?.substringAfterLast('.') !in toggleClasses) return null
            val desiredChecked = verb == "enable" || verb == "turn on"
            if (element.checked == desiredChecked) return null
        }
        return index.takeIf { NavigationProtocol.validTarget(it, elements, candidateIndices) }
    }

    private fun normalize(value: String): String = value.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
}
