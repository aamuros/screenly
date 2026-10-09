package com.screenly.app.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.*
import com.screenly.app.ai.navigation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** One synthetic A/B sample, with initialized engines; not general-app accuracy evidence. */
@RunWith(AndroidJUnit4::class)
class CapturedAnswerNativeTest {
    @Test fun compareQuestionLatencyAndVerifyFrozenImageInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e capturedAnswerBenchmark true.",
            InstrumentationRegistry.getArguments().getString("capturedAnswerBenchmark") == "true")
        assertNotEquals("Run the native benchmark separately from live UI tests, whose retained engine affects memory and latency.",
            "true", InstrumentationRegistry.getArguments().getString("assistantUi"))
        val context = instrumentation.targetContext
        fun record(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
        val screen = guidanceSnapshot(SnapshotKey(1, 1), ScreenObservation("fixture.upload", 1, listOf(
            AccessibleUiElement("Upload photo", null, "Button", null, true, true, false, false, 0, 0, 180, 80),
            AccessibleUiElement("Cancel", null, "Button", null, true, true, false, false, 0, 100, 180, 180)
        ), imageAllowed = true), 256, 256)
        val text = LocalInference(context)
        val vision = LiteRtMultimodalInference(context)
        val unavailableVision = object : LocalMultimodalInference by vision {
            override fun availability() = ImageModelAvailability.UNSUPPORTED
        }
        var calls = 0
        val planner = ScreenPlanner(unavailableVision, { calls++; text.generate(it) },
            capture = { error("No image in the text A/B sample") }, fresh = { true })
        try {
            val init = SystemClock.elapsedRealtime()
            text.initialize()
            record("text_initialize_ms", "${SystemClock.elapsedRealtime() - init}")
            var start = SystemClock.elapsedRealtime()
            val old = planner.plan("What is this screen for?", screen, emptyList(), question = true)
            record("previous_text_question", "elapsed_ms=${SystemClock.elapsedRealtime() - start}; native_calls=$calls; valid_answer=${old.explanation.isNotBlank()}")
            calls = 0
            start = SystemClock.elapsedRealtime()
            val answer = planner.answerCaptured("What is this screen for?", screen, ScreenCapture.Failed(CaptureFailure.PRIVACY), emptyList())
            record("captured_text_question", "elapsed_ms=${SystemClock.elapsedRealtime() - start}; native_calls=$calls; valid_answer=${answer.explanation.isNotBlank()}; pss_kib=${Debug.getPss()}")
            assertEquals(1, calls)
            assertTrue(answer.explanation.isNotBlank())
            assertNull(answer.index)
            text.close()

            assertEquals(ImageModelAvailability.VERIFIED, vision.availability())
            val visionInit = SystemClock.elapsedRealtime()
            vision.initialize()
            record("vision_initialize_ms", "${SystemClock.elapsedRealtime() - visionInit}")
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val bytes = try {
                bitmap.eraseColor(Color.RED)
                ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            } finally { bitmap.recycle() }
            val imageScreen = screen.copy(observation = screen.observation.copy(packageName = "fixture.image", elements = emptyList()),
                candidateIndices = emptyList(), planningElements = emptyList())
            val frozen = ScreenPlanner(vision, { error("No text substitution for a valid image result") },
                capture = { error("Browsing must not cause a recapture") }, fresh = { false })
            start = SystemClock.elapsedRealtime()
            val imageAnswer = frozen.answerCaptured("Is this image mostly red or blue?", imageScreen,
                ScreenCapture.Image(ScreenImage(imageScreen.key, bytes, 0, 0, 256, 256, 256, 256)), emptyList())
            record("captured_image_question", "elapsed_ms=${SystemClock.elapsedRealtime() - start}; mode=${imageAnswer.mode}; pss_kib=${Debug.getPss()}; synthetic_answer=${imageAnswer.explanation}")
            assertEquals(AiMode.MULTIMODAL, imageAnswer.mode)
            assertTrue(Regex("\\bred\\b", RegexOption.IGNORE_CASE).containsMatchIn(imageAnswer.explanation))
            assertNull(imageAnswer.index)
            assertTrue(bytes.all { it == 0.toByte() })
        } finally { text.close(); vision.close() }
    }
}
