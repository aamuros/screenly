package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class GuidanceSnapshotTest {
    private fun element(text: String?, clickable: Boolean, parent: Int? = null, id: String? = null) = AccessibleUiElement(
        text, null, "android.widget.LinearLayout", id, clickable, true, false, false, 0, 100, 1080, 200, parent
    )

    @Test fun copiedRelationshipsLabelContainersWithoutChangingBoundsOrIndices() {
        val elements = mutableListOf(element(null, true), element("Display & touch", false, 0, "android:id/title"),
            element("Dark theme, font size, touch", false, 0, "android:id/summary"), element("Unrelated", false))
        val snapshot = guidanceSnapshot(SnapshotKey(1, 1), ScreenObservation("com.android.settings", 1, elements), 1080, 1920)
        elements.clear()
        assertEquals(listOf(0), snapshot.candidateIndices)
        assertNull(snapshot.observation.elements[0].text)
        assertEquals("Display & touch", snapshot.planningElements[0].text)
        assertEquals("Dark theme, font size, touch", snapshot.planningElements[0].contentDescription)
        assertEquals(snapshot.observation.elements[0].left, snapshot.planningElements[0].left)
        assertEquals(4, snapshot.observation.elements.size)
    }

    @Test fun geometricContainmentDoesNotInventAParentAndOffscreenControlsAreExcluded() {
        val elements = listOf(element(null, true), element("Font size", false), element("Sound", true).copy(left = 1100, right = 1300))
        val snapshot = guidanceSnapshot(SnapshotKey(1, 1), ScreenObservation("app", 1, elements), 1080, 1920)
        assertTrue(snapshot.candidateIndices.isEmpty())
        assertNull(snapshot.planningElements[0].text)
    }

    @Test fun highlightingUsesCurrentOriginalBoundsAndRejectsStaleOrIneligibleIndices() {
        val originals = listOf(element("Heading", false), element("Font size", true).copy(top = 400, bottom = 500))
        val current = guidanceSnapshot(SnapshotKey(5, 8), ScreenObservation("app", 2, originals), 1080, 1920)
        assertEquals(originals[1], validatedGuidanceTarget(current, current, "Open font size", 1, emptySet()))
        for (index in listOf(null, -1, 0, 99)) assertNull(validatedGuidanceTarget(current, current, "Open font size", index, emptySet()))
        for (key in listOf(SnapshotKey(5, 9), SnapshotKey(6, 8))) {
            assertNull(validatedGuidanceTarget(current, current.copy(key = key), "Open font size", 1, emptySet()))
        }
        val moved = guidanceSnapshot(current.key, current.observation.copy(elements = listOf(originals[0], originals[1].copy(top = 600))), 1080, 1920)
        assertNull(validatedGuidanceTarget(current, moved, "Open font size", 1, emptySet()))
        val offscreen = guidanceSnapshot(current.key, current.observation, 1080, 300)
        assertNull(validatedGuidanceTarget(current, offscreen, "Open font size", 1, emptySet()))
    }
}
