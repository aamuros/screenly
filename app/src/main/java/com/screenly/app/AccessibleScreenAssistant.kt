package com.screenly.app

import java.util.Locale

/** An honest, deterministic accessibility fallback. It does not interpret screenshot pixels. */
internal object AccessibleScreenAssistant {
    data class VisibleItem(val index: Int, val title: String, val detail: String)
    enum class GuidancePhase { NEEDS_ACTION, NEEDS_REVIEW, COMPLETED, CANCELLED }
    data class Guidance(
        val goal: String,
        val step: Int,
        val instruction: String,
        val status: String,
        val targetIndex: Int?,
        val targetLabel: String?,
        val observed: ScreenObservation,
        val phase: GuidancePhase = GuidancePhase.NEEDS_ACTION
    )

    fun visibleItems(observation: ScreenObservation): List<VisibleItem> =
        observation.elements.mapIndexedNotNull { index, element ->
            if (!element.enabled || !element.clickable || element.right <= element.left ||
                element.bottom <= element.top) return@mapIndexedNotNull null
            val label = label(element) ?: observation.elements.firstOrNull { child ->
                child !== element && (child.text != null || child.contentDescription != null) &&
                    child.left >= element.left && child.top >= element.top &&
                    child.right <= element.right && child.bottom <= element.bottom &&
                    child.right > child.left && child.bottom > child.top
            }?.let(::label) ?: return@mapIndexedNotNull null
            VisibleItem(index, label, describe(label))
        }.distinctBy { normalize(it.title) }.take(12)

    fun describeScreen(observation: ScreenObservation): String =
        "Accessible controls in ${friendlyApp(observation.packageName)}. " +
            "These labels come from Android accessibility, not screenshot image analysis."

    fun ask(question: String, observation: ScreenObservation): String {
        if (question.isBlank()) return "Enter a question about the current screen."
        // Useful first-run help even before the separately distributed model is installed.
        // These are fixed, general instructions, never claims about unseen screen pixels.
        val q = normalize(question)
        if (Regex("\\b(call|dial|phone somebody|phone someone)\\b").containsMatchIn(q)) {
            return "To call someone, open the Phone app. Choose Contacts to find a person, or " +
                "Keypad to enter their number, then press the call button. " +
                "This is general offline guidance, not a screenshot analysis."
        }
        if (q.contains("email") || q.contains("e-mail") || q.contains("gmail")) {
            return "To write and send an email, open Gmail or your email app, tap Compose, " +
                "enter the recipient in To, add a subject and message, then tap Send. " +
                "If you mean creating a new email account, tell me. " +
                "This is general offline guidance, not a screenshot analysis."
        }
        val result = choose(question, observation)
        return when {
            result != null ->
                "From the accessible controls on this screen, I found “${result.title}”. " +
                    "You can tap it to continue. I have not visually analyzed the screenshot."
            visibleItems(observation).isEmpty() ->
                "I cannot identify actionable controls on this screen. " +
                    "For open-ended answers, import the local AI model in Screenly. " +
                    "Basic offline help works without it."
            else ->
                "I cannot safely identify a control for that question. " +
                    "Visible accessible options include: " +
                    visibleItems(observation).take(4).joinToString(", ") { it.title } +
                    ". Screenshot vision inference is not connected yet."
        }
    }

    fun begin(goal: String, observation: ScreenObservation): Guidance {
        val cleanGoal = goal.trim().take(160)
        val result = choose(cleanGoal, observation)
        return if (result == null) Guidance(
            cleanGoal, 1,
            "I cannot identify a safe next control from this screen. " +
                "Navigate to the relevant app or Settings and tap Check my screen.",
            "Awaiting a recognizable screen. Vision guidance is not yet connected.",
            null, null, observation, GuidancePhase.NEEDS_REVIEW
        ) else Guidance(
            cleanGoal, 1, "Find and tap “${result.title}”.",
            "Step suggested from accessibility labels. Perform the action yourself.",
            result.index, result.title, observation
        )
    }

