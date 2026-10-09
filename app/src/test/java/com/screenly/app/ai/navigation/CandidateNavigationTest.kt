package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Regression checks for historical YES/NO evaluation; production uses ScreenPlanner. */
class CandidateNavigationTest {
    @Test fun answersRetainTrustedOriginalIndices() = runBlocking {
        val elements = List(40) { fixtureElement("Heading $it", clickable = false) }.toMutableList()
        elements[37] = fixtureElement("Upload")
        elements[19] = fixtureElement("Sound")
        val result = NavigationEngine { if (it.contains("Label: \"Upload\"")) "YES" else "NO" }
            .decideCandidates("Upload a photo", elements, listOf(19, 37), "unknown.app")
        assertEquals(37, result.decision.elementIndex)
        assertEquals(DecisionSource.MODEL, result.decision.source)
    }

    @Test fun failureMalformedOutputAndAbstentionNeverInvokeRules() = runBlocking {
        val elements = listOf(fixtureElement("Upload"))
        for (answer in listOf("NO", "Yes", "YES because", "TAP:0")) {
            val result = NavigationEngine { answer }.decideCandidates("Upload", elements, listOf(0), "unknown.app")
            assertNull(result.decision.elementIndex)
            assertFalse(result.decision.fallbackAttempted)
        }
        val failed = NavigationEngine { throw LocalInferenceException("failure", IOException()) }
            .decideCandidates("Upload", elements, listOf(0), "unknown.app")
        assertNull(failed.decision.elementIndex)
        assertEquals(ModelOutcome.FAILED, failed.decision.modelOutcome)
    }

    @Test fun multipleModelMatchesAbstain() = runBlocking {
        val elements = listOf(fixtureElement("Upload"), fixtureElement("Upload"))
        val result = NavigationEngine { "YES" }.decideCandidates("Upload", elements, listOf(0, 1), "app")
        assertNull(result.decision.elementIndex)
        assertEquals(ModelOutcome.REJECTED_TARGET, result.decision.modelOutcome)
    }

    @Test fun cancellationPropagates() = runBlocking {
        try {
            NavigationEngine { throw CancellationException() }.decideCandidates("Upload", listOf(fixtureElement("Upload")), listOf(0), "app")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
