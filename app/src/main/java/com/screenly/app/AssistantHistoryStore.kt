package com.screenly.app

import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Bounded, app-private, local-only conversational context. Does not store screenshot pixels,
 * accessibility hierarchy, window IDs or touch coordinates.
 */
internal class AssistantHistoryStore(private val file: File) {
    val messages = mutableListOf<String>()
    val guideSteps = mutableListOf<String>()
    var goal = ""
        private set

    init { load() }

    fun rememberQuestion(value: String) {
        messages.add(value.take(MAX_MESSAGE))
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        save()
    }

    fun rememberGoal(value: String) {
        val clean = value.take(160).trim()
        if (clean != goal) guideSteps.clear()
        goal = clean
        save()
    }

    fun rememberGuideStep(instruction: String) {
        val clean = instruction.trim().take(MAX_MESSAGE)
        if (clean.isBlank() || clean == guideSteps.lastOrNull()) return
        guideSteps.add(clean)
        while (guideSteps.size > MAX_STEPS) guideSteps.removeAt(0)
        save()
    }

    fun endGuide() {
        goal = ""
        guideSteps.clear()
        save()
    }

    fun clearAll() {
        goal = ""
        guideSteps.clear()
        messages.clear()
        save()
    }

    fun save() {
        val props = Properties()
        props["version"] = "1"
        props["goal"] = goal.take(160)
        props["messages"] = messages.size.toString()
        messages.forEachIndexed { i, msg -> props["chat.$i"] = msg.take(MAX_MESSAGE) }
        props["steps"] = guideSteps.size.toString()
        guideSteps.forEachIndexed { i, msg -> props["step.$i"] = msg.take(MAX_MESSAGE) }
        val dir = file.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(dir, file.name + ".partial")
        try {
            tmp.outputStream().use { props.store(it, "Screenly private session") }
            if (!tmp.renameTo(file)) throw IOException("Cannot save private history")
        } catch (_: IOException) {
            // Storage failures must never crash AccessibilityService; keep memory history.
        } finally { tmp.delete() }
    }

    private fun load() {
        if (!file.isFile || file.length() > 64 * 1024) return
        val props = Properties()
        try {
            file.inputStream().use(props::load)
        } catch (_: IOException) { return
        } catch (_: IllegalArgumentException) { return }
        if (props.getProperty("version") != "1") return
        goal = props.getProperty("goal", "").take(160)
        repeat(props.getProperty("messages")?.toIntOrNull()?.coerceIn(0, MAX_MESSAGES) ?: 0) { i ->
            props.getProperty("chat.$i")?.let { messages.add(it.take(MAX_MESSAGE)) }
        }
        repeat(props.getProperty("steps")?.toIntOrNull()?.coerceIn(0, MAX_STEPS) ?: 0) { i ->
            props.getProperty("step.$i")?.let { guideSteps.add(it.take(MAX_MESSAGE)) }
        }
    }

    private companion object {
        const val MAX_MESSAGES = 4
        const val MAX_STEPS = 8
        const val MAX_MESSAGE = 1000
    }
}
