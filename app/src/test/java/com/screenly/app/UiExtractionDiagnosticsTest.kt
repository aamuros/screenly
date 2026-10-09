package com.screenly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiExtractionDiagnosticsTest {
    private fun node(
        text: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        resourceId: String? = null
    ) = AccessibleUiElement(
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        viewId = resourceId,
        clickable = clickable,
        enabled = enabled,
        checked = false,
        scrollable = false,
        left = 10,
        top = 20,
        right = 200,
        bottom = 70
    )

    @Test
    fun reportsCountsAndActualMetadataForSettings() {
        val snapshot = ScreenObservation("com.android.settings", 1, listOf(
            node(text = "Settings"),
            node(clickable = true, resourceId = "settings:row"),
            node(text = "Display & touch", clickable = true),
            node(text = "Disabled", clickable = true, enabled = false)
        ))
        val result = UiExtractionDiagnostics.inspect(snapshot)

        assertEquals(4, result.nodeCount)
        assertEquals(3, result.labeledCount)
        assertEquals(2, result.enabledClickableCount)
        assertEquals(1, result.labeledClickableCount)
        assertEquals(1, result.resourceIdCount)
        assertEquals(4, result.entries.size)
        assertTrue(result.entries[2].detail.contains("bounds (px): (10, 20)"))
        assertTrue(result.summary.contains("not measured visual extraction coverage"))
    }

    @Test
    fun inspectorIsBoundedAndDoesNotInventMissingValues() {
        val snapshot = ScreenObservation("com.android.settings", 2,
            List(40) { node(text = "Option ${it + 1}") })
        val report = UiExtractionDiagnostics.inspect(snapshot)
        assertEquals(40, report.nodeCount)
        assertEquals(20, report.entries.size)
        assertFalse(report.entries.any { it.detail.contains("resource ID: null") })
        assertEquals(0, report.resourceIdCount)
    }
}
