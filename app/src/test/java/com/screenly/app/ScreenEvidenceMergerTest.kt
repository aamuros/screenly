package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class ScreenEvidenceMergerTest {
    private fun item(text: String? = null, clickable: Boolean = false,
        left: Int = 0, top: Int = 0, right: Int = 300, bottom: Int = 80,
        cls: String = "android.widget.ImageButton"): AccessibleUiElement =
        AccessibleUiElement(text, null, cls, null, clickable, true, false, false,
            left, top, right, bottom)

    @Test fun associatesOcrOnlyWithUniqueRealClickableTarget() {
        val snapshot = ScreenObservation("app", 1, listOf(
            item(right = 1000, bottom = 1800, cls = "android.view.View"),
            item(clickable = true, left = 50, top = 50, right = 250, bottom = 120)
        ))
        val merged = ScreenEvidenceMerger.augment(snapshot, listOf(
            OcrTextLine("Search", 70, 70, 180, 110)
        ))
        assertEquals(null, snapshot.elements[1].text)
        assertEquals("Search", merged.elements[1].text)
        assertTrue(merged.elements[1].clickable)
        assertEquals(merged.elements[1].left, snapshot.elements[1].left)
    }

    @Test fun refusesAmbiguousOverlappingTargets() {
        val snapshot = ScreenObservation("app", 1, listOf(
            item(right = 1000, bottom = 1800, cls = "android.view.View"),
            item(clickable = true, left = 0, top = 0, right = 300, bottom = 100),
            item(clickable = true, left = 40, top = 20, right = 200, bottom = 80)
        ))
        val merged = ScreenEvidenceMerger.augment(snapshot, listOf(
            OcrTextLine("Continue", 50, 30, 140, 70)
        ))
        assertEquals(snapshot, merged)
    }

    @Test fun cannotOverwriteRealAccessibilityLabels() {
        val snapshot = ScreenObservation("app", 1, listOf(
            item(right = 1000, bottom = 1800, cls = "android.view.View"),
            item(text = "Real label", clickable = true, left = 0, top = 0, right = 300, bottom = 100)
        ))
        val merged = ScreenEvidenceMerger.augment(snapshot, listOf(
            OcrTextLine("Different label", 10, 10, 180, 50)
        ))
        assertEquals("Real label", merged.elements[1].text)
    }
}
