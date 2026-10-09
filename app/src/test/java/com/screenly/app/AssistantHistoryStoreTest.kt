package com.screenly.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantHistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun retainsConversationsAndStepsButNeverCoordinates() {
        val file = folder.newFile("history.properties")
        val history = AssistantHistoryStore(file)
        history.rememberQuestion("How to call somebody? Answer: open Phone")
        history.rememberGoal("Change font size")
        history.rememberGuideStep("Tap Display & touch")
        history.rememberGuideStep("Tap Font size")
        val restored = AssistantHistoryStore(file)
        assertEquals(history.messages, restored.messages)
        assertEquals("Change font size", restored.goal)
        assertEquals(2, restored.guideSteps.size)
        val stored = file.readText()
        assertFalse(stored.contains("windowId"))
        assertFalse(stored.contains("bounds"))
        assertFalse(stored.contains("image"))
    }

    @Test fun clearingHistoryRemovesEverythingAcrossRestart() {
        val file = folder.newFile("history.properties")
        val first = AssistantHistoryStore(file)
        first.rememberQuestion("Hello")
        first.rememberGoal("Open Settings")
        first.rememberGuideStep("Tap Settings")
        first.clearAll()
        val again = AssistantHistoryStore(file)
        assertTrue(again.messages.isEmpty())
        assertTrue(again.guideSteps.isEmpty())
        assertEquals("", again.goal)
    }

    @Test fun boundsHistoryAndAvoidsDuplicatingCurrentStep() {
        val file = folder.newFile("history.properties")
        val session = AssistantHistoryStore(file)
        repeat(20) { session.rememberQuestion("question $it") }
        repeat(14) { session.rememberGuideStep("step $it") }
        session.rememberGuideStep("step 13")
        assertEquals(4, session.messages.size)
        assertEquals(8, session.guideSteps.size)
        assertEquals(8, AssistantHistoryStore(file).guideSteps.size)
    }
}
