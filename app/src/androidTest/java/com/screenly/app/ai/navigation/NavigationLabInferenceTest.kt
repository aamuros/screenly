package com.screenly.app.ai.navigation

import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real-model evaluation. Passing proves execution/validation, not selection accuracy. */
@RunWith(AndroidJUnit4::class)
class NavigationLabInferenceTest {
    @Test
    @OptIn(ExperimentalApi::class)
    fun evaluateFixturesWithRealOfflineModel() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e navigationLab true with the provisioned API 30 emulator.",
            arguments.getString("navigationLab") == "true")
        val context = instrumentation.targetContext
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
            val fixtureIds = arguments.getString(
                "fixtureIds", "display-font,missing-font,ambiguous,wifi-unavailable,blocked-targets"
            )!!.split(',')
            val repetitions = arguments.getString("repetitions", "5")!!.toInt()
            val modelId = arguments.getString("comparisonModel", "gemma")!!
            val recordFailures = arguments.getString("recordFailures") == "true"
            require(repetitions in 1..10)
            fixtureIds.forEach { id ->
                val fixture = navigationFixtures.first { it.id == id }
                repeat(repetitions) { run ->
                    // Match the manual lab: a fresh engine/conversation and cleanup on every run.
                    val inference = ComparisonInference(context, modelId)
                    val record = JSONObject().apply {
                        put("model", modelId)
                        put("runtime", "0.10.2")
                        put("backend", "CPU/4")
                        put("fixture", fixture.id)
                        put("run", run + 1)
                        put("goal", fixture.goal)
                        put("allowed_indices", JSONArray(fixture.candidateIndices))
                        put("expected_index", fixture.expectedIndex ?: JSONObject.NULL)
                        put("model_called", false)
                        put("memory_before", memorySample())
                    }
                    try {
                        val result = runNavigationLab(fixture, fixture.goal) { prompt ->
                            record.put("model_called", true)
                            record.put("prompt", prompt)
                            record.put("prompt_utf16_length", prompt.length)
                            val loadStarted = System.nanoTime()
                            inference.initialize()
                            record.put("initialize_ms", (System.nanoTime() - loadStarted) / 1_000_000L)
                            record.put("memory_loaded", memorySample())
                            val generationStarted = System.nanoTime()
                            inference.generate(prompt) { message, benchmark ->
                                record.put("role", message.role.toString())
                                record.put("content_types", JSONArray(message.contents.contents.map { it.javaClass.simpleName }))
                                record.put("text_parts", JSONArray(message.contents.contents.mapNotNull { (it as? Content.Text)?.text }))
                                record.put("channels", JSONObject(message.channels))
                                record.put("tool_calls", message.toolCalls.size)
                                record.put("prefill_tokens", benchmark.lastPrefillTokenCount)
                                record.put("decode_tokens", benchmark.lastDecodeTokenCount)
                                record.put("engine_token_limit", 1024)
                            }.also {
                                record.put("generation_ms", (System.nanoTime() - generationStarted) / 1_000_000L)
                                record.put("memory_after_generation", memorySample())
                            }
                        }
                        record.put("model_outcome", result.outcome.name)
                        record.put("validation_error", result.validationError?.name ?: JSONObject.NULL)
                        record.put("matches_expectation", result.outcome in setOf(
                            NavigationLabOutcome.SELECTED, NavigationLabOutcome.ABSTAINED
                        ) && result.selectedIndex == fixture.expectedIndex)
                        record.put("accepted_index", result.selectedIndex ?: JSONObject.NULL)
                        record.put("raw_response", result.response ?: JSONObject.NULL)
                        record.put("raw_utf16_length", result.response?.length ?: JSONObject.NULL)
                        record.put("raw_codepoints", result.response?.let { JSONArray(it.codePoints().toArray().toList()) } ?: JSONObject.NULL)
                        record.put("load_and_generation_ms", result.modelMillis ?: JSONObject.NULL)
                        record.put("rule_index", result.ruleIndex ?: JSONObject.NULL)
                        record.put("failure", result.failure ?: JSONObject.NULL)
                        instrumentation.sendStatus(0, Bundle().apply { putString("evaluation", record.toString()) })
                        if (fixture.promptEligible && !(recordFailures && result.outcome == NavigationLabOutcome.FAILED)) {
                            assertNotNull(result.response)
                            assertTrue(result.response!!.isNotBlank())
                            assertTrue(result.outcome in setOf(
                                NavigationLabOutcome.SELECTED, NavigationLabOutcome.ABSTAINED, NavigationLabOutcome.INVALID
                            ))
                        } else if (!fixture.promptEligible) {
                            assertEquals(NavigationLabOutcome.INPUT_REJECTED, result.outcome)
                            assertNull(result.response)
                            assertNull(result.selectedIndex)
                        }
                        result.selectedIndex?.let {
                            assertTrue(NavigationProtocol.validTarget(it, fixture.elements, fixture.candidateIndices))
                        }
                        checkOffline()
                    } finally {
                        inference.close()
                    }
                }
            }
        } finally {
            ExperimentalFlags.enableBenchmark = previousBenchmark
        }
    }
}

/** Endpoint PSS/RSS and process lifetime RSS high-water mark, not a per-call peak claim. */
private fun memorySample(): JSONObject {
    val memory = Debug.MemoryInfo()
    Debug.getMemoryInfo(memory)
    return JSONObject().apply {
        put("pss_kib", memory.totalPss)
        put("private_dirty_kib", memory.totalPrivateDirty)
        put("private_clean_kib", memory.totalPrivateClean)
        File("/proc/self/status").useLines { lines ->
            lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.forEach {
                put(if (it.startsWith("VmRSS:")) "rss_kib" else "process_rss_hwm_kib",
                    it.substringAfter(':').trim().substringBefore(' ').toLong())
            }
        }
    }
}
