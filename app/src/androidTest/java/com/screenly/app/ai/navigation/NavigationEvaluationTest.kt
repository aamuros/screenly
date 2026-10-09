package com.screenly.app.ai.navigation

import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in JNI evaluation. A passing harness does not assert model selection quality. */
@RunWith(AndroidJUnit4::class)
class NavigationEvaluationTest {
    @Test
    fun evaluateOfflineNavigation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e navigationEvaluation true after local model provisioning.",
            arguments.getString("navigationEvaluation") == "true")
        val context = instrumentation.targetContext
        val repetitions = arguments.getString("repetitions", "3")!!.toInt()
        require(repetitions in 1..10)
        val requested = arguments.getString("fixtureIds")?.split(',')
        val fixtures = if (requested == null) navigationFixtures else requested.map { id -> navigationFixtures.single { it.id == id } }
        val output = File(context.cacheDir, "navigation-evaluation.jsonl")
        output.writeText("")
        fun record(value: JSONObject) {
            val line = value.toString()
            output.appendText("$line\n")
            instrumentation.sendStatus(0, Bundle().apply { putString("navigation", line) })
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
        record(JSONObject().apply {
            put("kind", "environment")
            put("timestamp_ms", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("api", Build.VERSION.SDK_INT)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("runtime", "LiteRT-LM 0.10.2 CPU/4; 1024 total tokens; default sampler")
            put("model_sha256", LocalModel.SHA256)
            put("model_bytes", LocalModel.fileIn(context.noBackupFilesDir).length())
            put("repetitions", repetitions)
            put("token_counts", "NOT MEASURED; UTF-16 lengths are not tokens")
        })
        // Freeze all expectations and exact prompts in the output before any native call.
        fixtures.forEach { fixture ->
            record(JSONObject().apply {
                put("kind", "fixture")
                put("id", fixture.id)
                put("goal", fixture.goal)
                put("allowed_indices", JSONArray(fixture.candidateIndices))
                put("expected_index", fixture.expectedIndex ?: JSONObject.NULL)
                put("prompt", NavigationProtocol.buildPrompt(fixture.goal, fixture.elements, fixture.candidateIndices) ?: JSONObject.NULL)
                put("elements", JSONArray(fixture.elements.map { element -> JSONObject().apply {
                    put("text", element.text ?: JSONObject.NULL)
                    put("description", element.contentDescription ?: JSONObject.NULL)
                    put("class", element.className ?: JSONObject.NULL)
                    put("view_id", element.viewId ?: JSONObject.NULL)
                    put("enabled", element.enabled)
                    put("clickable", element.clickable)
                    put("checked", element.checked)
                    put("scrollable", element.scrollable)
                    put("bounds", JSONArray(listOf(element.left, element.top, element.right, element.bottom)))
                } }))
            })
        }
        if (arguments.getString("prepareOnly") == "true") return@runBlocking
        checkOffline()
        val inference = LocalInference(context)
        var runtimeFailures = 0
        try {
            val started = System.nanoTime()
            inference.initialize()
            record(JSONObject().apply {
                put("kind", "initialized")
                put("initialize_ms", (System.nanoTime() - started) / 1_000_000)
                put("pss_kib", memoryPss())
            })
            inference.initialize() // Same engine, no reload/hash on subsequent initialization.
            val engine = NavigationEngine(inference::generate)
            repeat(repetitions) { run ->
                fixtures.forEach { fixture ->
                    checkOffline()
                    val ruleStarted = System.nanoTime()
                    val ruleIndex = NavigationRules.select(fixture.goal, fixture.elements, fixture.candidateIndices)
                    val ruleMicros = (System.nanoTime() - ruleStarted) / 1_000
                    val result = engine.decide(fixture.goal, fixture.elements, fixture.candidateIndices)
                    if (result.modelOutcome == ModelOutcome.FAILED) runtimeFailures++
                    record(JSONObject().apply {
                        put("kind", "evaluation")
                        put("fixture", fixture.id)
                        put("run", run + 1)
                        put("raw_response", result.rawResponse ?: JSONObject.NULL)
                        put("parsed_index", result.parsedIndex ?: JSONObject.NULL)
                        put("model_index", result.modelIndex ?: JSONObject.NULL)
                        put("model_outcome", result.modelOutcome.name)
                        put("rejection", result.rejection?.name ?: JSONObject.NULL)
                        put("model_correct", result.modelOutcome in setOf(ModelOutcome.SELECTED, ModelOutcome.ABSTAINED) && result.modelIndex == fixture.expectedIndex)
                        val parsed = result.rawResponse?.let(NavigationProtocol::parseResponse)
                        put("raw_model_correct", parsed != null && parsed.elementIndex == fixture.expectedIndex)
                        put("rule_index", ruleIndex ?: JSONObject.NULL)
                        put("rule_correct", ruleIndex == fixture.expectedIndex)
                        put("rule_us", ruleMicros)
                        put("combined_index", result.elementIndex ?: JSONObject.NULL)
                        put("combined_correct", result.elementIndex == fixture.expectedIndex)
                        put("source", result.source.name)
                        put("fallback_attempted", result.fallbackAttempted)
                        put("failure", result.failure ?: JSONObject.NULL)
                        put("generation_ms", result.generationMillis ?: JSONObject.NULL)
                        put("total_ms", result.totalMillis)
                        put("pss_kib", memoryPss())
                    })
                    result.elementIndex?.let { index ->
                        assertNull(NavigationRules.rejection(fixture.goal, index, fixture.elements, fixture.candidateIndices))
                    }
                    assertEquals(fixture.promptEligible, result.generationMillis != null)
                }
            }
            // Exercise real concurrent calls, cancelled work, reuse, and cleanup without any UI actions.
            val sample = fixtures.firstOrNull { it.promptEligible } ?: navigationFixtures.first { it.promptEligible }
            val prompt = NavigationProtocol.buildPrompt(sample.goal, sample.elements, sample.candidateIndices)!!
            val first = async { inference.generate(prompt) }
            val second = async { inference.generate(prompt) }
            assertTrue(first.await().isNotBlank())
            assertTrue(second.await().isNotBlank())
            val entered = CompletableDeferred<Unit>()
            val cancelled = async { entered.complete(Unit); inference.generate(prompt) }
            entered.await()
            delay(50)
            val cancellationStarted = System.nanoTime()
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            assertTrue(inference.generate(prompt).isNotBlank())
            record(JSONObject().apply {
                put("kind", "runtime_checks")
                put("concurrent_calls", "PASS: two serialized real requests completed")
                put("cancel_and_reuse", "PASS; timing does not establish native interruption")
                put("cancel_join_and_reuse_ms", (System.nanoTime() - cancellationStarted) / 1_000_000)
            })
            checkOffline()
            assertEquals("Native failures recorded in evaluation", 0, runtimeFailures)
        } finally {
            inference.close()
            inference.close()
            record(JSONObject().put("kind", "cleanup").put("status", "PASS"))
        }
    }

    private fun memoryPss(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
}
