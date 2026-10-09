package com.screenly.app.ai.navigation

/** Frozen before inference; new synthetic variations, not independent recorded Android screens. */
internal val availabilityDiagnosticFixtures: List<NavigationFixture> = run {
    val original = listOf("display-font", "missing-font", "ambiguous", "wifi-unavailable")
        .map { id -> navigationFixtures.first { it.id == id } }
    fun screen(id: String, goal: String, expected: Int?, vararg labels: String) = NavigationFixture(
        id, goal, labels.map { fixtureElement(it) }, labels.indices.toList(), expected
    )
    fun nfc(id: String, goal: String, checked: Boolean, expected: Int?) = NavigationFixture(
        id, goal, listOf(fixtureElement("NFC", kind = "Switch", checked = checked),
            fixtureElement("Bluetooth")), listOf(0, 1), expected
    )
    val font = original.first()
    val variations = listOf(
        font.copy(id = "font-row-order", candidateIndices = listOf(2, 1)),
        font.copy(id = "font-moved-index", elements = listOf(font.elements[0], font.elements[2], font.elements[1]),
            expectedIndex = 2, expectedRuleIndex = 2),
        screen("missing-font-new-labels", "Open font size", null, "Wallpaper", "Storage"),
        screen("duplicate-save", "Save", null, "Save", "Save"),
        NavigationFixture("wifi-unavailable-bluetooth", "Enable Wi-Fi",
            listOf(fixtureElement("Wi-Fi", kind = "Switch", enabled = false), fixtureElement("Bluetooth")),
            listOf(1), null),
        screen("brightness-present", "Open brightness level", 1, "Adaptive brightness", "Brightness level", "Wallpaper"),
        screen("brightness-missing", "Open brightness level", null, "Battery", "Storage"),
        screen("brightness-duplicate", "Open brightness level", null, "Brightness level", "Brightness level"),
        nfc("nfc-enable-off", "Enable NFC", checked = false, expected = 0),
        nfc("nfc-enable-on", "Enable NFC", checked = true, expected = null),
        nfc("nfc-disable-on", "Disable NFC", checked = true, expected = 0),
        nfc("nfc-disable-off", "Disable NFC", checked = false, expected = null)
    ).map { fixture ->
        fixture.copy(elements = fixture.elements.mapIndexed { index, element ->
            element.copy(top = 100 + index * 100, bottom = 180 + index * 100)
        })
    }
    original + variations
}
