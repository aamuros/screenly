package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement

/** Small English demo vocabulary; unsupported requests are preserved and may safely abstain. */
internal object SettingsWorkflow {
    fun canonicalGoal(goal: String): String {
        val normalized = NavigationProtocol.normalize(goal).trimEnd('?', '.', '!')
            .replace(Regex("^(how (do|can) i |please )"), "")
            .replace(Regex("\\b(my|the) "), "")
        val match = Regex("^(find(?: and open)?|open|change|adjust|enable|disable|turn on|turn off|navigate to|navigate toward) " +
            "(font[ -]size|wi[ -]?fi|dark[ -](?:theme|mode))(?: settings)?$").matchEntire(normalized)
            ?: return normalized
        val verb = match.groupValues[1].takeIf { it in setOf("enable", "disable", "turn on", "turn off") } ?: "open"
        val target = when (match.groupValues[2]) {
            "font-size" -> "font size"
            "wifi", "wi fi" -> "wi-fi"
            "dark-theme", "dark-mode" -> "dark theme"
            else -> match.groupValues[2]
        }
        return "$verb $target"
    }

    /**
     * Routes are restricted to stock Settings and must advertise the destination in their
     * own accessible summary. No generic Display/Network guess is enabled for other apps/OEMs.
     */
    fun verifiedRoutes(
        goal: String, packageName: String, stockSettings: Boolean,
        elements: List<AccessibleUiElement>, candidates: List<Int>, sdkInt: Int? = null
    ): Set<Int> {
        if (!stockSettings || packageName != "com.android.settings") return emptySet()
        val targets = NavigationRules.targetLabels(canonicalGoal(goal))
        val menus = when {
            "font size" in targets -> setOf("display", "display & touch", "display size and text", "display size & text", "advanced")
            "dark mode" in targets || "dark theme" in targets -> setOf("display", "display & touch")
            "wi-fi" in targets -> setOf("network & internet", "network and internet", "internet")
            else -> emptySet()
        }
        return candidates.filter { index ->
            val element = elements[index]
            val title = NavigationProtocol.normalize(element.text ?: "")
            val summary = NavigationProtocol.normalize(element.contentDescription ?: "")
            val advertised = title in menus && targets.any { target ->
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(target)}(?![\\p{L}\\p{N}])").containsMatchIn(summary)
            }
            // Verified on the API 37 Google Settings emulator: Internet opens the Wi-Fi page.
            // Its summary is the connected SSID, not a destination hint. Other APIs/OEMs abstain
            // unless their own menu summary advertises Wi-Fi. Never interpret an SSID as a hint.
            // API 30 Google Settings was observed exposing Dark theme directly under Display.
            // Its homepage summary advertises font size instead; do not generalize this profile.
            advertised || (sdkInt == 37 && title == "internet" && "wi-fi" in targets) ||
                (sdkInt == 30 && title == "display" && "dark theme" in targets)
        }.toSet()
    }
}
