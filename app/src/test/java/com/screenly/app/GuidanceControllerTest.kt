package com.screenly.app

import com.screenly.app.ai.navigation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GuidanceControllerTest {
    private fun snapshot(session: Long = 1, revision: Long = 1, label: String = "Font size"): GuidanceSnapshot {
        val element = AccessibleUiElement(label, null, "Button", null, true, true, false, false, 0, 0, 100, 100)
        return guidanceSnapshot(SnapshotKey(session, revision), ScreenObservation("app", 1, listOf(element)), 100, 100)
    }
    private fun decision() = CandidateDecision(NavigationDecision(0, DecisionSource.MODEL, ModelOutcome.SELECTED,
        "YES", 0, 0, null, false, null, 1, 1), listOf(CandidateEvaluation(0, "YES", true)))

    @Test fun unchangedScreenWaitsForManualActionAndChangedScreenPlansNextStep() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var current = snapshot()
        var calls = 0
        val updates = mutableListOf<GuidanceUpdate>()
        val controller = GuidanceController(scope, { _, _, _ -> calls++; decision() }, { current }, updates::add)
        controller.observe(current)
        controller.start("Open font size")
        assertEquals(GuidanceStatus.NEXT, controller.status)
        repeat(5) { controller.observe(current) }
        assertEquals(1, calls)
        current = snapshot(revision = 2, label = "Next setting")
        controller.observe(current)
        assertEquals(2, calls)
        assertEquals(GuidanceStatus.NEXT, controller.status)
        controller.close(); scope.cancel()
    }

    @Test fun delayedResultsCannotSurviveGoalReplacementOrStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val current = snapshot()
        val release = CompletableDeferred<Unit>()
        val updates = mutableListOf<GuidanceUpdate>()
        val controller = GuidanceController(scope, { _, _, _ -> withContext(NonCancellable) { release.await(); decision() } },
            { current }, updates::add)
        controller.observe(current)
        controller.start("Open font size")
        val old = updates.last().request!!
        controller.start("Open Wi-Fi")
        assertFalse(controller.canPresent(old))
        val replacement = updates.last().request!!
        controller.stop()
        assertFalse(controller.canPresent(replacement))
        release.complete(Unit)
        yield()
        assertTrue(updates.none { it.status == GuidanceStatus.NEXT })
        controller.close(); scope.cancel()
    }

    @Test fun navigationAwayBackAndSessionReconnectRejectOldRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var current = snapshot()
        val updates = mutableListOf<GuidanceUpdate>()
        val controller = GuidanceController(scope, { _, _, _ -> release.await(); decision() }, { current }, updates::add)
        controller.observe(current); controller.start("Open font size")
        val old = updates.last().request!!
        controller.observe(null)
        assertFalse(controller.canPresent(old))
        current = snapshot(revision = 3)
        controller.observe(current)
        assertFalse(controller.canPresent(old))
        val beforeReconnect = updates.last().request!!
        current = snapshot(session = 2, revision = 1)
        controller.observe(current)
        assertFalse(controller.canPresent(beforeReconnect))
        controller.close(); release.complete(Unit); scope.cancel()
    }

    @Test fun finalRefreshDiscardsScreenChangedDuringInference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var current = snapshot()
        val updates = mutableListOf<GuidanceUpdate>()
        lateinit var controller: GuidanceController
        controller = GuidanceController(scope, { _, _, _ -> release.await(); decision() }, {
            current = snapshot(revision = 2)
            controller.observe(current)
            current
        }, updates::add)
        controller.observe(current); controller.start("Open font size")
        val old = updates.last().request!!
        release.complete(Unit)
        yield()
        assertFalse(controller.canPresent(old))
        assertTrue(updates.filter { it.status == GuidanceStatus.NEXT }.all { it.request!!.snapshot.key.revision == 2L })
        controller.close(); scope.cancel()
    }

    @Test fun nonCancellableResponseFromABACannotPresentOldHighlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var current = snapshot()
        val updates = mutableListOf<GuidanceUpdate>()
        val controller = GuidanceController(scope, { _, _, _ -> withContext(NonCancellable) { release.await(); decision() } },
            { current }, updates::add)
        controller.observe(current); controller.start("Open font size")
        val old = updates.last().request!!
        current = snapshot(revision = 2, label = "Display")
        controller.observe(current)
        current = snapshot(revision = 3)
        controller.observe(current)
        assertEquals(old.snapshot.observation, current.observation)
        release.complete(Unit); yield()
        assertFalse(controller.canPresent(old))
        assertTrue(updates.filter { it.status == GuidanceStatus.NEXT }.all { it.request!!.snapshot.key.revision == 3L })
        controller.close(); scope.cancel()
    }
}
