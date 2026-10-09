package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenObservationStateTest {
    private val element = AccessibleUiElement(
        "Settings row", null, "android.widget.Button", "android:id/title",
        true, true, false, false, 20, 100, 300, 180
    )
    private val screen = ScreenObservation("com.android.settings", 10, listOf(element))

    @Test
    fun duplicateSnapshotsPreserveAValidSelection() {
        val state = ScreenObservationState()
        assertTrue(state.update(screen))
        val revision = state.revision
        assertFalse(state.update(screen.copy(elements = screen.elements.toList())))
        assertTrue(state.canSelect(screen, revision, element))
    }

    @Test
    fun appWindowGeometryAndStateChangesRejectOldCoordinates() {
        val changedScreens = listOf(
            screen.copy(packageName = "another.app"),
            screen.copy(windowId = 11),
            screen.copy(elements = listOf(element.copy(top = 200, bottom = 280))),
            screen.copy(elements = listOf(element.copy(enabled = false))),
            screen.copy(elements = emptyList())
        )
        changedScreens.forEach { next ->
            val state = ScreenObservationState()
            state.update(screen)
            val revision = state.revision
            assertTrue(state.update(next))
            assertFalse(state.canSelect(screen, revision, element))
        }
    }

    @Test
    fun scrollOrNavigationInvalidatesEvenAnIdenticalSnapshot() {
        val state = ScreenObservationState()
        state.update(screen)
        val revision = state.revision
        state.invalidateSelection()
        state.update(screen)
        assertFalse(state.canSelect(screen, revision, element))
    }

    @Test
    fun returningToSameScreenDoesNotReviveOldPickerCallbacks() {
        val state = ScreenObservationState()
        state.update(screen)
        val revision = state.revision
        state.update(screen.copy(windowId = 11))
        state.update(screen)
        assertFalse(state.canSelect(screen, revision, element))
    }

    @Test
    fun disconnectOrLockClearsSnapshotAndRejectsCallbacksAfterReconnect() {
        val state = ScreenObservationState()
        state.update(screen)
        val revision = state.revision
        state.clear()
        state.clear()
        assertNull(state.snapshot)
        assertFalse(state.canSelect(screen, revision, element))
        state.update(screen)
        assertFalse(state.canSelect(screen, revision, element))
    }

    @Test
    fun cannotSelectAnAbsentDisabledNonClickableOrEmptyElement() {
        val originalState = ScreenObservationState()
        originalState.update(screen)
        assertFalse(originalState.canSelect(screen, originalState.revision, element.copy(text = "Absent")))
        listOf(
            element.copy(enabled = false),
            element.copy(clickable = false),
            element.copy(right = element.left)
        ).forEach { candidate ->
            val state = ScreenObservationState()
            val invalidScreen = screen.copy(elements = listOf(candidate))
            state.update(invalidScreen)
            assertFalse(state.canSelect(invalidScreen, state.revision, candidate))
        }
    }

    @Test
    fun coordinateValidationAllowsPartialVisibilityAndRejectsInvalidBounds() {
        assertTrue(element.intersectsScreen(1080, 1920))
        assertTrue(element.copy(left = -20, top = -10).intersectsScreen(1080, 1920))
        assertFalse(element.copy(left = 300, right = 20).intersectsScreen(1080, 1920))
        assertFalse(element.copy(bottom = 100).intersectsScreen(1080, 1920))
        assertFalse(element.copy(left = 1080, right = 1200).intersectsScreen(1080, 1920))
        assertFalse(element.copy(top = 1920, bottom = 2000).intersectsScreen(1080, 1920))
        assertFalse(element.copy(left = -300, right = 0).intersectsScreen(1080, 1920))
        assertFalse(element.intersectsScreen(0, 1920))
    }

    @Test
    fun captureSchedulingHasABoundedDeadlineWithoutStarvation() {
        assertEquals(0L, observationDelayMillis(null, 1000))
        assertEquals(250L, observationDelayMillis(1000, 1000))
        assertEquals(150L, observationDelayMillis(1000, 1100))
        assertEquals(0L, observationDelayMillis(1000, 1250))
        assertEquals(0L, observationDelayMillis(1000, 1400))
        assertEquals(250L, observationDelayMillis(1000, 900))
    }
}
