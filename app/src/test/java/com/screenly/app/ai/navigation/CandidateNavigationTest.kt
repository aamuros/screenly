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

    @Test fun boundingNativeCallsStillChecksUnseenDuplicatesAndPrioritizesHighTargetIndices() = runBlocking {
        val elements = listOf(fixtureElement("Font size"), fixtureElement("Sound"), fixtureElement("Battery"),
            fixtureElement("Storage"), fixtureElement("Font size"))
        val engine = NavigationEngine { if (it.contains("Label: \"Font size\"")) "YES" else "NO" }
        val ambiguous = engine.decideCandidates("Open font size", elements, elements.indices.toList(), "com.android.settings")
        assertEquals(CandidateProtocol.MAX_EVALUATIONS, ambiguous.evaluations.size)
        assertFalse(ambiguous.evaluations.any { it.originalIndex == 4 })
        assertNull(ambiguous.decision.elementIndex)
        assertEquals(NavigationRejection.AMBIGUOUS_TARGET, ambiguous.decision.rejection)
        val unique = engine.decideCandidates("Open font size", elements.mapIndexed { index, element ->
            if (index == 0) element.copy(text = "Wallpaper") else element
        }, elements.indices.toList(), "com.android.settings")
        assertEquals(4, unique.decision.elementIndex)
        assertEquals(4, unique.evaluations.first().originalIndex)
        assertEquals(DecisionSource.MODEL, unique.decision.source)
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

    @Test fun availabilityExperimentNfcStateFailuresRemainProtected() = runBlocking {
        for (enable in listOf(true, false)) for (checked in listOf(true, false)) {
            val elements = listOf(fixtureElement("NFC", kind = "Switch", checked = checked), fixtureElement("Bluetooth"))
            val goal = if (enable) "Enable NFC" else "Disable NFC"
            val result = NavigationEngine { if (it.contains("Label: \"NFC\"")) "YES" else "NO" }
                .decideCandidates(goal, elements, listOf(1, 0), "com.android.settings")
            assertEquals("$goal checked=$checked", if (enable != checked) 0 else null, result.decision.elementIndex)
        }
    }

    @Test fun frozenAvailabilityFailuresRejectAdversarialMatchesWithSeparateFallback() = runBlocking {
        assertEquals(16, availabilityDiagnosticFixtures.size)
        for (fixture in availabilityDiagnosticFixtures) {
            val result = NavigationEngine { "YES" }.decideCandidates(
                fixture.goal, fixture.elements, fixture.candidateIndices, "com.android.settings"
            )
            assertEquals(fixture.id, fixture.expectedIndex, result.decision.elementIndex)
            if (result.decision.source == DecisionSource.RULE) assertNull(result.decision.modelIndex)
        }
    }

    @Test fun wifiTypographyAndVerifiedInternetRouteDoNotEnableOtherDeviceGuesses() {
        assertEquals("open wi-fi", SettingsWorkflow.canonicalGoal("Find and open Wi‑Fi settings"))
        val elements = listOf(fixtureElement("Internet", description = "Connected network"))
        assertEquals(setOf(0), SettingsWorkflow.verifiedRoutes("Open Wi-Fi", "com.android.settings", true, elements, listOf(0), 37))
        assertTrue(SettingsWorkflow.verifiedRoutes("Open Wi-Fi", "com.android.settings", true, elements, listOf(0), 35).isEmpty())
        assertTrue(SettingsWorkflow.verifiedRoutes("Open Wi-Fi", "com.android.settings", false, elements, listOf(0), 37).isEmpty())
        assertTrue(SettingsWorkflow.verifiedRoutes("Open font size", "com.android.settings", true, elements, listOf(0), 37).isEmpty())
    }

    @Test fun openingSettingsAndChangingAnAdjacentToggleAreDifferentActions() = runBlocking {
        val elements = listOf(fixtureElement("Dark theme", kind = "LinearLayout", description = "Schedule"),
            fixtureElement(null, kind = "Switch", description = "Dark theme"))
        val engine = NavigationEngine { "YES" }
        val open = engine.decideCandidates("Open dark-theme settings", elements, listOf(0, 1), "com.android.settings")
        assertEquals(0, open.decision.elementIndex)
        assertEquals(DecisionSource.RULE, open.decision.source) // Model's two YES answers remain ambiguous.
        val enable = engine.decideCandidates("Enable dark mode", elements, listOf(0, 1), "com.android.settings")
        assertEquals(1, enable.decision.elementIndex)
        val alreadyOn = engine.decideCandidates("Enable dark mode", listOf(elements[0], elements[1].copy(checked = true)),
            listOf(0, 1), "com.android.settings")
        assertNull(alreadyOn.decision.elementIndex)
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
