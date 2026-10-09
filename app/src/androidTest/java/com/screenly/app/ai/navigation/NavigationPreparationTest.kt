package com.screenly.app.ai.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.screenly.app.AccessibleUiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks Kotlin helpers on Android; this test does not call the model or shared Planner API. */
@RunWith(AndroidJUnit4::class)
class NavigationPreparationTest {
    @Test
    fun promptParserValidationAndRulesRunOnAndroid() {
        val elements = listOf(
            element("Display", clickable = false), element("Font size"), element("Display size")
        )
        val candidates = listOf(1, 2)
        assertNotNull(NavigationProtocol.buildPrompt("Open font size", elements, candidates))
        assertEquals(NavigationResponse(1), NavigationProtocol.parseResponse("TAP:1"))
        assertTrue(NavigationProtocol.validTarget(1, elements, candidates))
        assertFalse(NavigationProtocol.validTarget(0, elements, candidates))
        assertFalse(NavigationProtocol.validTarget(99, elements, candidates))
        assertNull(NavigationProtocol.parseResponse("TAP:1 because it matches"))
        assertNull(NavigationRules.select("Open font size", elements, candidates))
        assertNull(NavigationRules.select("Open Wi-Fi", elements, candidates))
        assertEquals(NavigationResponse(null), NavigationProtocol.parseResponse("NONE"))
    }

    private fun element(label: String, clickable: Boolean = true) = AccessibleUiElement(
        text = label, contentDescription = null, className = "android.widget.Button", viewId = null,
        clickable = clickable, enabled = true, checked = false, scrollable = false,
        left = 0, top = 100, right = 1080, bottom = 200
    )
}
