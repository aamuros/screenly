package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CandidateNavigationTest {
    private fun fixture(id: String) = navigationFixtures.single { it.id == id }
    private suspend fun decide(id: String, answer: (String) -> String): CandidateDecision {
        val fixture = fixture(id)
        return NavigationEngine { answer(it) }.decideCandidates(
            fixture.goal, fixture.elements, fixture.candidateIndices, "com.android.settings"
        )
    }

    @Test fun candidateAnswersUseTrustedOriginalIndicesIncludingReorderedHighIndices() = runBlocking {
        val elements = List(40) { fixtureElement("Heading $it", clickable = false) }.toMutableList()
        elements[37] = fixtureElement("Font size")
        elements[19] = fixtureElement("Sound")
        val result = NavigationEngine { if (it.contains("Label: \"Font size\"")) "YES\n" else "NO" }
            .decideCandidates("How do I change my font size?", elements, listOf(19, 37), "com.android.settings")
        assertEquals(37, result.decision.elementIndex)
        assertEquals(DecisionSource.MODEL, result.decision.source)
        assertEquals(setOf(19, 37), result.evaluations.map { it.originalIndex }.toSet())
    }

    @Test fun noRelevantCandidateProducesActualModelAbstention() = runBlocking {
        val result = decide("missing-font") { "NO" }
        assertNull(result.decision.elementIndex)
        assertEquals(ModelOutcome.ABSTAINED, result.decision.modelOutcome)
        assertEquals(DecisionSource.NONE, result.decision.source)
    }

    @Test fun falseSemanticMatchesCannotSelectBatteryOrUnavailableTarget() = runBlocking {
        for (id in listOf("missing-font", "wifi-unavailable", "offscreen-target", "brightness-missing")) {
            val result = decide(id) { if (it.contains("Label: \"Battery\"")) "NO" else "YES" }
            assertNull(id, result.decision.elementIndex)
        }
    }

    @Test fun duplicatesAndSatisfiedTogglesAbstainEvenWhenModelSaysYes() = runBlocking {
        for (id in listOf("ambiguous", "ambiguous-with-descriptions", "dark-on", "hotspot-on", "already-disabled")) {
            assertNull(id, decide(id) { "YES" }.decision.elementIndex)
        }
    }

    @Test fun malformedOrIncorrectAnswersHaveSeparateRuleProvenance() = runBlocking {
        for (answer in listOf("TAP:1", "Yes", "YES because it matches", "NO")) {
            val result = decide("display-font") { answer }
            assertEquals(1, result.decision.elementIndex)
            assertEquals(DecisionSource.RULE, result.decision.source)
            assertNull(result.decision.modelIndex)
            assertTrue(result.decision.fallbackAttempted)
        }
        val wrong = decide("display-font") { if (it.contains("Label: \"Display size\"")) "YES" else "NO" }
        assertEquals(NavigationRejection.CONTRADICTS_EXACT_TARGET, wrong.decision.rejection)
        assertEquals(DecisionSource.RULE, wrong.decision.source)
    }

    @Test fun unknownIntermediateMenusDoNotInheritLegacyRouteGuesses() = runBlocking {
        val result = decide("settings-font") { "YES" }
        assertNull(result.decision.elementIndex)
        val elements = listOf(fixtureElement("Display", description = "Dark theme, font size, touch"), fixtureElement("Sound"))
        val routes = SettingsWorkflow.verifiedRoutes("Open font size", "com.android.settings", true, elements, listOf(0, 1))
        assertEquals(setOf(0), routes)
        val supported = NavigationEngine { if (it.contains("Label: \"Display\"")) "YES" else "NO" }
            .decideCandidates("Open font size", elements, listOf(0, 1), "com.android.settings", routes)
        assertEquals(0, supported.decision.elementIndex)
        assertTrue(SettingsWorkflow.verifiedRoutes("Open font size", "other.app", true, elements, listOf(0, 1)).isEmpty())
        assertTrue(SettingsWorkflow.verifiedRoutes("Open font size", "com.android.settings", false, elements, listOf(0, 1)).isEmpty())
        assertTrue(SettingsWorkflow.verifiedRoutes("Open font size", "com.android.settings", true,
            listOf(fixtureElement("Display")), listOf(0)).isEmpty())
    }

    @Test fun knownRoutesRemainAvailableWhenLocalModelIsMissing() = runBlocking {
        val elements = listOf(fixtureElement("Display & touch", description = "Dark theme, font size, touch"))
        val routes = SettingsWorkflow.verifiedRoutes("Open font size", "com.android.settings", true, elements, listOf(0))
        val result = NavigationEngine { throw LocalInferenceException("missing", IOException()) }
            .decideCandidates("Open font size", elements, listOf(0), "com.android.settings", routes)
        assertEquals(0, result.decision.elementIndex)
        assertEquals(DecisionSource.RULE, result.decision.source)
        assertEquals(ModelOutcome.FAILED, result.decision.modelOutcome)
    }

    @Test fun promptCarriesStateAndContextWithoutCoordinatesAndUsesStrictReplies() {
        val prompt = CandidateProtocol.prompt("Enable Wi-Fi", "com.android.settings", 42,
            fixtureElement("Wi-Fi", kind = "Switch", checked = true), listOf("Internet"), listOf("Network & internet"), false)!!
        assertTrue(prompt.contains("state: ON"))
        assertTrue(prompt.contains("original ID: 42"))
        assertTrue(prompt.contains("com.android.settings"))
        assertFalse(prompt.contains("1080"))
        for (raw in listOf("Yes", "none", "TAP:42", "YES\nNO", "\"YES\"", " ".repeat(33))) assertNull(CandidateProtocol.parse(raw))
    }

    @Test fun openingSettingsCannotFlipAnAlreadyVisibleToggle() = runBlocking {
        val elements = listOf(fixtureElement("Wi-Fi", kind = "Switch", checked = true))
        val result = NavigationEngine { "YES" }.decideCandidates("Open Wi-Fi settings", elements, listOf(0), "com.android.settings")
        assertNull(result.decision.elementIndex)
        assertEquals(NavigationRejection.UNSAFE_TOGGLE, result.decision.rejection)
    }

    @Test fun invalidInputSkipsAllNativeWorkAndCancellationPropagates() = runBlocking {
        val fixture = fixture("display-font")
        val rejected = NavigationEngine { error("Called for invalid input") }
            .decideCandidates("", fixture.elements, fixture.candidateIndices, "com.android.settings")
        assertEquals(ModelOutcome.INPUT_REJECTED, rejected.decision.modelOutcome)
        assertFalse(rejected.decision.fallbackAttempted)
        try {
            NavigationEngine { throw CancellationException("cancelled") }
                .decideCandidates(fixture.goal, fixture.elements, fixture.candidateIndices, "com.android.settings")
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }
}
