package com.screenly.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantSessionStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun screen(label: String = "Wi-Fi") = ScreenObservation(
        "com.android.settings", 8, listOf(
            AccessibleUiElement(label, null, "android.widget.Button", null,
                true, true, false, false, 12, 24, 100, 75)
        )
    )

    @Test fun instructionsAndChatSurviveNewSessionWithoutStaleCoordinates() {
        val file = temp.newFile("assistant-session.properties")
        val original = AssistantSessionStore(file)
        val first = screen()
        original.messages += AssistantChatEntry(true, "How do I enable Wi-Fi?")
        original.messages += AssistantChatEntry(false, "Tap Wi-Fi")
        original.save()
        original.guidance = AccessibleScreenAssistant.Guidance(
            "Enable Wi-Fi", 2, "Tap Wi-Fi",
            "Waiting for your next action", 0, "Wi-Fi", first
        )
        // Simulate service recreation and a different target screen.
        val reloaded = AssistantSessionStore(file)
        assertEquals(2, reloaded.messages.size)
        assertEquals("Tap Wi-Fi", reloaded.messages.last().content)
        assertNull(reloaded.guidance)
        assertEquals(1, reloaded.guideSteps.size)

        reloaded.onObservation(screen("Networks"))
        val saved = reloaded.guidance!!
        assertEquals(2, saved.step)
        assertEquals("Enable Wi-Fi", saved.goal)
        assertNull("Never resurrect an old coordinate/index", saved.targetIndex)
        assertEquals(AccessibleScreenAssistant.GuidancePhase.NEEDS_REVIEW, saved.phase)
        assertNull("Restored guide must freshly plan", reloaded.previousForCheck())
        val candidate = AccessibleScreenAssistant.Guidance(
            saved.goal, 1, "Tap Networks", "", 0, "Networks", screen("Networks")
        )
        val advanced = reloaded.rebaseRestoredStep(candidate)
        assertEquals(3, advanced.step)
        reloaded.guidance = advanced
        assertNotNull(reloaded.previousForCheck())
        assertEquals(2, reloaded.guideSteps.size)
    }

    @Test fun sameControlDoesNotIncrementStepAfterRestore() {
        val file = temp.newFile("assistant-session.properties")
        val original = AssistantSessionStore(file)
        original.guidance = AccessibleScreenAssistant.Guidance(
            "Open Wi-Fi", 3, "Tap Wi-Fi", "", 0, "Wi-Fi", screen()
        )
        val restored = AssistantSessionStore(file)
        restored.onObservation(screen())
        val proposed = AccessibleScreenAssistant.begin("Open Wi-Fi", screen())
        assertEquals(3, restored.rebaseRestoredStep(proposed).step)
    }

    @Test fun clearHistoryErasesPersistedChatAndGuide() {
        val file = temp.newFile("assistant-session.properties")
        val store = AssistantSessionStore(file)
        store.messages += AssistantChatEntry(true, "private message")
        store.guidance = AccessibleScreenAssistant.begin("Open Wi-Fi", screen())
        store.explanation = "saved explanation"
        store.save()
        store.clearHistory()
        val reloaded = AssistantSessionStore(file)
        reloaded.onObservation(screen())
        assertTrue(reloaded.messages.isEmpty())
        assertTrue(reloaded.guideSteps.isEmpty())
        assertNull(reloaded.guidance)
        assertNull(reloaded.explanation)
    }

    @Test fun invalidAndOversizedSessionFilesFailSafely() {
        val file = temp.newFile("assistant-session.properties")
        file.writeText("not_a_valid_properties_version=1")
        assertTrue(AssistantSessionStore(file).messages.isEmpty())
        file.writeBytes(ByteArray(65 * 1024))
        assertNull(AssistantSessionStore(file).guidance)
    }

    @Test fun smallSnapshotsCanBeClearedWithoutResettingConversation() {
        val file = temp.newFile("assistant-session.properties")
        val store = AssistantSessionStore(file)
        val state = ScreenObservationState()
        state.update(screen())
        store.guidance = AccessibleScreenAssistant.begin("Open Wi-Fi", screen())
        store.messages += AssistantChatEntry(true, "next?")
        store.save()
        state.clear()
        assertNull(state.snapshot)
        assertEquals("Open Wi-Fi", store.guidance?.goal)
        assertEquals("next?", store.messages.single().content)
    }
}
