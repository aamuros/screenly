package com.screenly.app.ai.navigation

import com.screenly.app.*
import com.screenly.app.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreenPlannerTest {
    private fun element(label: String = "Upload", clickable: Boolean = true) =
        AccessibleUiElement(label, null, "Control", null, clickable, true, false, false, 0, 0, 100, 100)
    private fun snapshot(vararg elements: AccessibleUiElement) = guidanceSnapshot(
        SnapshotKey(1, 1), ScreenObservation("unfamiliar.app", 1, elements.toList(), imageAllowed = true), 200, 200)
    private class Vision(private val state: ImageModelAvailability, private val output: String = "NAVIGATE|0|-|Open Upload") : LocalMultimodalInference {
        var calls = 0
        override fun availability() = state
        override suspend fun initialize() {}
        override suspend fun generate(prompt: String, image: ByteArray?): String { check(image != null); calls++; return output }
        override suspend fun close() {}
    }

    @Test fun strictWireRejectsMalformedAndUnboundActions() {
        listOf("NAVIGATE|0|-|Open", "ADJUST|1|INCREASE|Increase", "TOGGLE|2|ON|Enable",
            "SCROLL|3|FORWARD|Scroll", "DONE|-|-|Visible evidence", "UNCERTAIN|-|-|Unknown").forEach { assertNotNull(it, ScreenDecisionProtocol.parse(it)) }
        listOf("NAVIGATE|00|-|Open", "ADJUST|1|-|Adjust", "DONE|0|-|Done", "TOGGLE|-|ON|Enable",
            "NAVIGATE|0|-|Open\nextra", "```NAVIGATE|0|-|Open```", "NAVIGATE|0|-|Open|extra", "{\"id\":0}",
            "NAVIGATE|0|-Upload photo|The user wants to upload a photo.")
            .forEach { assertNull(it, ScreenDecisionProtocol.parse(it)) }
    }

    @Test fun groundingChecksCapabilitiesRangeEndpointsStateAndMembership() {
        val slider = element("Font", false).copy(range = ControlRange(0f, 4f, 2f), actions = listOf(16908349))
        val toggle = element("Theme").copy(checkable = true)
        val scroll = element("List", false).copy(scrollable = true, actions = listOf(4096))
        val screen = snapshot(element(), slider, toggle, scroll)
        fun ground(wire: String) = ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse(wire)!!, screen)
        assertTrue(ground("ADJUST|1|INCREASE|Increase"))
        assertTrue(ground("TOGGLE|2|ON|Enable"))
        assertTrue(ground("SCROLL|3|FORWARD|Scroll"))
        listOf("NAVIGATE|1|-|Open", "ADJUST|0|INCREASE|Increase", "TOGGLE|2|OFF|Disable",
            "SCROLL|3|BACKWARD|Scroll", "NAVIGATE|99|-|Open").forEach { assertFalse(it, ground(it)) }
        assertFalse(ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse("ADJUST|0|INCREASE|Increase")!!,
            snapshot(slider.copy(range = ControlRange(0f, 4f, 4f)))))
        assertFalse(ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse("NAVIGATE|0|-|Open")!!,
            snapshot(element().copy(enabled = false))))
        val backwardOnly = snapshot(slider.copy(actions = listOf(8192)))
        assertFalse(ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse("ADJUST|0|INCREASE|Increase")!!, backwardOnly))
        assertTrue(ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse("ADJUST|0|DECREASE|Decrease")!!, backwardOnly))
    }

    @Test fun realTextFallbackIsExplicitAndNeverInventsARuleStep() = runBlocking {
        val vision = Vision(ImageModelAvailability.MISSING)
        var calls = 0
        val planner = ScreenPlanner(vision, { calls++; "0" },
            capture = { error("Missing vision cannot capture") }, fresh = { true })
        val result = planner.plan("Upload a photo", snapshot(element()), emptyList())
        assertEquals(AiMode.TEXT_ONLY, result.mode)
        assertEquals(0, result.index)
        assertEquals(1, calls)
        assertEquals(0, vision.calls)
        val broken = ScreenPlanner(vision, { "bad output" }, capture = { error("capture") }, fresh = { true })
        assertEquals(NavigationAction.UNCERTAIN, broken.plan("Open Upload", snapshot(element()), emptyList()).action)
        val unavailable = ScreenPlanner(vision, { throw java.io.IOException("private text") }, capture = { error("capture") }, fresh = { true })
        assertEquals(AiMode.UNAVAILABLE, unavailable.plan("Upload", snapshot(element()), emptyList()).mode)
    }

    @Test fun fusedImageIsBoundToSnapshotAndErasedEvenWhenResultIsInvalid() = runBlocking {
        val screen = snapshot(element())
        val bytes = byteArrayOf(1, 2, 3)
        val vision = Vision(ImageModelAvailability.VERIFIED, "NAVIGATE|99|-|Open")
        val planner = ScreenPlanner(vision, { error("No rule fallback after invalid vision") },
            capture = { ScreenCapture.Image(ScreenImage(screen.key, bytes, 0, 0, 100, 100, 100, 100)) }, fresh = { true })
        val result = planner.plan("Upload", screen, emptyList())
        assertEquals(NavigationAction.UNCERTAIN, result.action)
        assertEquals(AiMode.MULTIMODAL, result.mode)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun screenChangeDuringInferenceRejectsLateAnswer() = runBlocking {
        var fresh = true
        val planner = ScreenPlanner(Vision(ImageModelAvailability.UNVERIFIED), { fresh = false; "NAVIGATE|0|-|Open" },
            capture = { error("Unverified model cannot capture") }, fresh = { fresh })
        assertNull(planner.plan("Upload", snapshot(element()), emptyList()).index)
    }

    @Test fun categoricalTextOutputBindsOnlyToCapturedCapabilityOptions() {
        val screen = snapshot(element("Heading", false), element("Upload"))
        val options = TextActionProtocol.options(screen)
        assertEquals(1, TextActionProtocol.parse("0", options)!!.index)
        listOf("00", "0 because", "-1", "1", "999").forEach { assertNull(it, TextActionProtocol.parse(it, options)) }
        assertEquals(NavigationAction.DONE, TextActionProtocol.parse("DONE", options)!!.action)
    }

    @Test fun explainCanUseARealTextAnswerWithoutInventingATarget() = runBlocking {
        var calls = 0
        val planner = ScreenPlanner(Vision(ImageModelAvailability.MISSING), {
            calls++
            if (calls == 1) "invalid choice" else "This screen offers Upload and Cancel controls."
        }, capture = { error("No image model") }, fresh = { true })
        val result = planner.plan("Explain this screen", snapshot(element()), emptyList(), question = true)
        assertEquals(2, calls)
        assertEquals(NavigationAction.UNCERTAIN, result.action)
        assertNull(result.index)
        assertEquals("This screen offers Upload and Cancel controls.", result.explanation)
        assertEquals(AiMode.TEXT_ONLY, result.mode)
    }

    @Test fun pixelChangesOnAnIdenticalTreeInvalidateVisionAndEraseBothCaptures() = runBlocking {
        val screen = snapshot(element())
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4)
        var captures = 0
        val planner = ScreenPlanner(Vision(ImageModelAvailability.VERIFIED), { error("Vision result cannot become a rule") },
            capture = { ScreenCapture.Image(ScreenImage(screen.key, if (captures++ == 0) first else second, 0, 0, 100, 100, 100, 100)) },
            fresh = { true })
        val result = planner.plan("Upload", screen, emptyList())
        assertEquals(NavigationAction.UNCERTAIN, result.action)
        assertEquals("stale", result.visionStatus)
        assertTrue(first.all { it == 0.toByte() } && second.all { it == 0.toByte() })
    }
}
