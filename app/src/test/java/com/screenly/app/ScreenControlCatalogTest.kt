package com.screenly.app

import org.junit.Assert.*
import org.junit.Test

class ScreenControlCatalogTest {
    private fun node(text: String? = null, parent: Int? = null, clickable: Boolean = false,
        checked: Boolean = false, cls: String = "android.widget.TextView",
        id: String? = null): AccessibleUiElement =
        AccessibleUiElement(text, null, cls, id, clickable, true, checked,
            false, 10, 20, 200, 90, parent)

    @Test fun groupsRealClickableSettingsRow() {
        val s = ScreenObservation("com.android.settings", 1, listOf(
            node(clickable = true, cls = "android.widget.LinearLayout"),
            node("Network & internet", 0, id = "android:id/title"),
            node("Mobile, Wi-Fi, hotspot", 0, id = "android:id/summary")
        ))
        val item = ScreenControlCatalog.controls(s).single()
        assertEquals(0, item.index)
        assertEquals("Network & internet", item.label)
        assertEquals("Mobile, Wi-Fi, hotspot", item.subtitle)
    }

    @Test fun distinguishesClickableRowAndNestedSwitch() {
        val s = ScreenObservation("com.android.settings", 1, listOf(
            node(clickable = true, cls = "android.widget.LinearLayout"),
            node("Dark theme", 0, id = "android:id/title"),
            node("Dark theme", 0, clickable = true, cls = "android.widget.Switch", checked = true),
            node("On", 2)
        ))
        val controls = ScreenControlCatalog.controls(s)
        assertEquals(listOf(ScreenControl.Type.ROW, ScreenControl.Type.TOGGLE),
            controls.map { it.type })
        assertTrue(controls.last().checked)
        assertEquals(2, controls.last().index)
        assertTrue(AccessibleScreenAssistant.ask("Enable dark mode", s).contains("cannot safely"))
        assertTrue(AccessibleScreenAssistant.ask("Open dark theme settings", s).contains("found"))
    }

    @Test fun retainsDuplicateLabelsAsAmbiguous() {
        val s = ScreenObservation("com.android.settings", 1, listOf(
            node("Continue", clickable = true), node("Continue", clickable = true)))
        assertEquals(2, ScreenControlCatalog.controls(s).size)
        assertTrue(AccessibleScreenAssistant.ask("Continue", s).contains("cannot safely"))
    }

    @Test fun acceptsNaturalDarkModePhraseWhenSwitchIsOff() {
        val off = ScreenObservation("com.android.settings", 1, listOf(
            node("Dark theme", clickable = true, cls = "android.widget.Switch", checked = false)
        ))
        assertTrue(AccessibleScreenAssistant.ask("Make the screen dark", off).contains("found"))
        val on = off.copy(elements = off.elements.map { it.copy(checked = true) })
        assertTrue(AccessibleScreenAssistant.ask("Make the screen dark", on).contains("cannot safely"))
    }

    @Test fun detectsOppositeSwitchState() {
        val off = ScreenObservation("com.android.settings", 1, listOf(
            node("Dark theme", clickable = true, cls = "android.widget.Switch")))
        assertTrue(AccessibleScreenAssistant.ask("Enable dark mode", off).contains("found"))
        val on = off.copy(elements = off.elements.map { it.copy(checked = true) })
        assertTrue(AccessibleScreenAssistant.ask("Disable dark mode", on).contains("found"))
    }
}
