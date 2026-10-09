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
        resourceId: String? = null,
        parentIndex: Int? = null,
        className: String = "android.widget.TextView",
        top: Int = 20,
        bottom: Int = 70
    ) = AccessibleUiElement(
        text = text,
        contentDescription = null,
        className = className,
        viewId = resourceId,
        clickable = clickable,
        enabled = enabled,
        checked = false,
        scrollable = false,
        left = 10,
        top = top,
        right = 200,
        bottom = bottom,
        parentIndex = parentIndex
    )

    @Test
    fun reportsRawCountsWithoutTreatingUnlabeledContainersAsTitles() {
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
        assertEquals(listOf("Settings", "Display & touch", "Disabled"),
            result.entries.map { it.title })
        assertTrue(result.entries[1].detail.contains("tap target bounds (px): (10, 20)"))
        assertTrue(result.entries[2].detail.contains("enabled: false"))
        assertTrue(result.summary.contains("not measured visual extraction coverage"))
    }

    @Test
    fun groupsTextAndSubtitleUnderTheRealClickableSettingsRow() {
        val snapshot = ScreenObservation("com.android.settings", 2, listOf(
            node(clickable = true, className = "android.widget.LinearLayout", top = 100, bottom = 250),
            node(text = "Network & internet", resourceId = "android:id/title", parentIndex = 0,
                top = 110, bottom = 160),
            node(text = "Mobile, Wi-Fi, hotspot", resourceId = "android:id/summary",
                parentIndex = 0, top = 170, bottom = 230),
            node(text = "Connected devices", clickable = true, top = 260, bottom = 310)
        ))
        val report = UiExtractionDiagnostics.inspect(snapshot)
        assertEquals(listOf("Network & internet", "Connected devices"),
            report.entries.map { it.title })
        assertEquals(0, report.entries[0].index)
        assertTrue(report.entries[0].detail.contains("summary: Mobile, Wi-Fi, hotspot"))
        assertTrue(report.entries[0].detail.contains("label node: #1"))
        assertTrue(report.entries[0].detail.contains("clickable: false"))
        assertTrue(report.entries[0].detail.contains("tap target bounds (px): (10, 100) - (200, 250)"))
        assertEquals(2, report.enabledClickableCount)
        assertEquals(1, report.labeledClickableCount)
    }

    @Test
    fun nestedClickableControlDoesNotLeakItsLabelIntoOuterRow() {
        val snapshot = ScreenObservation("com.android.settings", 3, listOf(
            node(clickable = true, className = "android.widget.LinearLayout", top = 10, bottom = 140),
            node(text = "Dark theme", resourceId = "android:id/title", parentIndex = 0),
            node(text = "Theme switch", clickable = true, parentIndex = 0,
                className = "android.widget.Switch"),
            node(text = "On", parentIndex = 2)
        ))
        val result = UiExtractionDiagnostics.inspect(snapshot)
        assertEquals(listOf("Dark theme", "Theme switch"), result.entries.map { it.title })
        assertFalse(result.entries[0].detail.contains("summary: Theme switch"))
        assertEquals(2, result.entries[1].index)
    }

    @Test
    fun inspectorIsBoundedAndDoesNotInventMissingValues() {
        val snapshot = ScreenObservation("com.android.settings", 4,
            List(40) { node(text = "Option ${it + 1}") })
        val report = UiExtractionDiagnostics.inspect(snapshot)
        assertEquals(40, report.nodeCount)
        assertEquals(20, report.entries.size)
        assertFalse(report.entries.any { it.detail.contains("resource ID: null") })
        assertEquals(0, report.resourceIdCount)
    }
}
