package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NavigationEngineTest {
    private val fixture = navigationFixtures.first { it.id == "display-font" }

    @Test
    fun validModelSelectionRetainsProvenanceAndOriginalIndex() = runBlocking<Unit> {
        val result = NavigationEngine { "TAP:1" }.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
        assertEquals(1, result.elementIndex)
        assertEquals(1, result.modelIndex)
        assertEquals(DecisionSource.MODEL, result.source)
        assertEquals(ModelOutcome.SELECTED, result.modelOutcome)
        assertFalse(result.fallbackAttempted)
    }

    @Test
    fun invalidOutputAndAbstentionUseRulesWithoutBecomingModelSuccess() = runBlocking<Unit> {
        listOf("None" to ModelOutcome.INVALID_RESPONSE, "TAP:99" to ModelOutcome.REJECTED_TARGET,
            "TAP:0" to ModelOutcome.REJECTED_TARGET, "NONE" to ModelOutcome.ABSTAINED).forEach { (raw, outcome) ->
            val result = NavigationEngine { raw }.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
            assertEquals(1, result.elementIndex)
            assertNull(result.modelIndex)
            assertEquals(DecisionSource.RULE, result.source)
            assertEquals(outcome, result.modelOutcome)
            assertEquals(raw, result.rawResponse)
            assertTrue(result.fallbackAttempted)
        }
    }

    @Test
    fun wrongExactTargetCannotOverrideUniqueRuleEvidence() = runBlocking<Unit> {
        val result = NavigationEngine { "TAP:2" }.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
        assertEquals(NavigationRejection.CONTRADICTS_EXACT_TARGET, result.rejection)
        assertEquals(2, result.parsedIndex)
        assertNull(result.modelIndex)
        assertEquals(1, result.elementIndex)
        assertEquals(DecisionSource.RULE, result.source)
    }

    @Test
    fun ambiguousAndAlreadySatisfiedResponsesCannotSelect() = runBlocking<Unit> {
        listOf("ambiguous", "ambiguous-with-descriptions", "dark-on", "already-disabled").forEach { id ->
            val sample = navigationFixtures.first { it.id == id }
            val result = NavigationEngine { "TAP:0" }.decide(sample.goal, sample.elements, sample.candidateIndices)
            assertEquals(id, ModelOutcome.REJECTED_TARGET, result.modelOutcome)
            assertNull(result.elementIndex)
            assertEquals(DecisionSource.NONE, result.source)
        }
    }

    @Test
    fun inputRejectionSkipsModelAndFallback() = runBlocking<Unit> {
        val engine = NavigationEngine { error("Invalid input reached generation") }
        listOf("" to listOf(1), "x".repeat(161) to listOf(1), fixture.goal to listOf(1, 1), fixture.goal to listOf(-1)).forEach { (goal, candidates) ->
            val result = engine.decide(goal, fixture.elements, candidates)
            assertEquals(ModelOutcome.INPUT_REJECTED, result.modelOutcome)
            assertFalse(result.fallbackAttempted)
            assertNull(result.elementIndex)
            assertNull(result.generationMillis)
        }
    }

    @Test
    fun promptBudgetRejectionSkipsGenerationButAllowsFullSetRuleFallback() = runBlocking<Unit> {
        val sample = navigationFixtures.first { it.id == "too-many-candidates" }
        val result = NavigationEngine { error("Prompt rejection reached generation") }
            .decide(sample.goal, sample.elements, sample.candidateIndices)
        assertEquals(ModelOutcome.PROMPT_REJECTED, result.modelOutcome)
        assertEquals(0, result.elementIndex)
        assertEquals(DecisionSource.RULE, result.source)
        assertNull(result.rawResponse)
    }

    @Test
    fun runtimeFailureAndExhaustedFallbackAreExplicit() = runBlocking<Unit> {
        val engine = NavigationEngine { throw LocalInferenceException("private path", IOException("native failure")) }
        val result = engine.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
        assertEquals(ModelOutcome.FAILED, result.modelOutcome)
        assertEquals("LocalInferenceException", result.failure)
        assertEquals(DecisionSource.RULE, result.source)
        val missing = navigationFixtures.first { it.id == "missing-font" }
        val unable = engine.decide(missing.goal, missing.elements, missing.candidateIndices)
        assertNull(unable.elementIndex)
        assertEquals(DecisionSource.NONE, unable.source)
    }

    @Test
    fun cancellationAndProgrammingErrorsPropagate() = runBlocking<Unit> {
        listOf(CancellationException("cancelled"), IllegalStateException("bug")).forEach { failure ->
            try {
                NavigationEngine { throw failure }.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
                fail("Expected exception")
            } catch (actual: Exception) {
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
            }
        }
    }

    @Test
    fun cancelledGenerationCannotReturnFallback() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        var returned = false
        val job = async {
            NavigationEngine { entered.complete(Unit); release.await() }
                .decide(fixture.goal, fixture.elements, fixture.candidateIndices)
            returned = true
        }
        entered.await()
        job.cancelAndJoin()
        release.complete("NONE")
        assertFalse(returned)
    }

    @Test
    fun capturesListsBeforeGenerationAndConcurrentRequestsStayIndependent() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val elements = fixture.elements.toMutableList()
        val candidates = fixture.candidateIndices.toMutableList()
        val engine = NavigationEngine { prompt ->
            if (prompt.contains("Open font size")) {
                entered.complete(Unit)
                release.await()
                "TAP:1"
            } else "NONE"
        }
        val first = async { engine.decide(fixture.goal, elements, candidates) }
        entered.await()
        elements.clear()
        candidates.clear()
        val second = async { engine.decide("Missing", fixture.elements, fixture.candidateIndices) }
        assertNull(second.await().elementIndex)
        release.complete(Unit)
        assertEquals(1, first.await().elementIndex)
    }

    @Test
    fun allFixturesHandleMalformedResponsesWithTheIndependentRuleBaseline() = runBlocking<Unit> {
        navigationFixtures.forEach { sample ->
            val result = NavigationEngine { "TAP:999" }.decide(sample.goal, sample.elements, sample.candidateIndices)
            assertEquals(sample.id, sample.expectedRuleIndex, result.elementIndex)
            assertNull(sample.id, result.modelIndex)
        }
    }
}
