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
}
