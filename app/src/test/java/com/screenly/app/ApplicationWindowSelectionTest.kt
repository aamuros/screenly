package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationWindowSelectionTest {
    private fun app(
        id: Int,
        active: Boolean = false,
        focused: Boolean = false
    ) = ApplicationWindowCandidate(id, true, active, focused)

    private fun assistantOverlay(id: Int) =
        ApplicationWindowCandidate(id, false, true, true)

    @Test
    fun explainsSettingsWithoutLosingWindowWhenAssistantTakesFocus() {
        val windows = listOf(app(7), assistantOverlay(8))
        assertEquals(7, selectApplicationWindowId(windows, 7, assistantPanelOpen = true))
    }

    @Test
    fun activeAndFocusedApplicationsTakePriorityOverCachedWindow() {
        assertEquals(11, selectApplicationWindowId(
            listOf(app(7), app(11, active = true), assistantOverlay(8)),
            7, assistantPanelOpen = true
        ))
        assertEquals(12, selectApplicationWindowId(
            listOf(app(7), app(12, focused = true), assistantOverlay(8)),
            7, assistantPanelOpen = true
        ))
    }

    @Test
    fun doesNotUseCachedAppWithoutOpenPanelOrMatchingWindow() {
        val windows = listOf(app(7), assistantOverlay(8))
        assertNull(selectApplicationWindowId(windows, 7, assistantPanelOpen = false))
        assertNull(selectApplicationWindowId(windows, 9, assistantPanelOpen = true))
        assertNull(selectApplicationWindowId(windows, null, assistantPanelOpen = true))
    }

    @Test
    fun neverInspectsOverlayEvenWhenItsIdMatchesPreviousObservation() {
        assertNull(selectApplicationWindowId(
            listOf(assistantOverlay(8)), 8, assistantPanelOpen = true
        ))
    }
}
