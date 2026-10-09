package com.screenly.app

import android.content.Context

/**
 * Session owner, independent from the short-lived floating panel.
 * Only an explicitly started goal and numerical step are saved across process death.
 * Neither screenshots nor extracted UI text nor chat messages are written to storage.
 */
internal class ScreenlySessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("screenly_guidance_v1", Context.MODE_PRIVATE)
    val messages = mutableListOf<AssistantChatEntry>()
    var guidance: AccessibleScreenAssistant.Guidance? = null
        private set

    var savedGoal: String? = prefs.getString("goal", null)?.takeIf { it.isNotBlank() }
        private set
    var savedStep: Int = prefs.getInt("step", 1).coerceIn(1, 100)
        private set

    fun addMessage(entry: AssistantChatEntry) {
        messages.add(entry.copy(content = entry.content.take(500)))
        if (messages.size > 24) messages.subList(0, messages.size - 24).clear()
    }

    fun updateGuidance(value: AccessibleScreenAssistant.Guidance?) {
        guidance = value
        if (value == null || value.phase == AccessibleScreenAssistant.GuidancePhase.CANCELLED ||
            value.phase == AccessibleScreenAssistant.GuidancePhase.COMPLETED) {
            savedGoal = null
            savedStep = 1
            prefs.edit().remove("goal").remove("step").apply()
        } else {
            savedGoal = value.goal.take(160)
            savedStep = value.step.coerceIn(1, 100)
            prefs.edit().putString("goal", savedGoal).putInt("step", savedStep).apply()
        }
    }

    fun resumeFrom(observation: ScreenObservation) {
        if (guidance != null) return
        val goal = savedGoal ?: return
        val fresh = AccessibleScreenAssistant.begin(goal, observation)
        updateGuidance(fresh.copy(
            step = savedStep,
            status = "Restored your goal. The current screen was checked again; " +
                "previous highlights were not reused."
        ))
    }

    fun clearChat() { messages.clear() }
    fun clearGuidance() { updateGuidance(null) }
}
