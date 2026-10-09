package com.screenly.app.ai.navigation

import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Paired Gemma diagnostic only; execution assertions deliberately do not assert model accuracy. */
@RunWith(AndroidJUnit4::class)
class AvailabilityDiagnosticInferenceTest {
    @Test
    @OptIn(ExperimentalApi::class)
    fun compareFrozenProtocolsOffline() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("availabilityDiagnostic") == "true")
        val context = instrumentation.targetContext
        val repetitions = arguments.getString("repetitions", "5")!!.toInt()
        require(repetitions in 1..10)
        fun emit(key: String, record: JSONObject) {
            instrumentation.sendStatus(0, Bundle().apply { putString(key, record.toString()) })
        }
        val manifests = availabilityDiagnosticFixtures.mapIndexed { index, fixture ->
            val baseline = checkNotNull(NavigationProtocol.buildPrompt(
                fixture.goal, fixture.elements, fixture.candidateIndices
            ))
            val availability = checkNotNull(availabilityPrompt(fixture))
            assertEquals(baseline.substringAfter('\n'), availability.substringAfter('\n'))
            assertTrue(baseline.length <= 1000 && availability.length <= 1000)
            JSONObject().apply {
                put("fixture", fixture.id)
                put("group", if (index < 4) "original" else "new_variation")
                put("goal", fixture.goal)
                put("expected_index", fixture.expectedIndex ?: JSONObject.NULL)
                put("expected_available", fixture.expectedIndex != null)
                put("allowed_indices", JSONArray(fixture.candidateIndices))
                put("baseline_prompt", baseline)
                put("availability_prompt", availability)
                put("elements", JSONArray(fixture.elements.map { element -> JSONObject().apply {
                    put("text", element.text ?: JSONObject.NULL)
                    put("description", element.contentDescription ?: JSONObject.NULL)
                    put("class", element.className ?: JSONObject.NULL)
                    put("enabled", element.enabled)
                    put("clickable", element.clickable)
                    put("checked", element.checked)
                    put("bounds", JSONArray(listOf(element.left, element.top, element.right, element.bottom)))
                } }))
            }
        }
        if (arguments.getString("prepareOnly") == "true") {
            manifests.forEach { emit("fixture_manifest", it) }
            return@runBlocking
        }
        fun checkOffline() {
            assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on", -1))
            assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", -1))
            assertEquals(0, Settings.Global.getInt(context.contentResolver, "mobile_data", -1))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.ACCESS_NETWORK_STATE")
            try {
                assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
        val previousBenchmark = ExperimentalFlags.enableBenchmark
        ExperimentalFlags.enableBenchmark = true
        try {
            checkOffline()
            availabilityDiagnosticFixtures.forEachIndexed { fixtureIndex, fixture ->
                repeat(repetitions) { run ->
                    // Alternate arm order to avoid assigning all later/cached runs to one protocol.
                    val arms = if (run % 2 == 0) listOf("baseline", "availability_first")
                        else listOf("availability_first", "baseline")
                    for (arm in arms) {
                        val inference = LocalInference(context)
                        val stages = JSONArray()
                        val record = JSONObject().apply {
                            put("fixture", fixture.id)
                            put("group", manifests[fixtureIndex].getString("group"))
                            put("run", run + 1)
                            put("arm", arm)
                            put("model", "gemma")
                            put("expected_index", fixture.expectedIndex ?: JSONObject.NULL)
                            put("expected_available", fixture.expectedIndex != null)
                            put("stages", stages)
                            put("rule_index", NavigationRules.select(
                                fixture.goal, fixture.elements, fixture.candidateIndices
                            ) ?: JSONObject.NULL)
                            put("selected_index", JSONObject.NULL)
                            put("available", JSONObject.NULL)
                        }
                        val started = System.nanoTime()
                        try {
                            val loadStarted = System.nanoTime()
                            inference.initialize()
                            record.put("initialize_ms", (System.nanoTime() - loadStarted) / 1_000_000L)
                            val generate: suspend (String) -> String = { prompt ->
                                val stage = JSONObject().apply {
                                    put("stage", if (arm == "availability_first" && stages.length() == 0)
                                        "availability" else "selection")
                                    put("prompt", prompt)
                                }
                                stages.put(stage)
                                val generationStarted = System.nanoTime()
                                try {
                                    inference.generate(prompt) { message, benchmark ->
                                        stage.put("role", message.role.toString())
                                        stage.put("text_parts", JSONArray(message.contents.contents.mapNotNull {
                                            (it as? Content.Text)?.text
                                        }))
                                        stage.put("channels", JSONObject(message.channels))
                                        stage.put("tool_calls", message.toolCalls.size)
                                        stage.put("prefill_tokens", benchmark.lastPrefillTokenCount)
                                        stage.put("decode_tokens", benchmark.lastDecodeTokenCount)
                                    }.also { stage.put("raw_response", it) }
                                } catch (error: LocalInferenceException) {
                                    stage.put("failure", error.message)
                                    throw error
                                } finally {
                                    stage.put("generation_ms", (System.nanoTime() - generationStarted) / 1_000_000L)
                                }
                            }
                            if (arm == "baseline") {
                                val result = runNavigationLab(fixture, fixture.goal, generate)
                                record.put("outcome", result.outcome.name)
                                record.put("selected_index", result.selectedIndex ?: JSONObject.NULL)
                                record.put("validation_error", result.validationError?.name ?: JSONObject.NULL)
                                record.put("failure", result.failure ?: JSONObject.NULL)
                            } else {
                                val result = runAvailabilityDiagnostic(fixture, generate)
                                record.put("outcome", result.outcome.name)
                                record.put("available", result.available ?: JSONObject.NULL)
                                record.put("selected_index", result.selection?.selectedIndex ?: JSONObject.NULL)
                                record.put("validation_error", result.selection?.validationError?.name ?: JSONObject.NULL)
                                record.put("failure", result.failure ?: JSONObject.NULL)
                                if (result.available != true) assertEquals(1, stages.length())
                            }
                            if (!record.isNull("selected_index")) assertTrue(NavigationProtocol.validTarget(
                                record.getInt("selected_index"), fixture.elements, fixture.candidateIndices
                            ))
                        } catch (error: LocalInferenceException) {
                            record.put("outcome", NavigationLabOutcome.FAILED.name)
                            record.put("failure", error.message)
                        } finally {
                            record.put("load_and_decision_ms", (System.nanoTime() - started) / 1_000_000L)
                            inference.close()
                        }
                        emit("diagnostic", record)
                        checkOffline()
                    }
                }
            }
        } finally {
            ExperimentalFlags.enableBenchmark = previousBenchmark
        }
    }
}
