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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns focusable feature overlays, on-demand screenshots and ephemeral UI state.
 * Screen pixels never enter a disk cache or the accessibility observation model.
 */
internal class ScreenlyFeaturePanel(
    private val service: AccessibilityService,
    private val windowManager: WindowManager,
    private val bubble: View,
    private val session: AssistantSessionStore,
    private val assistant: OnDeviceAssistant,
    private val layoutParams: (Int, Int) -> WindowManager.LayoutParams,
    private val refreshObservation: () -> Boolean,
    private val currentObservation: () -> ScreenObservation?,
    private val currentRevision: () -> Long,
    private val highlightTarget: (ScreenObservation, Int, Long) -> Unit,
    private val manualPicker: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inferenceJob: Job? = null
    private var inferenceRunning = false
    private val messages get() = session.messages
    private var guidance: AccessibleScreenAssistant.Guidance?
        get() = session.guidance
        set(value) { session.guidance = value }
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
        if (action == AssistantAction.EXPLAIN && session.explanation == null) capture(action, null)
    }

    fun dismiss(immediate: Boolean = false, restoreBubble: Boolean = true) {
        if (stopped) return
        stopped = true
        captureRequest++
        inferenceJob?.cancel()
        scope.cancel()
        inferenceRunning = false
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
                    session.explanation ?: currentObservation()
                        ?.let(AccessibleScreenAssistant::describeScreen).orEmpty() else "",
                items = if (action == AssistantAction.EXPLAIN)
                    currentObservation()?.let(AccessibleScreenAssistant::visibleItems).orEmpty() else emptyList(),
                selectedItemIndex = selectedItem,
                guidance = guidance,
                guideSteps = session.guideSteps.toList(),
                processing = busy,
                captureStatus = if (action == AssistantAction.PRIVACY) {
                    session.captureStatus.ifBlank {
                        service.getString(R.string.assistant_capture_idle)
                    }
                } else feedback,
                processingStatus = assistant.runtimeStatus(),
                accessibilityStatus = service.getString(R.string.assistant_accessibility_status)
            ),
            AssistantPanelActions(
                close = { dismiss() },
                submit = { prompt ->
                    if (!busy) when (action) {
                        AssistantAction.ASK_AI -> {
                            messages += AssistantChatEntry(true, prompt)
                            trimHistory()
                            capture(action, prompt)
                        }
                        AssistantAction.GUIDE_ME -> {
                            // Keep the last guide visible until a new screen capture succeeds.
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
                    session.startNewGuide()
                    feedback = service.getString(R.string.assistant_guide_cancelled)
                    render()
                },
                clearHistory = {
                    session.clearHistory()
                    feedback = service.getString(R.string.assistant_history_cleared)
                    render()
                },
                clearScreenshots = {
                    captureRequest++
                    inferenceJob?.cancel()
                    inferenceRunning = false
                    busy = false
                    feedback = service.getString(R.string.assistant_images_cleared)
                    session.captureStatus = service.getString(R.string.assistant_capture_idle)
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
            // Guide Me must remain visible while the user acts on the underlying app.
            // FLAG_NOT_TOUCH_MODAL permits touches outside the panel without dismissing it.
            event.actionMasked == MotionEvent.ACTION_OUTSIDE
        }
        installBackHandler(panel)
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
            panel.pivotX = (fromX - params.x).toFloat().coerceIn(0f, params.width.toFloat())
            panel.pivotY = (fromY - params.y).toFloat().coerceIn(0f, dp(300).toFloat())
            panel.alpha = 0f
            panel.scaleX = (dp(72).toFloat() / params.width).coerceAtMost(1f)
            panel.scaleY = dp(72).toFloat() / dp(300)
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(DecelerateInterpolator()).setDuration(200L).start()
        }
    }

    private fun installBackHandler(view: View) {
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) installBackHandler(view.getChildAt(index))
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
        session.captureStatus = service.getString(R.string.assistant_capture_running)
        val available = refreshObservation()
        val snapshot = currentObservation()?.takeIf { available && !stopped }
        if (snapshot == null) {
            busy = false
            feedback = service.getString(R.string.assistant_screen_unavailable)
            session.captureStatus = feedback
            render()
            return
        }
        render()
        val requestId = ++captureRequest
        val revision = currentRevision()
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
                            completeCapture(requestId, revision, action, prompt, snapshot, decoded)
                        }
                        override fun onFailure(errorCode: Int) {
                            completeCapture(requestId, revision, action, prompt, snapshot, false)
                        }
                    })
            } catch (_: RuntimeException) {
                completeCapture(requestId, revision, action, prompt, snapshot, false)
            }
        }, 360L)
        handler.postDelayed({
            if (!stopped && requestId == captureRequest && busy && !inferenceRunning) {
                captureRequest++
                busy = false
                feedback = service.getString(R.string.assistant_capture_failed)
                session.captureStatus = feedback
                root?.visibility = View.VISIBLE
                render()
            }
        }, 5000L)
    }

    private fun completeCapture(
        requestId: Long,
        revision: Long,
        action: AssistantAction,
        prompt: String?,
        snapshot: ScreenObservation,
        success: Boolean
    ) {
        if (stopped || requestId != captureRequest) return
        lastCaptureStatus = if (success) service.getString(R.string.assistant_capture_success)
            else "Screenshot unavailable. Using accessible text labels instead."
        session.captureStatus = lastCaptureStatus
        feedback = lastCaptureStatus
        root?.visibility = View.VISIBLE
        // A screenshot never enters text-only AI. Stale Android targets cannot be acted on.
        if (!refreshObservation() || currentObservation() != snapshot ||
            currentRevision() != revision || feature != action) {
            busy = false
            feedback = service.getString(R.string.assistant_capture_stale)
            session.captureStatus = feedback
            render()
            return
        }
        if (assistant.modelAvailable() && action != AssistantAction.PRIVACY) {
            inferenceRunning = true
            feedback = "Generating answer offline from accessible controls..."
            render()
            inferenceJob?.cancel()
            val guideBefore = if (prompt == null) session.previousForCheck() else null
            inferenceJob = scope.launch {
                try {
                    when (action) {
                        AssistantAction.ASK_AI -> {
                            val question = prompt ?: messages.lastOrNull { it.fromUser }?.content
                            if (!question.isNullOrBlank()) {
                                val answer = assistant.ask(question, snapshot, messages.dropLast(1).toList())
                                if (requestIsCurrent(requestId, revision, snapshot)) {
                                    messages += AssistantChatEntry(false, answer)
                                    trimHistory()
                                }
                            }
                        }
                        AssistantAction.EXPLAIN -> {
                            val answer = assistant.explain(snapshot)
                            if (requestIsCurrent(requestId, revision, snapshot)) {
                                session.explanation = answer
                                selectedItem = null
                            }
                        }
                        AssistantAction.GUIDE_ME -> {
                            val goal = prompt ?: guideBefore?.goal
                            if (!goal.isNullOrBlank()) {
                                val next = assistant.guide(
                                    goal, snapshot, if (prompt == null) guideBefore else null
                                )
                                if (requestIsCurrent(requestId, revision, snapshot)) {
                                    val resolved = if (prompt == null)
                                        session.rebaseRestoredStep(next) else next
                                    if (prompt != null) session.startNewGuide()
                                    guidance = resolved
                                    resolved.targetIndex?.let { highlightTarget(snapshot, it, revision) }
                                }
                            }
                        }
                        AssistantAction.PRIVACY -> Unit
                    }
                    if (requestIsCurrent(requestId, revision, snapshot)) {
                        feedback = "Processed locally using accessible screen information."
                    }
                } catch (_: CancellationException) {
                    return@launch
                } catch (error: Exception) {
                    if (requestIsCurrent(requestId, revision, snapshot)) {
                        applyAccessibilityFallback(action, prompt, snapshot)
                        feedback = "Local AI unavailable. Used offline accessibility rules."
                    }
                } catch (error: LinkageError) {
                    if (requestIsCurrent(requestId, revision, snapshot)) {
                        applyAccessibilityFallback(action, prompt, snapshot)
                        feedback = "Model runtime unsupported on this device. Using offline rules."
                    }
                } finally {
                    if (!stopped && requestId == captureRequest) {
                        inferenceRunning = false
                        busy = false
                        if (!requestIsCurrent(requestId, revision, snapshot)) {
                            feedback = service.getString(R.string.assistant_capture_stale)
                        }
                        render()
                    }
                }
            }
        } else {
            applyAccessibilityFallback(action, prompt, snapshot)
            busy = false
            render()
        }
    }

    private fun requestIsCurrent(
        requestId: Long, revision: Long, snapshot: ScreenObservation
    ): Boolean = !stopped && requestId == captureRequest &&
        currentRevision() == revision && currentObservation() == snapshot

    private fun applyAccessibilityFallback(
        action: AssistantAction, prompt: String?, snapshot: ScreenObservation
    ) {
        when (action) {
            AssistantAction.ASK_AI -> {
                val question = prompt ?: messages.lastOrNull { it.fromUser }?.content
                if (!question.isNullOrBlank()) {
                    messages += AssistantChatEntry(
                        false, AccessibleScreenAssistant.ask(question, snapshot)
                    )
                    trimHistory()
                }
            }
            AssistantAction.EXPLAIN -> {
                session.explanation = AccessibleScreenAssistant.describeScreen(snapshot)
                selectedItem = null
            }
            AssistantAction.GUIDE_ME -> {
                val previous = session.previousForCheck()
                val next = when {
                    prompt != null -> AccessibleScreenAssistant.begin(prompt, snapshot)
                    previous != null -> AccessibleScreenAssistant.check(previous, snapshot)
                    guidance != null -> AccessibleScreenAssistant.begin(guidance!!.goal, snapshot)
                    else -> null
                }
                if (next != null) {
                    val resolved = if (prompt == null) session.rebaseRestoredStep(next) else next
                    if (prompt != null) session.startNewGuide()
                    guidance = resolved
                    resolved.targetIndex?.let { highlightTarget(snapshot, it, currentRevision()) }
                }
            }
            AssistantAction.PRIVACY -> Unit
        }
    }

    private fun trimHistory() {
        if (messages.size > 24) messages.subList(0, messages.size - 24).clear()
        session.save()
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
