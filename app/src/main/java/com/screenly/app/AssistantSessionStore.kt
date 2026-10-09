package com.screenly.app

import java.io.File
import java.io.IOException
import java.util.Properties

internal data class AssistantGuideStep(val step: Int, val instruction: String)

/**
 * App-private, bounded, text-only session. No screenshots or screen coordinates are persisted.
 * Snapshot-derived targets are invalid after service restart and always require a fresh check.
 */
internal class AssistantSessionStore(private val file: File) {
    val messages = mutableListOf<AssistantChatEntry>()
    val guideSteps = mutableListOf<AssistantGuideStep>()
    var captureStatus: String = "" // Transient diagnostic. Never written to disk.

    private data class SavedGuide(
        val goal: String, val step: Int, val instruction: String, val targetLabel: String?
    )
    private var waitingForObservation: SavedGuide? = null
    private var needsResumeCheck = false

    var guidance: AccessibleScreenAssistant.Guidance? = null
        set(next) {
            field = next
            needsResumeCheck = false
            if (next != null && guideSteps.lastOrNull()?.instruction != next.instruction) {
                guideSteps += AssistantGuideStep(next.step, next.instruction)
                if (guideSteps.size > MAX_STEPS) guideSteps.removeAt(0)
            }
            save()
        }

    var explanation: String? = null
        set(value) {
            field = value
            save()
        }

    init {
        load()
    }

    /** Reattach saved text to a fresh snapshot, never to persisted indices/bounds. */
    fun onObservation(snapshot: ScreenObservation) {
        val saved = waitingForObservation ?: return
        waitingForObservation = null
        guidance = AccessibleScreenAssistant.Guidance(
            saved.goal, saved.step, saved.instruction,
            "Previous step restored. Tap Check my screen to inspect the current app.",
            null, saved.targetLabel, snapshot,
            AccessibleScreenAssistant.GuidancePhase.NEEDS_REVIEW
        )
        needsResumeCheck = true
    }

    /** A saved guide must re-plan instead of comparing against a fabricated old screen. */
    fun previousForCheck(): AccessibleScreenAssistant.Guidance? =
        guidance.takeUnless { needsResumeCheck }

    fun rebaseRestoredStep(result: AccessibleScreenAssistant.Guidance): AccessibleScreenAssistant.Guidance {
        if (!needsResumeCheck) return result
        val previous = guidance ?: return result
        val moved = result.targetLabel != null &&
            !result.targetLabel.equals(previous.targetLabel, ignoreCase = true)
        return result.copy(
            step = previous.step + if (moved) 1 else 0,
            status = result.status + " Previous steps are saved."
        )
    }

    fun startNewGuide() {
        waitingForObservation = null
        needsResumeCheck = false
        guideSteps.clear()
        guidance = null
    }

    fun clearHistory() {
        waitingForObservation = null
        needsResumeCheck = false
        messages.clear()
        guideSteps.clear()
        guidance = null
        explanation = null
        save()
    }

    fun save() {
        val data = Properties()
        data.setProperty("version", "1")
        val recentMessages = messages.takeLast(MAX_MESSAGES)
        recentMessages.forEachIndexed { index, entry ->
            data.setProperty("chat." + index + ".role", if (entry.fromUser) "user" else "assistant")
            data.setProperty("chat." + index + ".text", entry.content.take(MAX_TEXT))
        }
        data.setProperty("chat.count", recentMessages.size.toString())
        val recentSteps = guideSteps.takeLast(MAX_STEPS)
        recentSteps.forEachIndexed { index, entry ->
            data.setProperty("step." + index + ".number", entry.step.toString())
            data.setProperty("step." + index + ".text", entry.instruction.take(MAX_TEXT))
        }
        data.setProperty("step.count", recentSteps.size.toString())
        val active = guidance
        if (active != null) {
            data.setProperty("guide.goal", active.goal.take(160))
            data.setProperty("guide.step", active.step.toString())
            data.setProperty("guide.instruction", active.instruction.take(MAX_TEXT))
            active.targetLabel?.let { data.setProperty("guide.targetLabel", it.take(160)) }
        } else if (waitingForObservation != null) {
            val pending = waitingForObservation!!
            data.setProperty("guide.goal", pending.goal.take(160))
            data.setProperty("guide.step", pending.step.toString())
            data.setProperty("guide.instruction", pending.instruction.take(MAX_TEXT))
            pending.targetLabel?.let { data.setProperty("guide.targetLabel", it.take(160)) }
        }
        explanation?.let { data.setProperty("explanation", it.take(MAX_TEXT)) }
        val directory = file.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val temporary = File(directory, file.name + ".tmp")
        try {
            temporary.outputStream().buffered().use { data.store(it, "Screenly offline assistant history") }
            // Same-directory rename is atomic on Android's app-private Linux filesystem.
            if (!temporary.renameTo(file)) throw IOException("Unable to replace assistant session")
        } catch (_: IOException) {
            // Keep in-memory history if storage is unavailable; do not crash the overlay.
        } finally {
            temporary.delete()
        }
    }

    private fun load() {
        if (!file.isFile || file.length() > MAX_BYTES) return
        val data = Properties()
        try {
            file.inputStream().buffered().use(data::load)
        } catch (_: IOException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        if (data.getProperty("version") != "1") return
        val count = data.getProperty("chat.count")?.toIntOrNull()?.coerceIn(0, MAX_MESSAGES) ?: 0
        repeat(count) { index ->
            val content = data.getProperty("chat." + index + ".text") ?: return@repeat
            val role = data.getProperty("chat." + index + ".role")
            if (role == "user" || role == "assistant") {
                messages += AssistantChatEntry(role == "user", content.take(MAX_TEXT))
            }
        }
        val steps = data.getProperty("step.count")?.toIntOrNull()?.coerceIn(0, MAX_STEPS) ?: 0
        repeat(steps) { index ->
            val number = data.getProperty("step." + index + ".number")?.toIntOrNull() ?: return@repeat
            val instruction = data.getProperty("step." + index + ".text") ?: return@repeat
            if (number > 0) guideSteps += AssistantGuideStep(number, instruction.take(MAX_TEXT))
        }
        val goal = data.getProperty("guide.goal")?.take(160)
        val instruction = data.getProperty("guide.instruction")?.take(MAX_TEXT)
        val step = data.getProperty("guide.step")?.toIntOrNull()
        if (!goal.isNullOrBlank() && !instruction.isNullOrBlank() && step != null && step > 0) {
            waitingForObservation = SavedGuide(
                goal, step, instruction, data.getProperty("guide.targetLabel")?.take(160)
            )
        }
        // Assign the backing field in init without persisting while loading.
        explanation = data.getProperty("explanation")?.take(MAX_TEXT)
    }

    private companion object {
        const val MAX_MESSAGES = 24
        const val MAX_STEPS = 12
        const val MAX_TEXT = 1000
        const val MAX_BYTES = 64 * 1024
    }
}
