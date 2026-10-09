package com.screenly.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only current-screen test. Clicks only Screenly's own controls; logs no screen content. */
@RunWith(AndroidJUnit4::class)
class AssistantInferenceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun menuSurvivesTargetContentInvalidation() {
        assumeTrue("Pass -e assistantUi true.", InstrumentationRegistry.getArguments().getString("assistantUi") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        waitForService()
        openMenu()
        try {
            instrumentation.runOnMainSync {
                val service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                    it.context as? ScreenlyAccessibilityService
                }
                val field = ScreenlyAccessibilityService::class.java.getDeclaredField("overlay").apply { isAccessible = true }
                val overlay = field.get(checkNotNull(service)) as ScreenlyOverlay
                val snapshotMethod = ScreenlyOverlay::class.java.getDeclaredMethod("currentGuidanceSnapshot").apply { isAccessible = true }
                val before = snapshotMethod.invoke(overlay) as GuidanceSnapshot
                overlay.clearVisibleTargets()
                assertEquals("A content event alone must not cancel unchanged observations.", before.key,
                    (snapshotMethod.invoke(overlay) as GuidanceSnapshot).key)
                overlay.clearSelection()
            }
            SystemClock.sleep(750)
            assertTrue(waitView { it is TextView && it.text.toString() == context.getString(R.string.assistant_action_explain) }.isShown)
            instrumentation.sendStatus(0, Bundle().apply { putString("menu_stability", "PASS; target selection invalidated without dismissing assistant menu") })
        } finally { openMenu() }
    }

    @Test fun explainAndAskCallRealInference() {
        assumeTrue("Pass -e assistantUi true on an authorized non-sensitive current screen.",
            InstrumentationRegistry.getArguments().getString("assistantUi") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        waitForService()
        assertTrue("Manually enable Screenly first.", context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName })
        try {
            openMenu()
            click(waitView { it is TextView && it.text.toString() == context.getString(R.string.assistant_action_explain) })
            verifyAnswer("explain")
            openMenu()
            click(waitView { it is TextView && it.text.toString() == context.getString(R.string.assistant_action_ask) })
            val input = waitView { it is EditText } as EditText
            instrumentation.runOnMainSync { input.setText("What is this screen for?") }
            click(waitView { it is TextView && it.text.toString() == context.getString(R.string.ask_send) })
            verifyAnswer("ask")
        } finally {
            // Clear only this assistant session, preserving Android accessibility settings.
            openMenu()
            click(waitView { it is TextView && it.text.toString() == context.getString(R.string.assistant_action_privacy) })
            click(waitView { it is TextView && it.text.toString() == context.getString(R.string.clear_assistant_session) })
        }
    }

    private fun openMenu() {
        click(waitView { it.contentDescription?.toString() == context.getString(R.string.assistant_bubble_description) })
    }

    private fun waitForService() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }) return
            SystemClock.sleep(250)
        }
        throw AssertionError("Manually reconnect Screenly after instrumentation restarts its process.")
    }

    private fun verifyAnswer(feature: String) {
        val answer = waitView(timeout = 180_000) { view ->
            view is TextView && (view.text.startsWith(context.getString(R.string.ai_multimodal)) ||
                view.text.startsWith(context.getString(R.string.ai_text_only)))
        } as TextView
        var value = ""
        instrumentation.runOnMainSync { value = answer.text.toString() }
        assertFalse("A placeholder or failed decision is not an explanation.", value.contains(context.getString(R.string.guidance_uncertain)))
        val mode = if (value.startsWith(context.getString(R.string.ai_multimodal))) "MULTIMODAL" else "TEXT_ONLY"
        instrumentation.sendStatus(0, Bundle().apply { putString(feature, "PASS; mode=$mode; answer_characters=${value.length}; no private answer logged") })
    }

    private fun click(view: View) {
        instrumentation.runOnMainSync {
            var target = view
            while (!target.isClickable && target.parent is View) target = target.parent as View
            assertTrue("Screenly control must be clickable.", target.isClickable)
            target.performClick()
        }
    }

    private fun waitView(timeout: Long = 15_000, predicate: (View) -> Boolean): View {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            var result: View? = null
            instrumentation.runOnMainSync {
                fun find(view: View): View? {
                    if (view.isShown && predicate(view)) return view
                    if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                    return null
                }
                result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            }
            result?.let { return it }
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected Screenly view did not appear.")
    }
}
