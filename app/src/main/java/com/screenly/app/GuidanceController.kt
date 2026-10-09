package com.screenly.app

import com.screenly.app.ai.navigation.NavigationAction
import com.screenly.app.ai.navigation.ScreenDecision
import com.screenly.app.ai.navigation.ScreenDecisionProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class GuidanceStatus { IDLE, WAITING_FOR_SCREEN, PLANNING, NEXT, DONE, UNCERTAIN }
internal data class GuidanceRequest(val goalVersion: Long, val requestId: Long, val snapshot: GuidanceSnapshot, val goal: String)
internal data class GuidanceUpdate(
    val status: GuidanceStatus,
    val request: GuidanceRequest? = null,
    val result: ScreenDecision? = null
)

/** Main-thread owner. Cancellation is backed by session/revision/request/goal checks. */
internal class GuidanceController(
    private val scope: CoroutineScope,
    private val plan: suspend (String, GuidanceSnapshot, List<String>) -> ScreenDecision,
    private val refresh: () -> GuidanceSnapshot?,
    private val render: (GuidanceUpdate) -> Unit
) {
    private var goal: String? = null
    private var goalVersion = 0L
    private var requestId = 0L
    private var snapshot: GuidanceSnapshot? = null
    private var lastRequestedKey: SnapshotKey? = null
    private var job: Job? = null
    private var disposed = false
    private var lastSuggestedLabel: String? = null
    private var lastSuggestedPackage: String? = null
    private var lastSuggestedObservation: ScreenObservation? = null
    private var lastPresented: Pair<ScreenObservation, ScreenDecision>? = null
    private val previousSuggestions = mutableListOf<String>()
    var status = GuidanceStatus.IDLE
        private set

    fun start(newGoal: String) {
        if (disposed) return
        stop()
        if (newGoal.isBlank() || newGoal.length > 160) return
        goal = newGoal
        goalVersion++
        previousSuggestions.clear()
        lastSuggestedLabel = null
        requestIfNeeded()
    }

    fun observe(next: GuidanceSnapshot?) {
        if (disposed || snapshot?.key == next?.key) return
        if (next != null && next.observation != lastSuggestedObservation) {
            lastSuggestedLabel?.let { previousSuggestions += "Suggested in $lastSuggestedPackage: $it; observed new screen in ${next.observation.packageName}" }
            while (previousSuggestions.size > 4) previousSuggestions.removeAt(0)
        }
        if (next != null) {
            lastSuggestedLabel = null
            lastSuggestedPackage = null
        }
        snapshot = next
        invalidateRequest()
        if (goal != null) requestIfNeeded()
    }

    fun retry() {
        if (disposed || goal == null) return
        invalidateRequest()
        lastRequestedKey = null
        snapshot = refresh()
        requestIfNeeded()
    }

    fun stop() {
        goal = null
        goalVersion++
        invalidateRequest()
        lastRequestedKey = null
        previousSuggestions.clear()
        lastSuggestedLabel = null
        lastSuggestedPackage = null
        lastSuggestedObservation = null
        lastPresented = null
        publish(GuidanceUpdate(GuidanceStatus.IDLE))
    }

    fun close() {
        stop()
        disposed = true
    }

    fun canPresent(request: GuidanceRequest): Boolean = !disposed && goal != null &&
        request.goalVersion == goalVersion && request.requestId == requestId &&
        request.snapshot.key == snapshot?.key && request.snapshot.observation == snapshot?.observation

    private fun invalidateRequest() {
        requestId++
        job?.cancel()
        job = null
    }

    private fun requestIfNeeded() {
        val currentGoal = goal ?: return
        val current = snapshot
        if (current == null) {
            publish(GuidanceUpdate(GuidanceStatus.WAITING_FOR_SCREEN))
            return
        }
        if (current.key == lastRequestedKey) return
        lastRequestedKey = current.key
        val request = GuidanceRequest(goalVersion, ++requestId, current, currentGoal)
        val history = previousSuggestions.toList()
        publish(GuidanceUpdate(GuidanceStatus.PLANNING, request))
        job = scope.launch {
            val result = try {
                plan(currentGoal, current, history)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (canPresent(request)) publish(GuidanceUpdate(GuidanceStatus.UNCERTAIN, request))
                return@launch
            }
            if (!canPresent(request)) return@launch
            // Refresh Android observations immediately before accepting even an unchanged result.
            val fresh = refresh()
            observe(fresh)
            if (fresh?.key != current.key || fresh.observation != current.observation || !canPresent(request)) return@launch
            val index = result.index
            val previous = lastPresented
            if (previous?.first == current.observation && previous.second.action == result.action &&
                previous.second.index == result.index && previous.second.direction == result.direction) {
                publish(GuidanceUpdate(GuidanceStatus.UNCERTAIN, request))
                return@launch
            }
            if (!ScreenDecisionProtocol.grounded(result, current)) {
                publish(GuidanceUpdate(GuidanceStatus.UNCERTAIN, request))
            } else if (index != null) {
                lastSuggestedLabel = "${result.action}:${result.direction}:${current.planningElements[index].text ?: current.planningElements[index].contentDescription}"
                lastSuggestedPackage = current.observation.packageName
                lastSuggestedObservation = current.observation
                lastPresented = current.observation to result
                publish(GuidanceUpdate(GuidanceStatus.NEXT, request, result))
            } else publish(GuidanceUpdate(if (result.action == NavigationAction.DONE) GuidanceStatus.DONE else GuidanceStatus.UNCERTAIN, request, result))
        }
    }

    private fun publish(update: GuidanceUpdate) {
        status = update.status
        render(update)
    }
}
