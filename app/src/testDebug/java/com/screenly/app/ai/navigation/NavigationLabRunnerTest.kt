package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NavigationLabRunnerTest {
    private val fixture = navigationFixtures.first { it.id == "display-font" }

    @Test
    fun validModelSelectionAndRuleBaselineAreRecordedSeparately() = runBlocking<Unit> {
        val result = runNavigationLab(fixture, fixture.goal) { "TAP:2" }
        assertEquals(NavigationLabOutcome.SELECTED, result.outcome)
        assertEquals(2, result.selectedIndex)
        assertEquals(1, result.ruleIndex)
        assertEquals("TAP:2", result.response)
        assertNotNull(result.modelMillis)
    }

    @Test
    fun invalidOutputsCannotDisplayRuleSuccessAsModelSelection() = runBlocking<Unit> {
        listOf("TAP:0", "TAP:99", "TAP:1 because it matches", "None", "None\n", "1\n").forEach { raw ->
            val result = runNavigationLab(fixture, fixture.goal) { raw }
            assertEquals(NavigationLabOutcome.INVALID, result.outcome)
            assertNull(result.selectedIndex)
            assertEquals(1, result.ruleIndex)
            assertEquals(raw, result.response)
            assertEquals(
                if (raw in listOf("TAP:0", "TAP:99")) NavigationLabValidationError.TARGET_NOT_ALLOWED
                else NavigationLabValidationError.INVALID_SYNTAX,
                result.validationError
            )
            assertEquals(
                NavigationProtocol.buildPrompt(fixture.goal, fixture.elements, fixture.candidateIndices), result.prompt
            )
        }
    }

    @Test
    fun abstentionDoesNotBecomeRuleSelectionOrCompletion() = runBlocking<Unit> {
        val result = runNavigationLab(fixture, fixture.goal) { "NONE" }
        assertEquals(NavigationLabOutcome.ABSTAINED, result.outcome)
        assertNull(result.selectedIndex)
        assertEquals(1, result.ruleIndex)
    }

    @Test
    fun rejectedInputSkipsInferenceButStillShowsIndependentRules() = runBlocking<Unit> {
        val overflow = navigationFixtures.first { it.id == "too-many-candidates" }
        val result = runNavigationLab(overflow, overflow.goal) { error("Inference must not run.") }
        assertEquals(NavigationLabOutcome.INPUT_REJECTED, result.outcome)
        assertNull(result.response)
        assertNull(result.modelMillis)
        assertEquals(0, result.ruleIndex)
    }

    @Test
    fun runtimeFailureRemainsVisibleDespiteMatchingRule() = runBlocking<Unit> {
        val result = runNavigationLab(fixture, fixture.goal) {
            throw LocalInferenceException("Missing model", IOException("not provisioned"))
        }
        assertEquals(NavigationLabOutcome.FAILED, result.outcome)
        assertNull(result.selectedIndex)
        assertEquals(1, result.ruleIndex)
        assertEquals("Missing model", result.failure)
        assertTrue(result.modelMillis!! >= 0)
    }

    @Test
    fun cancellationPropagatesRatherThanReturningAFallbackReport() {
        assertThrows(CancellationException::class.java) {
            runBlocking { runNavigationLab(fixture, fixture.goal) { throw CancellationException("Activity closed") } }
        }
    }
}
