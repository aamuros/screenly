package com.screenly.app.ai.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRulesTest {
    @Test
    fun conservativeBaselineMatchesFixtureExpectations() {
        navigationFixtures.forEach { fixture ->
            assertEquals(
                fixture.id, fixture.expectedRuleIndex,
                NavigationRules.select(fixture.goal, fixture.elements, fixture.candidateIndices)
            )
        }
    }

    @Test
    fun exactMatchingNormalizesCaseAndWhitespace() {
        assertEquals(1, NavigationRules.select(
            "  OPEN   Font SIZE  ", listOf(fixtureElement("Display size"), fixtureElement(" FONT   size ")), listOf(0, 1)
        ))
    }

    @Test
    fun noSubstringRankingOrFirstMatchTieBreak() {
        assertNull(NavigationRules.select("Open font", listOf(fixtureElement("Font size")), listOf(0)))
        assertNull(NavigationRules.select("Open font size", listOf(fixtureElement("Font size"), fixtureElement("FONT SIZE")), listOf(0, 1)))
    }

    @Test
    fun unknownCheckedStateCannotAuthorizeToggle() {
        assertNull(NavigationRules.select("Enable Wi-Fi", listOf(fixtureElement("Wi-Fi", kind = "TextView")), listOf(0)))
        assertNull(NavigationRules.select("Enable Wi-Fi", listOf(fixtureElement("Wi-Fi", kind = "Button")), listOf(0)))
        assertNull(NavigationRules.select("Disable Wi-Fi", listOf(fixtureElement("Wi-Fi", kind = "Switch")), listOf(0)))
        assertEquals(0, NavigationRules.select("Disable Wi-Fi", listOf(fixtureElement("Wi-Fi", kind = "Switch", checked = true)), listOf(0)))
    }

    @Test
    fun ruleFallbackCanUseFullSetWhenPromptBudgetRejectsIt() {
        val fixture = navigationFixtures.first { it.id == "too-many-candidates" }
        assertNull(NavigationProtocol.buildPrompt(fixture.goal, fixture.elements, fixture.candidateIndices))
        assertEquals(0, NavigationRules.select(fixture.goal, fixture.elements, fixture.candidateIndices))
    }

    @Test
    fun invalidCandidateSetAndNoncandidateMatchAreRejected() {
        val elements = listOf(fixtureElement("Font size"), fixtureElement("Sound"))
        assertNull(NavigationRules.select("Open font size", elements, listOf(1)))
        assertNull(NavigationRules.select("Open font size", elements, listOf(0, 0)))
        assertNull(NavigationRules.select("Open font size", elements, listOf(0, 99)))
    }

    @Test
    fun modelCannotBypassAmbiguityOrToggleSafeguards() {
        listOf("ambiguous", "ambiguous-with-descriptions").forEach { id ->
            val fixture = navigationFixtures.first { it.id == id }
            fixture.candidateIndices.forEach { index ->
                assertEquals(NavigationRejection.AMBIGUOUS_TARGET,
                    NavigationRules.rejection(fixture.goal, index, fixture.elements, fixture.candidateIndices))
            }
        }
        listOf("dark-on", "hotspot-on", "already-disabled", "wifi-menu").forEach { id ->
            val fixture = navigationFixtures.first { it.id == id }
            assertEquals(NavigationRejection.UNSAFE_TOGGLE,
                NavigationRules.rejection(fixture.goal, fixture.candidateIndices.first(), fixture.elements, fixture.candidateIndices))
        }
    }

    @Test
    fun descriptionDisambiguatesOnlyWhenTheGoalIdentifiesIt() {
        val fixture = navigationFixtures.first { it.id == "text-and-description" }
        assertNull(NavigationRules.rejection(fixture.goal, 1, fixture.elements, fixture.candidateIndices))
        assertEquals(NavigationRejection.AMBIGUOUS_TARGET,
            NavigationRules.rejection("Continue", 1, fixture.elements, fixture.candidateIndices))
    }

    @Test
    fun unsupportedAndUnavailableTargetsCannotBecomeUnrelatedSelections() {
        listOf("missing-font", "wifi-unavailable", "offscreen-target").forEach { id ->
            val fixture = navigationFixtures.first { it.id == id }
            fixture.candidateIndices.forEach { index ->
                org.junit.Assert.assertNotNull(id,
                    NavigationRules.rejection(fixture.goal, index, fixture.elements, fixture.candidateIndices))
            }
        }
        org.junit.Assert.assertNotNull(NavigationRules.rejection("Open brightness", 0, listOf(fixtureElement("Sound")), listOf(0)))
    }

    @Test
    fun knownMenuRoutesRequireOneUniqueCandidateAndDoNotBecomeRuleSelections() {
        listOf("settings-font", "settings-dark", "wifi-entry", "hotspot-entry", "network-wifi").forEach { id ->
            val fixture = navigationFixtures.first { it.id == id }
            assertNull(id, NavigationRules.rejection(fixture.goal, fixture.expectedIndex!!, fixture.elements, fixture.candidateIndices))
            assertNull(id, NavigationRules.select(fixture.goal, fixture.elements, fixture.candidateIndices))
        }
        assertEquals(NavigationRejection.AMBIGUOUS_TARGET,
            NavigationRules.rejection("Change font size", 0,
                listOf(fixtureElement("Display"), fixtureElement("Display & brightness")), listOf(0, 1)))
    }
}
