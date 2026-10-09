package com.screenly.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in current-screen capture. User must enable Screenly and open a non-sensitive app. */
@RunWith(AndroidJUnit4::class)
class LiveScreenCaptureTest {
    @Test fun captureIsGroundedAndReleasedWithoutPersistingPixels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e currentScreenSmoke true on a user-authorized non-sensitive screen.", args.getString("currentScreenSmoke") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AccessibilityManager::class.java)
        fun connected() = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        // Starting instrumentation restarts the target process. Android may mark its
        // accessibility service crashed; let the user manually reconnect during the test.
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
        if (!connected()) instrumentation.sendStatus(0, Bundle().apply {
            putString("manual_service_reconnect", "Waiting up to 120 seconds for user to reconnect Screenly after instrumentation restart")
        })
        while (!connected() && android.os.SystemClock.elapsedRealtime() < deadline) delay(250)
        val enabled = connected()
        assertTrue("Screenly must be manually enabled before live capture testing.", enabled)
        var service: ScreenlyAccessibilityService? = null
        var snapshot: GuidanceSnapshot? = null
        var overlay: ScreenlyOverlay? = null
        val hidden = mutableListOf<View>()
        fun current(): GuidanceSnapshot? {
            var result: GuidanceSnapshot? = null
            instrumentation.runOnMainSync {
                val method = ScreenlyOverlay::class.java.getDeclaredMethod("currentGuidanceSnapshot")
                method.isAccessible = true
                result = overlay?.let { method.invoke(it) as GuidanceSnapshot? }
            }
            return result
        }
        instrumentation.runOnMainSync {
            val views = WindowInspector.getGlobalWindowViews()
            service = views.firstNotNullOfOrNull { it.context as? ScreenlyAccessibilityService }
            service?.let {
                val field = ScreenlyAccessibilityService::class.java.getDeclaredField("overlay")
                field.isAccessible = true
                overlay = field.get(it) as ScreenlyOverlay?
            }
            views.filter { it.context is ScreenlyAccessibilityService }.forEach { view ->
                if (view.visibility == View.VISIBLE) { hidden += view; view.visibility = View.INVISIBLE }
            }
        }
        try {
            val connected = checkNotNull(service) { "No live Screenly overlay. Open a non-sensitive target app." }
            snapshot = current()
            val captured = checkNotNull(snapshot)
            val bounds = connected.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val result = ScreenImageCapture(connected).capture(captured, bounds.width(), bounds.height())
            assertTrue("Expected a permitted capture: $result", result is ScreenCapture.Image)
            val frame = (result as ScreenCapture.Image).frame
            try {
                assertEquals(captured.key, frame.key)
                assertEquals(captured.key, current()?.key)
                assertTrue(frame.imageWidth > 0 && frame.imageHeight > 0 && maxOf(frame.imageWidth, frame.imageHeight) <= 768)
                assertTrue(frame.bytes.isNotEmpty())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("live_capture", "PASS; revision=${captured.key.revision}; encoded=${frame.imageWidth}x${frame.imageHeight}; no persisted pixels")
                })
            } finally { frame.close() }
            assertTrue(frame.bytes.all { it == 0.toByte() })
        } finally {
            instrumentation.runOnMainSync { hidden.forEach { it.visibility = View.VISIBLE } }
        }
    }
}
