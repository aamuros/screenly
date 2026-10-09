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
        val view = ScreenObservation("com.android.settings", 1, listOf(element("Dark theme", type = "android.widget.Switch")))
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

    @Test fun changesAreRecheckedOneStepAtATime() {
        val settings = ScreenObservation("com.android.settings", 1,
            listOf(element("Display & touch")))
        val display = ScreenObservation("com.android.settings", 2,
            listOf(element("Dark theme", type = "android.widget.Switch")))
        val first = AccessibleScreenAssistant.begin("Enable dark mode", settings)
        val second = AccessibleScreenAssistant.check(first, display)
        assertEquals(2, second.step)
        assertTrue(second.instruction.contains("Dark theme"))
        assertEquals(AccessibleScreenAssistant.GuidancePhase.NEEDS_ACTION, second.phase)
    }

    @Test fun navigationDoesNotClaimCompletionWithoutSwitchEvidence() {
        val startScreen = ScreenObservation("com.android.settings", 1,
            listOf(element("Display & touch")))
        val nextScreen = ScreenObservation("com.android.settings", 2,
            listOf(element("Colors")))
        val result = AccessibleScreenAssistant.check(
            AccessibleScreenAssistant.begin("Enable dark mode", startScreen), nextScreen)
        assertEquals(AccessibleScreenAssistant.GuidancePhase.NEEDS_REVIEW, result.phase)
    }

    @Test fun supportsParentRowsLabeledByChildText() {
        val row = AccessibleUiElement(null, null, "android.widget.LinearLayout", null,
            true, true, false, false, 0, 0, 300, 80)
        val title = AccessibleUiElement("Display & touch", null, "android.widget.TextView", null,
            false, true, false, false, 12, 14, 180, 40)
        val view = ScreenObservation("com.android.settings", 1, listOf(row, title))
        val result = AccessibleScreenAssistant.begin("Enable dark mode", view)
        assertEquals(0, result.targetIndex)
        assertTrue(result.instruction.contains("Display & touch"))
    }

    @Test fun ambiguousTargetsAbstain() {
        val view = ScreenObservation("com.android.settings", 1,
            listOf(element("Dark mode"), element("Dark theme")))
        assertEquals(null, AccessibleScreenAssistant.begin("Enable dark mode", view).targetIndex)
    }

    @Test fun identicalLabelsRemainAmbiguousIncludingBeyondTwelveControls() {
        val view = ScreenObservation("com.android.settings", 1,
            listOf(element("Dark theme")) + (1..12).map { element("Other $it") } + element("Dark theme"))
        assertEquals(null, AccessibleScreenAssistant.begin("Open dark theme", view).targetIndex)
    }

    @Test fun satisfiedUnknownAndOpenTogglesCannotBeSuggested() {
        for ((goal, item) in listOf(
            "Enable dark mode" to element("Dark theme", true, "android.widget.Switch"),
            "Disable Wi-Fi" to element("Wi-Fi", false, "android.widget.Switch"),
            "Enable Wi-Fi" to element("Wi-Fi"),
            "Open dark theme" to element("Dark theme", false, "android.widget.Switch")
        )) assertEquals(goal, null, AccessibleScreenAssistant.begin(goal,
            ScreenObservation("com.android.settings", 1, listOf(item))).targetIndex)
    }

    @Test fun completionRequiresSameAppWindowAndUniqueToggleEvidence() {
        val off = ScreenObservation("com.android.settings", 1, listOf(element("Dark theme", false, "android.widget.Switch")))
        val start = AccessibleScreenAssistant.begin("Enable dark mode", off)
        val on = element("Dark theme", true, "android.widget.Switch")
        for (next in listOf(off.copy(packageName = "other.app", elements = listOf(on)),
            off.copy(windowId = 2, elements = listOf(on)), off.copy(elements = listOf(on, on)),
            off.copy(elements = listOf(on.copy(className = "android.widget.TextView"))))) {
            assertTrue(AccessibleScreenAssistant.check(start, next).phase != AccessibleScreenAssistant.GuidancePhase.COMPLETED)
        }
    }

    @Test fun deactivateIsNotActivate() {
        val on = ScreenObservation("com.android.settings", 1, listOf(element("Wi-Fi", true, "android.widget.Switch")))
        val state = AccessibleScreenAssistant.check(AccessibleScreenAssistant.begin("Deactivate Wi-Fi", on),
            on.copy(elements = listOf(element("Wi-Fi", false, "android.widget.Switch"))))
        assertEquals(AccessibleScreenAssistant.GuidancePhase.COMPLETED, state.phase)
    }
}
