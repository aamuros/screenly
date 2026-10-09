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
}
