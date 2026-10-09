package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager

/**
 * Owns focusable feature overlays, on-demand screenshots and ephemeral UI state.
 * Screen pixels never enter a disk cache or the accessibility observation model.
 */
internal class ScreenlyFeaturePanel(
    private val service: AccessibilityService,
    private val windowManager: WindowManager,
    private val bubble: View,
    private val layoutParams: (Int, Int) -> WindowManager.LayoutParams,
    private val refreshObservation: () -> Boolean,
    private val currentObservation: () -> ScreenObservation?,
    private val manualPicker: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<AssistantChatEntry>()
    private var guidance: AccessibleScreenAssistant.Guidance? = null
    private var feature: AssistantAction? = null
    private var root: View? = null
    private var selectedItem: Int? = null
    private var feedback = ""
    private var busy = false
    private var captureRequest = 0L
    private var stopped = false
    private var lastCaptureStatus = ""

    fun open(action: AssistantAction) {
        if (stopped) return
        feature = action
        feedback = ""
        render(animate = true)
        if (action == AssistantAction.EXPLAIN) capture(action, null)
    }

    fun dismiss(immediate: Boolean = false, restoreBubble: Boolean = true) {
        if (stopped) return
        stopped = true
        captureRequest++
        busy = false
        handler.removeCallbacksAndMessages(null)
        val view = root
        root = null
        view?.animate()?.cancel()
        if (view != null) {
            service.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(view.windowToken, 0)
            if (immediate) detach(view)
            else view.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                .setDuration(160L).withEndAction { detach(view) }.start()
        }
        messages.clear()
        guidance = null
        if (restoreBubble) {
            bubble.animate().cancel()
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
        onClosed()
    }

    @SuppressLint("ClickableViewAccessibility") // Only ACTION_OUTSIDE; native buttons handle taps.
    private fun render(animate: Boolean = false) {
        val action = feature ?: return
        if (stopped) return
        val panel = FloatingAssistantViews.feature(
            service,
            AssistantPanelState(
                action = action,
                messages = messages.toList(),
                detail = if (action == AssistantAction.EXPLAIN)
                    currentObservation()?.let(AccessibleScreenAssistant::describeScreen).orEmpty() else "",
                items = if (action == AssistantAction.EXPLAIN)
                    currentObservation()?.let(AccessibleScreenAssistant::visibleItems).orEmpty() else emptyList(),
                selectedItemIndex = selectedItem,
                guidance = guidance,
                processing = busy,
                captureStatus = feedback,
                processingStatus = service.getString(R.string.assistant_ai_runtime_status),
                accessibilityStatus = service.getString(R.string.assistant_accessibility_status)
            ),
            AssistantPanelActions(
                close = { dismiss() },
                submit = { prompt ->
                    if (!busy) when (action) {
                        AssistantAction.ASK_AI -> {
                            messages += AssistantChatEntry(true, prompt)
                            capture(action, prompt)
                        }
                        AssistantAction.GUIDE_ME -> {
                            guidance = null
                            capture(action, prompt)
                        }
                        else -> Unit
                    }
                },
                refresh = {
                    if (!busy) capture(action, messages.lastOrNull { it.fromUser }?.content)
                },
                selectItem = { index ->
                    selectedItem = if (selectedItem == index) null else index
                    render()
                },
                checkScreen = {
                    if (!busy) capture(AssistantAction.GUIDE_ME, null)
                },
                cancelGuide = {
                    guidance = null
                    feedback = service.getString(R.string.assistant_guide_cancelled)
                    render()
                },
                clearHistory = {
                    messages.clear()
                    feedback = service.getString(R.string.assistant_history_cleared)
                    render()
                },
                clearScreenshots = {
                    captureRequest++
                    busy = false
                    feedback = service.getString(R.string.assistant_images_cleared)
                    render()
                },
                openPermissions = {
                    dismiss()
                    service.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                },
                manualPicker = {
                    dismiss()
                    manualPicker()
                }
            )
        )
        panel.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else false
        }
        panel.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }
        panel.setOnApplyWindowInsetsListener { _, insets ->
            handler.post { reposition() }
            insets
        }
        panel.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, previousBottom ->
            if (bottom != previousBottom) handler.post { reposition() }
        }
        val previous = root
        previous?.animate()?.cancel()
        previous?.let(::detach)
        val targetWidth = dp(280)
        val params = layoutParams(targetWidth, dp(300))
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        try {
            windowManager.addView(panel, params)
            root = panel
            bubble.visibility = View.INVISIBLE
        } catch (_: WindowManager.BadTokenException) {
            dismiss(immediate = true)
            return
        } catch (_: WindowManager.InvalidDisplayException) {
            dismiss(immediate = true)
            return
        }
        if (animate) {
            val bubbleParams = bubble.layoutParams as? WindowManager.LayoutParams
            val fromX = (bubbleParams?.x ?: params.x) + dp(36)
            val fromY = (bubbleParams?.y ?: params.y) + dp(36)
            panel.pivotX = (fromX - params.x).toFloat().coerceIn(0f, targetWidth.toFloat())
            panel.pivotY = (fromY - params.y).toFloat().coerceIn(0f, dp(300).toFloat())
            panel.alpha = 0f
            panel.scaleX = (dp(72).toFloat() / targetWidth).coerceAtMost(1f)
            panel.scaleY = dp(72).toFloat() / dp(300)
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(DecelerateInterpolator()).setDuration(200L).start()
        }
    }

    private fun reposition() {
        val view = root ?: return
        if (stopped || view.visibility != View.VISIBLE || view.height <= 0) return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val wanted = layoutParams(params.width, view.height)
        if (params.x == wanted.x && params.y == wanted.y) return
        params.x = wanted.x
        params.y = wanted.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            dismiss(immediate = true)
        }
    }

    /** Called only by explicit Send, Explain, Refresh, or Check my screen interactions. */
    private fun capture(action: AssistantAction, prompt: String?) {
        if (stopped || busy || feature != action) return
        busy = true
        feedback = service.getString(R.string.assistant_capture_starting)
        val available = refreshObservation()
        val snapshot = currentObservation()?.takeIf { available && !stopped }
        if (snapshot == null) {
            busy = false
            feedback = service.getString(R.string.assistant_screen_unavailable)
            render()
            return
        }
        render()
        val requestId = ++captureRequest
        root?.let {
            it.clearFocus()
            service.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.visibility = View.INVISIBLE
        }
        bubble.visibility = View.INVISIBLE
        handler.postDelayed({
            if (stopped || requestId != captureRequest) return@postDelayed
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val decoded = try {
                                // No vision backend is integrated: validate the transient buffer,
                                // then immediately discard it without claiming image interpretation.
                                val image = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                val valid = image != null && image.width > 0 && image.height > 0
                                image?.recycle()
                                valid
                            } catch (_: RuntimeException) {
                                false
                            } finally {
                                buffer.close()
                            }
                            completeCapture(requestId, action, prompt, snapshot, decoded)
                        }
                        override fun onFailure(errorCode: Int) {
                            completeCapture(requestId, action, prompt, snapshot, false)
                        }
                    })
            } catch (_: SecurityException) {
                completeCapture(requestId, action, prompt, snapshot, false)
            } catch (_: IllegalStateException) {
                completeCapture(requestId, action, prompt, snapshot, false)
            }
        }, 360L)
        handler.postDelayed({
            if (!stopped && requestId == captureRequest && busy) {
                captureRequest++
                busy = false
                feedback = service.getString(R.string.assistant_capture_failed)
                root?.visibility = View.VISIBLE
                render()
            }
        }, 5000L)
    }

    private fun completeCapture(
        requestId: Long,
        action: AssistantAction,
        prompt: String?,
        snapshot: ScreenObservation,
        success: Boolean
    ) {
        if (stopped || requestId != captureRequest) return
        busy = false
        lastCaptureStatus = if (success) service.getString(R.string.assistant_capture_success)
            else service.getString(R.string.assistant_capture_failed)
        feedback = lastCaptureStatus
        root?.visibility = View.VISIBLE
        if (success && feature == action) {
            // The fallback uses only sanitized accessibility data, not the screenshot.
            when (action) {
                AssistantAction.ASK_AI -> {
                    val question = prompt ?: messages.lastOrNull { it.fromUser }?.content
                    if (!question.isNullOrBlank()) messages += AssistantChatEntry(
                        false, AccessibleScreenAssistant.ask(question, snapshot)
                    )
                }
                AssistantAction.EXPLAIN -> selectedItem = null
                AssistantAction.GUIDE_ME -> {
                    guidance = if (guidance != null && prompt == null)
                        AccessibleScreenAssistant.check(guidance!!, snapshot)
                    else if (!prompt.isNullOrBlank())
                        AccessibleScreenAssistant.begin(prompt, snapshot)
                    else guidance
                }
                AssistantAction.PRIVACY -> Unit
            }
        }
        render()
    }

    private fun detach(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // A revoked service token may already have removed the view.
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()
}