    fun check(previous: Guidance, observation: ScreenObservation): Guidance {
        if (previous.phase == GuidancePhase.CANCELLED ||
            previous.phase == GuidancePhase.COMPLETED) return previous
        if (observation == previous.observed) return previous.copy(
            status = "The accessible screen has not changed. I cannot verify the action yet."
        )
        val oldElement = previous.targetIndex?.let { previous.observed.elements.getOrNull(it) }
        val newMatching = previous.targetLabel?.let { wanted ->
            observation.elements.firstOrNull { it.enabled && normalize(label(it) ?: "") == normalize(wanted) }
        }
        val normalizedGoal = normalize(previous.goal)
        val desiredChecked = when {
            listOf("enable", "turn on", "activate").any { normalizedGoal.contains(it) } -> true
            listOf("disable", "turn off", "deactivate").any { normalizedGoal.contains(it) } -> false
            else -> null
        }
        val toggle = oldElement?.className?.contains("Switch", ignoreCase = true) == true
        if (desiredChecked != null && toggle && newMatching?.checked == desiredChecked &&
            oldElement.checked != desiredChecked) {
            return previous.copy(
                instruction = "The accessible switch state matches your requested action.",
                status = "Verified through the switch's accessibility state.",
                observed = observation, phase = GuidancePhase.COMPLETED
            )
        }
        val next = choose(previous.goal, observation)
        if (next == null) return previous.copy(
            instruction = "The screen changed, but I cannot safely confirm the next step or that the goal is complete.",
            status = "Review this screen and try Check my screen again.",
            targetIndex = null, targetLabel = null, observed = observation,
            phase = GuidancePhase.NEEDS_REVIEW
        )
        val same = normalize(next.title) == normalize(previous.targetLabel ?: "")
        return previous.copy(
            step = previous.step + if (same) 0 else 1,
            instruction = "Find and tap “${next.title}”.",
            status = if (same) "This control is still visible. Check whether you performed the action."
                else "The screen changed. This is the next accessible control.",
            targetIndex = next.index, targetLabel = next.title,
            observed = observation, phase = GuidancePhase.NEEDS_ACTION
        )
    }

    fun cancel(guide: Guidance) = guide.copy(
        status = "Guidance cancelled.", phase = GuidancePhase.CANCELLED
    )

    private fun choose(query: String, observation: ScreenObservation): VisibleItem? {
        val items = visibleItems(observation)
        val q = normalize(query)
        val targets = when {
            q.contains("dark") -> listOf("dark theme", "dark mode")
            q.contains("wifi") || q.contains("wi-fi") -> listOf("wifi", "wi-fi")
            q.contains("font") || q.contains("text size") -> listOf("font size", "display size & text")
            q.contains("notification") -> listOf("notifications")
            q.contains("privacy") -> listOf("privacy")
            else -> items.map { normalize(it.title) }.filter { q.contains(it) }
        }
        val direct = items.filter { normalize(it.title) in targets }.distinctBy { it.index }
        if (direct.size == 1) return direct.single()
        if (direct.isNotEmpty()) return null
        val routes = when {
            q.contains("dark") || q.contains("font") -> listOf("display & touch", "display")
            q.contains("wifi") || q.contains("wi-fi") -> listOf("network & internet", "network")
            else -> emptyList()
        }
        return items.filter { normalize(it.title) in routes }.singleOrNull()
    }

    private fun label(element: AccessibleUiElement): String? =
        (element.text ?: element.contentDescription)?.trim()?.takeIf { it.isNotEmpty() }

    private fun describe(label: String): String = when (normalize(label)) {
        "display", "display & touch" -> "Screen appearance, brightness and text settings"
        "notifications" -> "Control which alerts are shown"
        "privacy" -> "View privacy-related settings"
        "network & internet", "network" -> "Network and connectivity controls"
        "apps" -> "Manage installed applications"
        else -> "Accessible control named “$label”"
    }

    private fun friendlyApp(packageName: String) =
        if (packageName == "com.android.settings") "Android Settings"
        else packageName.substringAfterLast('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
