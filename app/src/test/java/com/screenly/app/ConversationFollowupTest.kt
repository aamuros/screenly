package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class ConversationFollowupTest {
    private fun screen() = ScreenObservation(
        "com.android.settings", 1, listOf(
            AccessibleUiElement("Wi-Fi", null, "Button", null, true, true, false, false, 0, 0, 10, 10)
        )
    )

    @Test fun followupCarriesPreviousTurnsAndCurrentQuestion() {
        val history = listOf(
            AssistantChatEntry(true, "How do I find Wi-Fi?"),
            AssistantChatEntry(false, "Open Network settings.")
        )
        val prompt = OnDevicePrompts.ask("What should I press next?", screen(), history)
        assertTrue(prompt.contains("How do I find Wi-Fi?"))
        assertTrue(prompt.contains("Open Network settings."))
        assertTrue(prompt.contains("Current question: What should I press next?"))
        assertTrue(prompt.contains("[0] Wi-Fi"))
    }

    @Test fun enormousHistoryCannotDisplaceCurrentQuestion() {
        val history = List(30) { AssistantChatEntry(false, "z".repeat(3000)) }
        val prompt = OnDevicePrompts.ask("Is it enabled?", screen(), history)
        assertTrue(prompt.length <= 1000)
        assertTrue(prompt.contains("Current question: Is it enabled?"))
    }
}
