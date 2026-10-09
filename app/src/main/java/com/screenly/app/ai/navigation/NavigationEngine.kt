package com.screenly.app.ai.navigation

import com.screenly.app.AccessibleUiElement
import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ModelOutcome { INPUT_REJECTED, PROMPT_REJECTED, SELECTED, ABSTAINED, INVALID_RESPONSE, REJECTED_TARGET, FAILED }
internal enum class DecisionSource { MODEL, RULE, NONE }

/** Diagnostics are for local evaluation, never product instructions or permission to draw/tap. */
internal data class NavigationDecision(
    val elementIndex: Int?,
    val source: DecisionSource,
    val modelOutcome: ModelOutcome,
    val rawResponse: String?,
    val parsedIndex: Int?,
    val modelIndex: Int?,
    val rejection: NavigationRejection?,
    val fallbackAttempted: Boolean,
    val failure: String?,
    val generationMillis: Long?,
    val totalMillis: Long
)

/**
 * Isolated AI backend, not the pending shared Planner API. Inputs contain only copied values.
 * Bind generate to LocalInference; its session owner initializes/reuses/closes the native engine.
 * The caller owns request identity and must reject stale results independently of cancellation.
 */
internal class NavigationEngine(private val generate: suspend (String) -> String) {
    /** Live integration uses independent candidate judgments and explicit, observed route hints. */
    suspend fun decideCandidates(
        goal: String,
        elements: List<AccessibleUiElement>,
        candidateIndices: List<Int>,
        packageName: String,
        verifiedRouteIndices: Set<Int> = emptySet(),
        previousSuggestions: List<String> = emptyList()
    ): CandidateDecision = evaluateCandidates(
        generate, goal, elements, candidateIndices, packageName, verifiedRouteIndices, previousSuggestions
    )

    suspend fun decide(
        goal: String,
        elements: List<AccessibleUiElement>,
        candidateIndices: List<Int>
    ): NavigationDecision {
        // Copy before the first suspension; later caller mutations cannot change index meaning.
        val capturedElements = elements.toList()
        val capturedCandidates = candidateIndices.toList()
        return withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            currentCoroutineContext().ensureActive()
            var outcome = ModelOutcome.INPUT_REJECTED
            var raw: String? = null
            var parsedIndex: Int? = null
            var modelIndex: Int? = null
            var rejection: NavigationRejection? = null
            var failure: String? = null
            var generationMillis: Long? = null
            val validInput = NavigationProtocol.validInput(goal, capturedElements, capturedCandidates)
            if (!validInput) {
                rejection = NavigationRejection.INVALID_INPUT
            } else {
                val prompt = NavigationProtocol.buildPrompt(goal, capturedElements, capturedCandidates)
                if (prompt == null) {
                    outcome = ModelOutcome.PROMPT_REJECTED
                } else {
                    val generationStarted = System.nanoTime()
                    try {
                        raw = generate(prompt)
                        currentCoroutineContext().ensureActive()
                        val parsed = NavigationProtocol.parseResponse(raw)
                        parsedIndex = parsed?.elementIndex
                        outcome = when {
                            parsed == null -> ModelOutcome.INVALID_RESPONSE
                            parsedIndex == null -> ModelOutcome.ABSTAINED
                            else -> {
                                rejection = NavigationRules.rejection(goal, parsedIndex, capturedElements, capturedCandidates)
                                if (rejection == null) {
                                    modelIndex = parsedIndex
                                    ModelOutcome.SELECTED
                                } else ModelOutcome.REJECTED_TARGET
                            }
                        }
                    } catch (error: LocalInferenceException) {
                        outcome = ModelOutcome.FAILED
                        // No model text, private path, or native exception message becomes a UI reason.
                        failure = error.javaClass.simpleName
                    } finally {
                        generationMillis = (System.nanoTime() - generationStarted) / 1_000_000
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val fallbackAttempted = validInput && modelIndex == null
            val ruleIndex = if (fallbackAttempted) NavigationRules.select(goal, capturedElements, capturedCandidates) else null
            currentCoroutineContext().ensureActive()
            NavigationDecision(
                elementIndex = modelIndex ?: ruleIndex,
                source = when {
                    modelIndex != null -> DecisionSource.MODEL
                    ruleIndex != null -> DecisionSource.RULE
                    else -> DecisionSource.NONE
                },
                modelOutcome = outcome,
                rawResponse = raw,
                parsedIndex = parsedIndex,
                modelIndex = modelIndex,
                rejection = rejection,
                fallbackAttempted = fallbackAttempted,
                failure = failure,
                generationMillis = generationMillis,
                totalMillis = (System.nanoTime() - started) / 1_000_000
            )
        }
    }
}
