package com.screenly.app.ai.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.screenly.app.R
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Separate debug launcher; never connects to the observation service or shared Planner API. */
class NavigationLabActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fixture by mutableStateOf(navigationFixtures.first { it.id == "display-font" })
    private var goal by mutableStateOf(fixture.goal)
    private var running by mutableStateOf(false)
    private var report by mutableStateOf<NavigationLabReport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                NavigationLabScreen(
                    fixture = fixture,
                    goal = goal,
                    running = running,
                    report = report,
                    onFixture = { next -> fixture = next; goal = next.goal; report = null },
                    onGoal = { next -> goal = next; report = null },
                    onRun = ::runModel
                )
            }
        }
    }

    private fun runModel() {
        if (running) return
        val capturedFixture = fixture
        val capturedGoal = goal
        running = true
        report = null
        scope.launch {
            // A destroyed activity's blocking JNI call must finish/close before a new lab loads.
            modelRunMutex.withLock {
                val inference = LocalInference(this@NavigationLabActivity)
                try {
                    report = runNavigationLab(capturedFixture, capturedGoal) { prompt ->
                        inference.initialize()
                        inference.generate(prompt)
                    }
                } finally {
                    try {
                        inference.close()
                    } catch (error: LocalInferenceException) {
                        report = report?.copy(
                            outcome = NavigationLabOutcome.FAILED, selectedIndex = null, failure = error.message
                        ) ?: NavigationLabReport(
                            NavigationLabOutcome.FAILED, ruleIndex = null, ruleMillis = 0, failure = error.message
                        )
                    } finally {
                        running = false
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val modelRunMutex = Mutex()
    }
}

@Composable
private fun NavigationLabScreen(
    fixture: NavigationFixture,
    goal: String,
    running: Boolean,
    report: NavigationLabReport?,
    onFixture: (NavigationFixture) -> Unit,
    onGoal: (String) -> Unit,
    onRun: () -> Unit
) {
    var choosing by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.navigation_lab_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.navigation_lab_scope), style = MaterialTheme.typography.bodySmall)
            Column {
                OutlinedButton(onClick = { choosing = true }, enabled = !running) {
                    Text(stringResource(R.string.navigation_lab_fixture, fixture.id))
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    navigationFixtures.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.id) },
                            onClick = { choosing = false; onFixture(candidate) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = goal,
                onValueChange = onGoal,
                enabled = !running,
                label = { Text(stringResource(R.string.navigation_lab_goal)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onRun, enabled = !running && goal.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.navigation_lab_run))
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.navigation_lab_running))
                }
            }
            Text(stringResource(R.string.navigation_lab_elements), style = MaterialTheme.typography.titleMedium)
            fixture.elements.forEachIndexed { index, element ->
                val selected = report?.selectedIndex == index
                val allowed = index in fixture.candidateIndices
                val label = NavigationProtocol.labelOf(element) ?: stringResource(R.string.navigation_lab_unlabelled)
                Column(
                    modifier = Modifier.fillMaxWidth().background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ).padding(vertical = 8.dp, horizontal = 12.dp)
                ) {
                    Text(stringResource(R.string.navigation_lab_row, index, label))
                    Text(
                        stringResource(when {
                            selected -> R.string.navigation_lab_selected
                            !allowed -> R.string.navigation_lab_context
                            element.checked -> R.string.navigation_lab_checked
                            else -> R.string.navigation_lab_candidate
                        }),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
            }
            if (goal == fixture.goal) {
                Text(if (fixture.expectedIndex == null) {
                    stringResource(R.string.navigation_lab_expected_none)
                } else {
                    stringResource(R.string.navigation_lab_expected_target, fixture.expectedIndex)
                })
            } else {
                Text(stringResource(R.string.navigation_lab_no_expectation))
            }
            report?.let { result -> NavigationLabResult(fixture, goal, result) }
            Text(stringResource(R.string.navigation_lab_footer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NavigationLabResult(fixture: NavigationFixture, goal: String, report: NavigationLabReport) {
    Text(stringResource(R.string.navigation_lab_result), style = MaterialTheme.typography.titleMedium)
    val index = report.selectedIndex
    if (report.outcome == NavigationLabOutcome.SELECTED && index != null) {
        Text(stringResource(
            R.string.navigation_lab_target, index,
            NavigationProtocol.labelOf(fixture.elements[index]) ?: stringResource(R.string.navigation_lab_unlabelled)
        ))
    } else {
        Text(stringResource(when (report.outcome) {
            NavigationLabOutcome.ABSTAINED -> R.string.navigation_lab_none
            NavigationLabOutcome.INPUT_REJECTED -> R.string.navigation_lab_rejected
            NavigationLabOutcome.FAILED -> R.string.navigation_lab_failure
            else -> R.string.navigation_lab_invalid
        }))
    }
    report.modelMillis?.let { Text(stringResource(R.string.navigation_lab_model_time, it)) }
    report.validationError?.let {
        Text(stringResource(when (it) {
            NavigationLabValidationError.INVALID_SYNTAX -> R.string.navigation_lab_invalid_syntax
            NavigationLabValidationError.TARGET_NOT_ALLOWED -> R.string.navigation_lab_disallowed_target
        }))
    }
    if (goal == fixture.goal && report.outcome in setOf(NavigationLabOutcome.SELECTED, NavigationLabOutcome.ABSTAINED)) {
        Text(stringResource(
            if (report.selectedIndex == fixture.expectedIndex) R.string.navigation_lab_match else R.string.navigation_lab_mismatch
        ))
    }
    val rule = report.ruleIndex?.let { stringResource(R.string.navigation_lab_rule_index, it) }
        ?: stringResource(R.string.navigation_lab_rule_none)
    Text(stringResource(R.string.navigation_lab_rules, rule, report.ruleMillis))
    report.failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
    report.response?.let {
        Text(stringResource(R.string.navigation_lab_raw), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    report.prompt?.let {
        Text(stringResource(R.string.navigation_lab_prompt), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
