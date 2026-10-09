package com.screenly.app

import android.graphics.Rect
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.ai.navigation.ScreenDecisionProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android node extraction tests with synthetic nodes; no user screen or automatic interactions. */
@RunWith(AndroidJUnit4::class)
class ScreenObservationInstrumentationTest {
    @Test fun applicationCannotAccessTheNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.INTERNET"))
    }
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun extract(node: AccessibilityNodeInfo): Pair<List<AccessibleUiElement>, Boolean> {
        val service = ScreenlyAccessibilityService()
        val method = ScreenlyAccessibilityService::class.java.getDeclaredMethod("extractElements", AccessibilityNodeInfo::class.java)
        method.isAccessible = true
        val elements = method.invoke(service, node) as List<AccessibleUiElement>
        val privacy = ScreenlyAccessibilityService::class.java.getDeclaredField("imageAllowed")
        privacy.isAccessible = true
        return elements to privacy.getBoolean(service)
    }

    @Suppress("DEPRECATION")
    @Test fun passwordScreenHasNoAiLabelsAndCannotCapture() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.isVisibleToUser = true
            node.isPassword = true
            node.text = "synthetic-secret"
            val (elements, imageAllowed) = extract(node)
            assertTrue(elements.isEmpty())
            assertFalse(imageAllowed)
        } finally { node.recycle() }
    }

    @Suppress("DEPRECATION")
    @Test fun editableValuesAreRemovedAndDisableImages() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.isVisibleToUser = true
            node.isEditable = true
            node.text = "synthetic-private-input"
            node.contentDescription = "synthetic-private-description"
            val (elements, imageAllowed) = extract(node)
            assertEquals(1, elements.size)
            assertNull(elements[0].text)
            assertNull(elements[0].contentDescription)
            assertTrue(elements[0].editable)
            assertFalse(imageAllowed)
        } finally { node.recycle() }
    }

    @Suppress("DEPRECATION")
    @Test fun nonClickableRangeRetainsItsCapabilitiesAndExactBounds() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.isVisibleToUser = true
            node.isEnabled = true
            node.text = "Font size"
            node.setBoundsInScreen(Rect(10, 30, 180, 80))
            node.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 4f, 2f)
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            val (elements, _) = extract(node)
            val snapshot = guidanceSnapshot(SnapshotKey(1, 1), ScreenObservation("fixture", 1, elements), 200, 200)
            assertEquals(listOf(0), snapshot.candidateIndices)
            assertEquals(10, elements[0].left)
            assertEquals(180, elements[0].right)
            assertEquals(ControlRange(0f, 4f, 2f), elements[0].range)
            assertTrue(ScreenDecisionProtocol.grounded(ScreenDecisionProtocol.parse("ADJUST|0|INCREASE|Increase")!!, snapshot))
        } finally { node.recycle() }
    }

    @Test fun privacyDeniedCaptureDoesNotCallAndroidScreenshotApi() = runBlocking {
        val service = ScreenlyAccessibilityService()
        val snapshot = guidanceSnapshot(SnapshotKey(1, 1), ScreenObservation("fixture", 1, emptyList()), 200, 200)
        assertEquals(ScreenCapture.Failed(CaptureFailure.PRIVACY), ScreenImageCapture(service).capture(snapshot, 200, 200))
    }
}
