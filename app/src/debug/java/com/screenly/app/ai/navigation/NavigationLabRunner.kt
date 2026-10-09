package com.screenly.app.ai.navigation

import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class NavigationLabOutcome {
    SELECTED, ABSTAINED, INVALID, INPUT_REJECTED, FAILED
}

internal enum class NavigationLabValidationError {
    INVALID_SYNTAX, TARGET_NOT_ALLOWED
}

/** Debug fixture evaluation only; no shared planner types, identity, fallback or Android actions. */
internal data class NavigationLabReport(
    val outcome: NavigationLabOutcome,
    val selectedIndex: Int? = null,
    val response: String? = null,
    val modelMillis: Long? = null,
    val ruleIndex: Int?,
    val ruleMillis: Long,
    val failure: String? = null,
    val prompt: String? = null,
    val validationError: NavigationLabValidationError? = null
)

/** The injected call loads/generates locally; cancellation propagates without rule substitution. */
internal suspend fun runNavigationLab(
    fixture: NavigationFixture,
    goal: String,
    generate: suspend (String) -> String
): NavigationLabReport {
    val ruleStarted = System.nanoTime()
    val ruleIndex = NavigationRules.select(goal, fixture.elements, fixture.candidateIndices)
    val ruleMillis = elapsedMillis(ruleStarted)
    val prompt = NavigationProtocol.buildPrompt(goal, fixture.elements, fixture.candidateIndices)
        ?: return NavigationLabReport(
            NavigationLabOutcome.INPUT_REJECTED, ruleIndex = ruleIndex, ruleMillis = ruleMillis
        )
    val started = System.nanoTime()
    val response = try {
        generate(prompt).also { currentCoroutineContext().ensureActive() }
    } catch (error: LocalInferenceException) {
        return NavigationLabReport(
            NavigationLabOutcome.FAILED, modelMillis = elapsedMillis(started),
            ruleIndex = ruleIndex, ruleMillis = ruleMillis, failure = error.message, prompt = prompt
        )
    }
    val modelMillis = elapsedMillis(started)
    val parsed = NavigationProtocol.parseResponse(response)
    val index = parsed?.elementIndex
    val validationError = when {
        parsed == null -> NavigationLabValidationError.INVALID_SYNTAX
        index != null && !NavigationProtocol.validTarget(index, fixture.elements, fixture.candidateIndices) ->
            NavigationLabValidationError.TARGET_NOT_ALLOWED
        else -> null
    }
    val outcome = when {
        validationError != null -> NavigationLabOutcome.INVALID
        index == null -> NavigationLabOutcome.ABSTAINED
        else -> NavigationLabOutcome.SELECTED
    }
    return NavigationLabReport(
        outcome = outcome,
        selectedIndex = index.takeIf { outcome == NavigationLabOutcome.SELECTED },
        response = response,
        modelMillis = modelMillis,
        ruleIndex = ruleIndex,
        ruleMillis = ruleMillis,
        prompt = prompt,
        validationError = validationError
    )
}

private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
