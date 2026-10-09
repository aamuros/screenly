package com.screenly.app.ai.navigation

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.AccessibleUiElement
import com.screenly.app.ScreenObservation
import com.screenly.app.SnapshotKey
import com.screenly.app.guidanceSnapshot
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import com.screenly.app.ai.LocalModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.File

/** Frozen evaluation data only. Never draws or activates a control; production policy is unchanged. */
@RunWith(AndroidJUnit4::class)
class HeldOutNavigationTest {
    @Test
    fun compareFrozenSnapshots() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("heldOutNavigation") == "true")
        val repetitions = args.getString("repetitions", "3")!!.toInt()
        require(repetitions in 1..10)
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val cases = JSONArray(assets.open("navigation-heldout/cases.json").bufferedReader().use { it.readText() })
        val prepareOnly = args.getString("prepareOnly") == "true"
        val output = File(context.cacheDir, if (prepareOnly) "navigation-prepared.jsonl" else "navigation-evaluation.jsonl")
        output.writeText("")
        fun record(row: JSONObject) {
            output.appendText("$row\n")
            instrumentation.sendStatus(0, Bundle().apply { putString("navigation", row.toString()) })
        }
        val frozen = (0 until cases.length()).map { number ->
            val case = cases.getJSONObject(number)
            val elements = mutableListOf<AccessibleUiElement>()
            val parents = mutableListOf<Int>()
            val parser = Xml.newPullParser()
            assets.open("navigation-heldout/${case.getString("screen")}.xml").use { input ->
                parser.setInput(input, "UTF-8")
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.name == "node" && parser.eventType == XmlPullParser.START_TAG) {
                        fun attr(name: String) = parser.getAttributeValue(null, name).orEmpty()
                        fun label(name: String) = attr(name).takeIf { it.isNotBlank() }
                        // These frozen test screens have no password/editable/data-sensitive nodes.
                        require(attr("password") != "true" && !attr("class").endsWith("EditText"))
                        val bounds = Regex("-?\\d+").findAll(attr("bounds")).map { it.value.toInt() }.toList()
                        elements += AccessibleUiElement(label("text"), label("content-desc"), label("class"),
                            label("resource-id"), attr("clickable") == "true", attr("enabled") == "true",
                            attr("checked") == "true", attr("scrollable") == "true",
                            bounds[0], bounds[1], bounds[2], bounds[3], parents.lastOrNull())
                        parents += elements.lastIndex
                    } else if (parser.name == "node" && parser.eventType == XmlPullParser.END_TAG) {
                        parents.removeAt(parents.lastIndex)
                    }
                    parser.next()
                }
            }
            when (case.optString("mutation")) {
                "duplicate-start" -> elements += elements[31].copy(top = 300, bottom = 400)
                "remove-create-label" -> elements[25] = elements[25].copy(text = null, contentDescription = null)
                "disable-create" -> elements[25] = elements[25].copy(enabled = false)
            }
            val packageName = if (case.getString("screen") == "contacts") "com.android.contacts" else "com.google.android.deskclock"
            val snapshot = guidanceSnapshot(SnapshotKey(1, number.toLong()), ScreenObservation(packageName, 1, elements), 1080, 2148)
            val expected = if (case.isNull("expected")) null else case.getInt("expected")
            expected?.let { require(it in snapshot.candidateIndices) }
            record(JSONObject(case.toString()).apply {
                put("kind", "fixture"); put("expected_index", expected ?: JSONObject.NULL)
                put("expected_policy_rejection", expected?.let { NavigationRules.rejection(
                    SettingsWorkflow.canonicalGoal(case.getString("goal")), it, snapshot.planningElements,
                    snapshot.candidateIndices, emptySet())?.name } ?: JSONObject.NULL)
                put("package", packageName); put("allowed_indices", JSONArray(snapshot.candidateIndices))
                put("provenance", if (case.has("mutation")) "constructed variant of recorded UIAutomator hierarchy" else "recorded UIAutomator hierarchy")
                put("planning_elements", JSONArray(snapshot.planningElements.map { e -> JSONObject().apply {
                    put("text", e.text ?: JSONObject.NULL); put("description", e.contentDescription ?: JSONObject.NULL)
                    put("class", e.className ?: JSONObject.NULL); put("enabled", e.enabled); put("clickable", e.clickable)
                    put("checked", e.checked); put("parent", e.parentIndex ?: JSONObject.NULL)
                    put("bounds", JSONArray(listOf(e.left, e.top, e.right, e.bottom)))
                } }))
            })
            Triple(case, snapshot, expected)
        }
        record(JSONObject().apply {
            put("kind", "environment"); put("model_sha256", LocalModel.SHA256)
            put("runtime", "LiteRT-LM 0.10.2 CPU/4; 1024 total tokens; default sampler")
            put("repetitions", repetitions); put("baseline", "identical production fallback policy; canonical goal and empty verified routes")
        })
        if (prepareOnly) return@runBlocking
        val inference = LocalInference(context)
        try {
            val started = System.nanoTime()
            inference.initialize()
            record(JSONObject().put("kind", "initialized").put("initialize_ms", (System.nanoTime() - started) / 1_000_000))
            val engine = NavigationEngine(inference::generate)
            // Exercise the actual fallback implementation, avoiding a weaker alternative rule policy.
            val rules = NavigationEngine { throw LocalInferenceException("Benchmark rules-only mode", IllegalStateException("Model intentionally bypassed")) }
            repeat(repetitions) { run ->
                frozen.forEach { (case, snapshot, expected) ->
                    val goal = case.getString("goal")
                    val baseline = rules.decideCandidates(goal, snapshot.planningElements, snapshot.candidateIndices,
                        snapshot.observation.packageName).decision
                    val result = engine.decideCandidates(goal, snapshot.planningElements, snapshot.candidateIndices,
                        snapshot.observation.packageName)
                    val decision = result.decision
                    val yes = result.evaluations.filter { it.relevant == true }
                    val rawCorrect = result.evaluations.isNotEmpty() && result.evaluations.all { it.relevant != null } &&
                        if (expected == null) yes.isEmpty() else yes.size == 1 && yes.single().originalIndex == expected
                    record(JSONObject().apply {
                        put("kind", "evaluation"); put("fixture", case.getString("id")); put("run", run + 1)
                        put("rule_index", baseline.elementIndex ?: JSONObject.NULL); put("rule_correct", baseline.elementIndex == expected)
                        put("rule_ms", baseline.totalMillis); put("combined_index", decision.elementIndex ?: JSONObject.NULL)
                        put("combined_correct", decision.elementIndex == expected); put("raw_model_correct", rawCorrect)
                        put("model_correct", decision.modelIndex == expected && decision.modelOutcome in setOf(ModelOutcome.SELECTED, ModelOutcome.ABSTAINED))
                        put("model_outcome", decision.modelOutcome.name); put("source", decision.source.name)
                        put("rejection", decision.rejection?.name ?: JSONObject.NULL); put("fallback_attempted", decision.fallbackAttempted)
                        put("generation_ms", decision.generationMillis ?: JSONObject.NULL); put("total_ms", decision.totalMillis)
                        put("pss_kib", android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss)
                        put("expected_evaluated", expected == null || result.evaluations.any { it.originalIndex == expected })
                        put("candidate_evaluations", JSONArray(result.evaluations.map { e -> JSONObject().apply {
                            put("original_index", e.originalIndex); put("raw", e.raw ?: JSONObject.NULL); put("relevant", e.relevant ?: JSONObject.NULL)
                        } }))
                    })
                    decision.elementIndex?.let { index -> assertNull(NavigationRules.rejection(
                        SettingsWorkflow.canonicalGoal(goal), index, snapshot.planningElements, snapshot.candidateIndices, emptySet())) }
                }
            }
            // Teardown while a request is in flight: cancellation cannot interrupt synchronous JNI.
            val entered = CompletableDeferred<Unit>()
            val pending = async(Dispatchers.IO) { entered.complete(Unit); inference.generate("Reply exactly YES.") }
            entered.await()
            delay(100)
            pending.cancel()
            inference.close() // Must serialize after native work, even with cancelled generation.
            pending.join()
            assertTrue(pending.isCancelled)
            inference.close()
            try { inference.generate("YES"); fail("Closed engine accepted work") } catch (_: IllegalStateException) { }
            // A replacement owner can load after the old owner has finished closing.
            val replacement = LocalInference(context)
            try {
                replacement.initialize()
                assertTrue(replacement.generate("Reply exactly YES.").isNotBlank())
            } finally { replacement.close() }
            record(JSONObject().put("kind", "runtime_checks").put("cancel_close_reload", "PASS; isolated runtime, not a live service reconnect"))
        } finally {
            inference.close()
            record(JSONObject().put("kind", "cleanup").put("status", "PASS"))
        }
    }
}
