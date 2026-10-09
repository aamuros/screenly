package com.screenly.app.ai.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationRulesTest {
    @Test fun ruleSelectorIsAlwaysUnavailable() {
        navigationFixtures.forEach { assertNull(it.id, NavigationRules.select(it.goal, it.elements, it.candidateIndices)) }
    }
    @Test fun validationChecksOnlyStructuralMembership() {
        val elements = listOf(fixtureElement("Unfamiliar menu"), fixtureElement("Disabled", enabled = false))
        assertNull(NavigationRules.rejection("Upload a photo", 0, elements, listOf(0)))
        assertEquals(NavigationRejection.TARGET_NOT_ALLOWED, NavigationRules.rejection("Upload", 1, elements, listOf(0)))
        assertEquals(NavigationRejection.INVALID_INPUT, NavigationRules.rejection("Upload", 0, elements, listOf(0, 0)))
    }
    @Test fun settingsHintsAndGoalRewritesAreRemoved() {
        val goal = "Make my phone text bigger"
        assertEquals(goal, SettingsWorkflow.canonicalGoal(goal))
        assertTrue(SettingsWorkflow.verifiedRoutes(goal, "com.android.settings", true, listOf(fixtureElement("Display")), listOf(0), 37).isEmpty())
    }
}
