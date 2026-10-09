package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inferenceJob: Job? = null
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
        busy = false
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
        val oldScroll = previous as? ScrollView
        val oldScrollY = oldScroll?.scrollY ?: 0
        val oldMaxScroll = oldScroll?.let {
            (it.getChildAt(0)?.height ?: 0) - it.height
        }?.coerceAtLeast(0) ?: 0
        val wasNearBottom = oldScroll == null || oldMaxScroll - oldScrollY <= dp(18)
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
            // Re-rendering a processing state should not jump chat/guide history to the top.
            (panel as? ScrollView)?.let { scroller ->
                scroller.post {
                    if (root !== scroller) return@post
                    val maxScroll = ((scroller.getChildAt(0)?.height ?: 0) - scroller.height)
                        .coerceAtLeast(0)
                    scroller.scrollTo(0, if (wasNearBottom) maxScroll else oldScrollY.coerceAtMost(maxScroll))
                }
            }
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

    /**
     * All current inference backends are text-only. A screenshot cannot improve their answer,
     * and hiding the panel to take one makes the accessibility snapshot change underneath us.
     * Read sanitized accessibility text once without re-checking the screen after a delay.
     */
    private fun capture(action: AssistantAction, prompt: String?) {
        if (stopped || busy || feature != action) return

        // A snapshot collected before the user opened the overlay is useful for questions.
        // Guide Me deliberately requires a fresh snapshot before suggesting any target.
        val snapshot = if (action == AssistantAction.GUIDE_ME) {
            if (refreshObservation()) currentObservation() else null
        } else {
            currentObservation() ?: if (refreshObservation()) currentObservation() else null
        }
        if (snapshot == null && action != AssistantAction.ASK_AI) {
            feedback = service.getString(R.string.assistant_screen_unavailable)
            render()
            return
        }
        val observed = snapshot ?: ScreenObservation("unavailable", -1, emptyList())
        val revision = currentRevision()
        val requestId = ++captureRequest
        busy = true
        feedback = service.getString(R.string.assistant_processing)
        session.captureStatus = service.getString(R.string.assistant_capture_idle)
        render()

        if (!assistant.modelAvailable()) {
            applyAccessibilityFallback(action, prompt, observed)
            busy = false
            feedback = service.getString(R.string.assistant_offline_rules_status)
            render()
            return
        }

        inferenceJob?.cancel()
        val previousGuide = if (prompt == null) session.previousForCheck() else null
        inferenceJob = scope.launch {
            try {
                when (action) {
                    AssistantAction.ASK_AI -> {
                        val question = prompt ?: messages.lastOrNull { it.fromUser }?.content
                        if (!question.isNullOrBlank()) {
                            val answer = assistant.ask(question, observed, messages.dropLast(1).toList())
                            // Answer the question about the snapshot captured when Send was tapped,
                            // even if the user navigated before the model finished.
                            if (requestStillActive(requestId)) {
                                messages += AssistantChatEntry(false, answer)
                                trimHistory()
                            }
                        }
                    }
                    AssistantAction.EXPLAIN -> {
                        val answer = assistant.explain(observed)
                        if (requestStillActive(requestId)) {
                            session.explanation = answer
                            selectedItem = null
                        }
                    }
                    AssistantAction.GUIDE_ME -> {
                        val goal = prompt ?: guidance?.goal
                        if (!goal.isNullOrBlank()) {
                            val next = assistant.guide(goal, observed,
                                if (prompt == null) previousGuide else null)
                            if (requestIsCurrent(requestId, revision, observed)) {
                                val resolved = if (prompt == null)
                                    session.rebaseRestoredStep(next) else next
                                if (prompt != null) session.startNewGuide()
                                guidance = resolved
                                resolved.targetIndex?.let { highlightTarget(observed, it, revision) }
                            } else if (requestStillActive(requestId)) {
                                feedback = service.getString(R.string.assistant_screen_changed_guide)
                            }
                        }
                    }
                    AssistantAction.PRIVACY -> Unit
                }
                if (requestStillActive(requestId) &&
                    (action != AssistantAction.GUIDE_ME || requestIsCurrent(requestId, revision, observed))) {
                    feedback = service.getString(R.string.assistant_ai_text_complete)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                if (requestStillActive(requestId)) {
                    if (action != AssistantAction.GUIDE_ME ||
                        requestIsCurrent(requestId, revision, observed)) {
                        applyAccessibilityFallback(action, prompt, observed)
                    }
                    feedback = service.getString(R.string.assistant_offline_rules_status)
                }
            } catch (_: LinkageError) {
                if (requestStillActive(requestId)) {
                    if (action != AssistantAction.GUIDE_ME ||
                        requestIsCurrent(requestId, revision, observed)) {
                        applyAccessibilityFallback(action, prompt, observed)
                    }
                    feedback = service.getString(R.string.assistant_offline_rules_status)
                }
            } finally {
                if (requestStillActive(requestId)) {
                    busy = false
                    render()
                }
            }
        }
    }

    private fun requestStillActive(requestId: Long): Boolean =
        !stopped && requestId == captureRequest

    /** Only the Guide Me target must match the current accessibility revision. */
    private fun requestIsCurrent(
        requestId: Long, revision: Long, snapshot: ScreenObservation
    ): Boolean = requestStillActive(requestId) &&
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
