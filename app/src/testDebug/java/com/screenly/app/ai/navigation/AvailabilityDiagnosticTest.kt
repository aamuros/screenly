package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityDiagnosticTest {
    private val fixture = navigationFixtures.first { it.id == "display-font" }

    @Test
    fun noSkipsSelectionAndPreservesRawAnswer() = runBlocking {
        var calls = 0
        val result = runAvailabilityDiagnostic(fixture) { calls++; "NO\n" }
        assertEquals(1, calls)
        assertEquals(NavigationLabOutcome.ABSTAINED, result.outcome)
        assertEquals("NO\n", result.rawAvailability)
        assertNull(result.selection)
    }

    @Test
    fun invalidAvailabilityCannotAuthorizeSelection() = runBlocking {
        for (raw in listOf("Yes", "YES NO", "YES because it exists", "", "YES" + " ".repeat(32))) {
            var calls = 0
            val result = runAvailabilityDiagnostic(fixture) { calls++; raw }
            assertEquals(1, calls)
            assertEquals(NavigationLabOutcome.INVALID, result.outcome)
            assertEquals(raw, result.rawAvailability)
            assertNull(result.selection)
        }
    }

    @Test
    fun yesDoesNotReplaceWrongSelectionWithRuleAnswer() = runBlocking {
        val answers = ArrayDeque(listOf("YES", "TAP:2"))
        val result = runAvailabilityDiagnostic(fixture) { answers.removeFirst() }
        assertEquals(NavigationLabOutcome.SELECTED, result.outcome)
        assertEquals(2, result.selection?.selectedIndex)
        assertEquals(1, result.selection?.ruleIndex)
        assertEquals("TAP:2", result.selection?.response)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun yesRetainsInvalidSelectionInsteadOfRepairingIt() = runBlocking {
        val answers = ArrayDeque(listOf("YES", "TAP:99"))
        val result = runAvailabilityDiagnostic(fixture) { answers.removeFirst() }
        assertEquals(NavigationLabOutcome.INVALID, result.outcome)
        assertNull(result.selection?.selectedIndex)
        assertEquals("TAP:99", result.selection?.response)
    }

    @Test
    fun failureIsRecordedWithoutRuleSubstitution() = runBlocking {
        val result = runAvailabilityDiagnostic(fixture) {
            throw LocalInferenceException("fixture failure", IllegalStateException("simulated native failure"))
        }
        assertEquals(NavigationLabOutcome.FAILED, result.outcome)
        assertEquals("fixture failure", result.failure)
        assertNull(result.selection)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() = runBlocking<Unit> {
        runAvailabilityDiagnostic(fixture) { throw CancellationException("cancelled") }
    }

    @Test
    fun rejectedInputNeverCallsModel() = runBlocking {
        val blocked = navigationFixtures.first { it.id == "blocked-targets" }
        val result = runAvailabilityDiagnostic(blocked) { error("Generation must be skipped") }
        assertEquals(NavigationLabOutcome.INPUT_REJECTED, result.outcome)
    }

    @Test
    fun availabilityAndSelectionReceiveIdenticalCandidateData() {
        val baseline = NavigationProtocol.buildPrompt(fixture.goal, fixture.elements, fixture.candidateIndices)!!
        assertEquals(baseline.substringAfter('\n'), availabilityPrompt(fixture)!!.substringAfter('\n'))
    }
}
