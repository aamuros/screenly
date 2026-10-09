package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibleScreenAssistantTest {
    private fun element(name: String, checked: Boolean = false, type: String = "android.widget.TextView") =
        AccessibleUiElement(name, null, type, null, true, true, checked, false, 1, 5, 300, 80)

    @Test fun suggestsVisibleRouteForDarkModeWithoutClaimingVision() {
        val view = ScreenObservation("com.android.settings", 1, listOf(element("Display & touch")))
        val guidance = AccessibleScreenAssistant.begin("Enable dark mode", view)
        assertTrue(guidance.instruction.contains("Display & touch"))
        assertTrue(AccessibleScreenAssistant.ask("How do I enable dark mode?", view).contains("not visually analyzed"))
    }

    @Test fun doesNotMarkUnchangedScreenComplete() {
        val view = ScreenObservation("com.android.settings", 1, listOf(element("Dark theme")))
        val start = AccessibleScreenAssistant.begin("Enable dark mode", view)
        val checked = AccessibleScreenAssistant.check(start, view)
        assertEquals(AccessibleScreenAssistant.GuidancePhase.NEEDS_ACTION, checked.phase)
        assertEquals(1, checked.step)
    }

    @Test fun verifiesRealSwitchStateChange() {
        val off = ScreenObservation("com.android.settings", 1,
            listOf(element("Dark theme", false, "android.widget.Switch")))
        val on = ScreenObservation("com.android.settings", 1,
            listOf(element("Dark theme", true, "android.widget.Switch")))
        val state = AccessibleScreenAssistant.check(
            AccessibleScreenAssistant.begin("Enable dark mode", off), on)
        assertEquals(AccessibleScreenAssistant.GuidancePhase.COMPLETED, state.phase)
    }

    @Test fun ambiguousTargetsAbstain() {
        val view = ScreenObservation("com.android.settings", 1,
            listOf(element("Dark mode"), element("Dark theme")))
        assertEquals(null, AccessibleScreenAssistant.begin("Enable dark mode", view).targetIndex)
    }
}
