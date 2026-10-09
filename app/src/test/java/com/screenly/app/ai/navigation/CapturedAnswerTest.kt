package com.screenly.app.ai.navigation

import com.screenly.app.*
import com.screenly.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CapturedAnswerTest {
    private fun snapshot(imageAllowed: Boolean = true) = guidanceSnapshot(SnapshotKey(1, 1),
        ScreenObservation("original.app", 1, listOf(AccessibleUiElement("Upload photo", null, "Button", null,
            true, true, false, false, 0, 0, 100, 100)), imageAllowed), 200, 200)

    private class Vision(private val state: ImageModelAvailability) : LocalMultimodalInference {
        var calls = 0
        var observedBytes: List<Byte>? = null
        override fun availability() = state
        override suspend fun initialize() {}
        override suspend fun generate(prompt: String, image: ByteArray?): String {
            calls++
            observedBytes = image?.toList()
            assertTrue(prompt.length < 3100)
            return "Use the Upload photo button on the captured screen."
        }
        override suspend fun close() {}
    }

    @Test fun imageAnswerSurvivesBrowsingAndNeverRecapturesOrHighlights() = runBlocking {
        val screen = snapshot()
        val bytes = byteArrayOf(1, 2, 3)
        val vision = Vision(ImageModelAvailability.VERIFIED)
        val planner = ScreenPlanner(vision, { error("No text pass after a valid image answer") },
            capture = { error("Historical answers must not recapture the current app") }, fresh = { false })
        val result = planner.answerCaptured("How do I upload?", screen,
            ScreenCapture.Image(ScreenImage(screen.key, bytes, 0, 0, 100, 100, 100, 100)), emptyList())
        assertEquals(AiMode.MULTIMODAL, result.mode)
        assertEquals(1, vision.calls)
        assertEquals(listOf<Byte>(1, 2, 3), vision.observedBytes)
        assertNull(result.index)
        assertTrue(result.explanation.contains("captured screen"))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun textQuestionUsesOneModelPassEvenAfterTheLiveRevisionChanges() = runBlocking {
        var calls = 0
        val planner = ScreenPlanner(Vision(ImageModelAvailability.MISSING), {
            calls++
            "The captured screen lets you upload a photo."
        }, capture = { error("No live capture") }, fresh = { false })
        val result = planner.answerCaptured("Explain", snapshot(), ScreenCapture.Failed(CaptureFailure.PRIVACY), emptyList())
        assertEquals(1, calls)
        assertEquals(AiMode.TEXT_ONLY, result.mode)
        assertEquals("missing", result.visionStatus)
        assertNull(result.index)
        assertTrue(result.explanation.isNotEmpty())
    }

    @Test fun rejectsMismatchedFramesAndSensitiveEmptySnapshotsBeforeAnyModelCall() = runBlocking {
        val vision = Vision(ImageModelAvailability.VERIFIED)
        val planner = ScreenPlanner(vision, { error("No model on rejected data") }, capture = { error("capture") }, fresh = { true })
        val screen = snapshot()
        val bytes = byteArrayOf(7)
        val result = planner.answerCaptured("Explain", screen,
            ScreenCapture.Image(ScreenImage(SnapshotKey(1, 2), bytes, 0, 0, 100, 100, 100, 100)), emptyList())
        assertEquals("stale", result.visionStatus)
        assertTrue(bytes.all { it == 0.toByte() })
        val sensitive = screen.copy(observation = screen.observation.copy(elements = emptyList(), imageAllowed = false))
        assertEquals("input_rejected", planner.answerCaptured("Explain", sensitive,
            ScreenCapture.Failed(CaptureFailure.PRIVACY), emptyList()).visionStatus)
        assertEquals(0, vision.calls)
    }

    @Test fun cancellationWhileQueuedErasesTheOwnedImage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val planner = ScreenPlanner(Vision(ImageModelAvailability.MISSING), {
            entered.complete(Unit)
            release.await()
            "A captured screen."
        }, capture = { error("capture") }, fresh = { false })
        val screen = snapshot()
        val first = async { planner.answerCaptured("Explain", screen, ScreenCapture.Failed(CaptureFailure.PRIVACY), emptyList()) }
        entered.await()
        val bytes = byteArrayOf(9, 8)
        val queued = launch { planner.answerCaptured("Explain", screen,
            ScreenCapture.Image(ScreenImage(screen.key, bytes, 0, 0, 100, 100, 100, 100)), emptyList()) }
        yield()
        queued.cancelAndJoin()
        assertTrue(bytes.all { it == 0.toByte() })
        release.complete(Unit)
        assertNull(first.await().index)
    }

    @Test fun malformedAnswersCannotAuthorizeCoordinatesOrTargets() = runBlocking {
        val planner = ScreenPlanner(Vision(ImageModelAvailability.MISSING), { "NAVIGATE|0|-|Tap" },
            capture = { error("capture") }, fresh = { false })
        val result = planner.answerCaptured("Explain", snapshot(), ScreenCapture.Failed(CaptureFailure.PRIVACY), emptyList())
        assertNull(result.index)
        assertEquals("", result.explanation)
    }
}
