package com.screenly.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.*
import com.screenly.app.ai.navigation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Frozen synthetic unfamiliar-app fixtures, not a claim about installed third-party apps. */
@RunWith(AndroidJUnit4::class)
class ScreenPlannerNativeTest {
    private data class Fixture(val name: String, val goal: String, val elements: List<AccessibleUiElement>,
        val expected: NavigationAction, val expectedIndex: Int?, val direction: String? = null)
    private fun control(label: String?, clickable: Boolean = true) = AccessibleUiElement(
        label, null, "android.widget.Button", null, clickable, true, false, false, 0, 0, 300, 100)
    private val fixtures = listOf(
        Fixture("photo_upload", "How do I upload a photo?", listOf(control("Upload photo"), control("Cancel")), NavigationAction.NAVIGATE, 0),
        Fixture("folder_creation", "Create a new folder", listOf(control("Sort files"), control("New folder")), NavigationAction.NAVIGATE, 1),
        Fixture("font_adjustment", "Make the text bigger", listOf(control("Font size", false).copy(
            className = "android.widget.SeekBar", range = ControlRange(0f, 4f, 2f), actions = listOf(16908349))), NavigationAction.ADJUST, 0, "INCREASE"),
        Fixture("theme_toggle", "Turn on dark mode", listOf(control("Dark mode").copy(checkable = true)), NavigationAction.TOGGLE, 0, "ON")
    )

    @Test fun accessibilityOnlyNativePlanner() = runBlocking { benchmark(false) }
    @Test fun multimodalNativePlanner() = runBlocking { benchmark(true) }

    private suspend fun benchmark(multimodal: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e plannerBenchmark true.", args.getString("plannerBenchmark") == "true")
        val context = instrumentation.targetContext
        val runtime = LiteRtMultimodalInference(context)
        if (multimodal) assertEquals("Pass the image compatibility gate first.", ImageModelAvailability.VERIFIED, runtime.availability())
        var lastRaw = ""
        val vision = if (multimodal) object : LocalMultimodalInference by runtime {
            override suspend fun generate(prompt: String, image: ByteArray?) = runtime.generate(prompt, image).also { lastRaw = it }
        } else object : LocalMultimodalInference by runtime {
            override fun availability() = ImageModelAvailability.UNSUPPORTED
        }
        val text = LocalInference(context)
        fun record(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
        val planner = ScreenPlanner(vision, { prompt -> text.initialize(); text.generate(prompt).also { lastRaw = it } },
            releaseText = { text.close() }, capture = { snapshot ->
                val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 24f }
                snapshot.planningElements.forEachIndexed { i, element -> canvas.drawText(element.text ?: "Upload photo", 8f, 40f + i * 60f, paint) }
                val encoded = try { ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() } }
                    finally { bitmap.recycle() }
                ScreenCapture.Image(ScreenImage(snapshot.key, encoded, 0, 0, 400, 300, 400, 300))
            }, fresh = { true })
        var correct = 0
        val selectedFixtures = fixtures.filter { args.getString("fixtureFilter")?.let { name -> it.name == name } ?: true }
        check(selectedFixtures.isNotEmpty())
        try {
            for ((i, fixture) in selectedFixtures.withIndex()) {
                val snapshot = guidanceSnapshot(SnapshotKey(1, i.toLong()), ScreenObservation("fixture.${fixture.name}", i, fixture.elements, imageAllowed = true), 400, 300)
                val start = SystemClock.elapsedRealtime()
                val result = planner.plan(fixture.goal, snapshot, emptyList())
                val match = result.action == fixture.expected && result.index == fixture.expectedIndex && result.direction == fixture.direction
                if (match) correct++
                // Deliberately synthetic fixtures only; never log real user screen responses.
                record("${fixture.name}_synthetic_raw", lastRaw)
                record(fixture.name, "mode=${result.mode}; action=${result.action}; index=${result.index}; direction=${result.direction}; " +
                    "correct=$match; elapsed_ms=${SystemClock.elapsedRealtime() - start}; pss_kib=${Debug.getPss()}")
            }
            record("fixture_accuracy", "$correct/${selectedFixtures.size}; synthetic; multimodal=$multimodal")
            assertEquals("Native planner must pass these basic tasks before claiming guidance quality.", selectedFixtures.size, correct)
        } finally { text.close(); runtime.close() }
    }
}
