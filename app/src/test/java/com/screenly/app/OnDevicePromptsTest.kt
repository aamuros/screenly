package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class OnDevicePromptsTest {
    private fun screen() = ScreenObservation("com.android.settings", 1, listOf(
        AccessibleUiElement("Wi-Fi", null, "Button", null, true, true, false, false, 0, 0, 50, 50)
    ))

    @Test fun boundedPromptUsesOnlyAccessibleControlLabels() {
        val result = OnDevicePrompts.ask("a".repeat(4000), screen())
        assertTrue(result.length <= 1000)
        assertTrue(result.contains("[0] Wi-Fi"))
        assertFalse(result.contains("a".repeat(200)))
    }

    @Test fun explanationDisclosesTheMissingVisionCapability() {
        assertTrue(OnDevicePrompts.explain(screen()).contains("Never invent unseen controls"))
    }
}
