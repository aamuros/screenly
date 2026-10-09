package com.screenly.app

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real overlay test. Touch injection simulates the user's taps in the test only. */
@RunWith(AndroidJUnit4::class)
class GuidanceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        }
    }

    @Test fun guidanceUsesRealBoundsAndContinuesAfterUserTap() {
        assumeTrue("Pass -e guidanceUi true on a dedicated stock Settings emulator.",
            InstrumentationRegistry.getArguments().getString("guidanceUi") == "true")
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val originalNightMode = shell("settings get secure ui_night_mode")
        val workflow = InstrumentationRegistry.getArguments().getString("workflow", "font")
        val goal: String
        val firstLabels: Set<String>
        val secondLabels: Set<String>
        val finalLabels: Set<String>
        when (workflow) {
            "wifi" -> {
                goal = "Find and open Wi-Fi settings"
                firstLabels = setOf("Network & internet", "Network and internet")
                secondLabels = setOf("Internet", "Wi-Fi")
                finalLabels = setOf("Use Wi-Fi")
            }
            "dark" -> {
                goal = "Navigate toward dark-theme settings"
                firstLabels = setOf("Display & touch", "Display")
                secondLabels = setOf("Dark theme")
                finalLabels = setOf("Use dark theme")
            }
            else -> {
                require(workflow == "font")
                goal = "How do I change my font size?"
                firstLabels = setOf("Display & touch", "Display")
                secondLabels = setOf("Display size and text", "Display size & text", "Font size")
                finalLabels = setOf("Font size")
            }
        }
        fun title(node: AccessibilityNodeInfo): String = node.text?.toString()?.replace('\u2011', '-') ?: ""
        try {
            val component = "com.screenly.app/com.screenly.app.ScreenlyAccessibilityService"
            val enabled = originalServices.takeUnless { it == "null" || it.isBlank() }
                ?.split(':')?.filter { !it.startsWith("com.screenly.app/") }.orEmpty() + component
            val others = enabled.filter { it != component }.joinToString(":")
            if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $others")
            SystemClock.sleep(300)
            // UiAutomation executes an argv command, not a quoting shell. Components have no spaces.
            shell("settings put secure enabled_accessibility_services ${enabled.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            // Reset only the test's Settings task; ACTION_SETTINGS alone can resume a detail page.
            instrumentation.targetContext.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            ))
            waitNode { title(it) in firstLabels && it.packageName?.toString() == "com.android.settings" }
            val bubble = waitView { it.contentDescription?.toString()?.startsWith("Screenly assistant.") == true }
            tap(bubble)
            tap(waitView { (it as? TextView)?.text?.toString() == "Guide Me" })
            val editor = waitView { it is EditText } as EditText
            instrumentation.runOnMainSync { editor.setText(goal) }
            tap(waitView { (it as? TextView)?.text?.toString() == "Start guidance" })
            val display = waitNode { title(it) in firstLabels && it.packageName?.toString() == "com.android.settings" }
            val displayBounds = clickableBounds(display)
            waitHighlight(displayBounds)
            record(display.text.toString(), displayBounds)
            tap(displayBounds)
            val next = waitNode { title(it) in secondLabels && it.packageName?.toString() == "com.android.settings" }
            val nextBounds = clickableBounds(next)
            waitHighlight(nextBounds)
            record(next.text.toString(), nextBounds)
            tap(nextBounds)
            waitNode { title(it) in finalLabels && it.packageName?.toString() == "com.android.settings" }
            if (workflow == "dark") assertEquals("Opening theme settings must not change the theme", originalNightMode,
                shell("settings get secure ui_night_mode"))
            if (workflow == "wifi" && InstrumentationRegistry.getArguments().getString("expectUnavailableInternet") == "true") {
                // This API 37 guest exposes Internet to UiAutomation but not to our service.
                // Verify safe waiting/clearing, then return to a readable page for Stop.
                waitView { (it as? TextView)?.text?.toString()?.startsWith("Waiting for an accessible screen") == true }
                instrumentation.runOnMainSync {
                    assertTrue(WindowInspector.getGlobalWindowViews().none { it.javaClass.simpleName == "HighlightView" })
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("guidance_ui", "Wi-Fi destination reached; service root unavailable; waiting message and no highlight verified")
                })
                instrumentation.targetContext.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                ))
                waitNode { title(it) in firstLabels && it.packageName?.toString() == "com.android.settings" }
            }
            // Stop via the real bubble; a test must not leave guidance active.
            tap(waitView { it.contentDescription?.toString()?.startsWith("Screenly assistant.") == true })
            tap(waitView { (it as? TextView)?.text?.toString() == "Guide Me" })
            tap(waitView { (it as? TextView)?.text?.toString() == "Stop guidance" })
        } finally {
            restore("enabled_accessibility_services", originalServices)
            restore("accessibility_enabled", originalEnabled)
        }
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private fun restore(key: String, value: String) {
        if (value == "null" || value.isEmpty()) shell("settings delete secure $key")
        else shell("settings put secure $key $value")
    }

    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                if (depth > 40) return null
                if (predicate(node)) return node
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    visit(child, depth + 1)?.let { return it }
                }
                return null
            }
            automation.windows.forEach { window -> window.root?.let { visit(it, 0) }?.let { return it } }
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected UI node did not appear; visible roots=${automation.windows.map { it.root?.packageName }}")
    }

    private fun clickableBounds(node: AccessibilityNodeInfo): Rect {
        var current = node
        var remaining = 40
        while (!current.isClickable && remaining-- > 0) current = current.parent ?: break
        assertTrue("Expected an enabled clickable ancestor", current.isClickable && current.isEnabled)
        return Rect().also { current.getBoundsInScreen(it) }
    }

    private fun waitView(predicate: (View) -> Boolean): View {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            var found: View? = null
            instrumentation.runOnMainSync {
                fun visit(view: View): View? {
                    if (view.isShown && predicate(view)) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::visit)
            }
            found?.let { return it }
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Screenly control did not appear in its native windows")
    }

    private fun tap(view: View) {
        val bounds = Rect()
        instrumentation.runOnMainSync {
            var control = view
            while (!control.isClickable && control.parent is View) control = control.parent as View
            assertTrue(control.isClickable)
            val origin = IntArray(2)
            control.getLocationOnScreen(origin)
            bounds.set(origin[0], origin[1], origin[0] + control.width, origin[1] + control.height)
        }
        tap(bounds)
    }

    private fun tap(node: AccessibilityNodeInfo) = tap(clickableBounds(node))
    private fun tap(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun waitHighlight(bounds: Rect) {
        val timeout = InstrumentationRegistry.getArguments().getString("highlightTimeoutMs", "20000")!!.toLong()
        require(timeout in 1_000L..180_000L)
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            val screenshot = automation.takeScreenshot()
            if (screenshot != null) {
                fun outlined(y: Int): Boolean = (-6..6).any { delta ->
                    val py = (y + delta).coerceIn(0, screenshot.height - 1)
                    val pixel = screenshot.getPixel(bounds.centerX().coerceIn(0, screenshot.width - 1), py)
                    Color.red(pixel) < 50 && Color.green(pixel) in 140..200 && Color.blue(pixel) > 200
                }
                val matches = outlined(bounds.top) && outlined(bounds.bottom)
                screenshot.recycle()
                if (matches) return
            }
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("No highlight at the current Android bounds $bounds")
    }

    private fun record(label: String, bounds: Rect) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("guidance_ui", "label=$label; Android bounds=$bounds; matching outline observed; test performs next tap")
        })
    }
}
