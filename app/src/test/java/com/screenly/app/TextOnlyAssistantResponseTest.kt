package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class TextOnlyAssistantResponseTest {
    private val emptyScreen = ScreenObservation("unavailable", -1, emptyList())

    @Test fun callSomeoneAnswersWithoutScreenshotOrModel() {
        val response = AccessibleScreenAssistant.ask("I want to call somebody", emptyScreen)
        assertTrue(response.contains("Phone app"))
        assertTrue(response.contains("Contacts"))
        assertTrue(response.contains("call button"))
        assertFalse(response.contains("Screen capture failed"))
    }

    @Test fun composingEmailAnswersWithoutScreenshotOrModel() {
        val response = AccessibleScreenAssistant.ask("How to make an email", emptyScreen)
        assertTrue(response.contains("Gmail"))
        assertTrue(response.contains("Compose"))
        assertTrue(response.contains("tap Send"))
        assertTrue(response.contains("email account"))
    }

    @Test fun missingScreenStillGetsExplicitOfflineResponse() {
        val response = AccessibleScreenAssistant.ask("How do I do something else?", emptyScreen)
        assertTrue(response.contains("import the local AI model"))
        assertFalse(response.isBlank())
    }

    @Test fun aKnownVisibleControlStillUsesScreenGroundedGuidance() {
        val screen = ScreenObservation("com.android.settings", 8, listOf(
            AccessibleUiElement("Display", null, "android.widget.Button", null,
                true, true, false, false, 0, 0, 100, 100)
        ))
        val result = AccessibleScreenAssistant.ask("Enable dark mode", screen)
        assertTrue(result.contains("Display"))
        assertTrue(result.contains("not visually analyzed"))
    }
}
