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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val layoutParams: (Int, Int) -> WindowManager.LayoutParams,
    private val refreshObservation: () -> Boolean,
    private val currentObservation: () -> ScreenObservation?,
    private val manualPicker: () -> Unit,
    private val onClosed: () -> Unit,
    private val session: ScreenlySessionStore,
    private val aiGateway: OfflineAiGateway
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messages get() = session.messages
    private var guidance: AccessibleScreenAssistant.Guidance?
        get() = session.guidance
        set(value) { session.updateGuidance(value) }
    private var feature: AssistantAction? = null
    private var root: View? = null
    private var selectedItem: Int? = null
    private var feedback = ""
    private var busy = false
    private var captureRequest = 0L
    private var stopped = false
    private var lastCaptureStatus = ""
    private val ocrDelegate = lazy { OfflineOcr() }
    private val ocr by ocrDelegate
    private var ocrLines: List<OcrTextLine> = emptyList()

    fun open(action: AssistantAction) {
        if (stopped) return
        feature = action
        feedback = ""
        if (action == AssistantAction.EXPLAIN) {
            if (!refreshObservation()) feedback = service.getString(R.string.assistant_screen_unavailable)
        }
        if (action == AssistantAction.GUIDE_ME && guidance == null) {
            currentObservation()?.let(session::resumeFrom)
        }
        render(animate = true)
    }

    fun dismiss(immediate: Boolean = false, restoreBubble: Boolean = true) {
        if (stopped) return
        stopped = true
        captureRequest++
        busy = false
        handler.removeCallbacksAndMessages(null)
        tasks.cancel()
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
        if (ocrDelegate.isInitialized()) ocr.close()
        ocrLines = emptyList()
        // Closing only hides the panel. The session is owned by ScreenlyOverlay.
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
        val observed = if (action == AssistantAction.EXPLAIN) currentObservation() else null
        val diagnostics = observed
            ?.takeIf { BuildConfig.DEBUG && it.packageName == "com.android.settings" }
            ?.let(UiExtractionDiagnostics::inspect)
        val panel = FloatingAssistantViews.feature(
            service,
            AssistantPanelState(
                action = action,
                messages = messages.toList(),
                detail = (diagnostics?.summary ?: observed
                    ?.let(AccessibleScreenAssistant::describeScreen).orEmpty()) +
                    if (action == AssistantAction.EXPLAIN && ocrLines.isNotEmpty())
                        "\\n" + OcrEvidence.summary(ocrLines) else "",
                items = diagnostics?.entries ?: observed
                    ?.let(AccessibleScreenAssistant::visibleItems).orEmpty(),
                selectedItemIndex = selectedItem,
                guidance = guidance,
                processing = busy,
                captureStatus = feedback,
                processingStatus = aiGateway.status(),
                accessibilityStatus = service.getString(R.string.assistant_accessibility_status)
            ),
            AssistantPanelActions(
                close = { dismiss() },
                submit = { prompt ->
                    if (!busy) when (action) {
                        AssistantAction.ASK_AI -> {
                            session.addMessage(AssistantChatEntry(true, prompt))
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
                    if (!busy) {
                        if (action == AssistantAction.EXPLAIN) {
                            selectedItem = null
                            capture(action, null)
                        } else capture(action, messages.lastOrNull { it.fromUser }?.content)
                    }
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
                    session.clearChat()
                    feedback = service.getString(R.string.assistant_history_cleared)
                    render()
                },
                clearGuidance = {
                    session.clearGuidance()
                    feedback = "Saved guidance and its progress were cleared."
                    render()
                },
                clearScreenshots = {
                    captureRequest++
                    busy = false
                    ocrLines = emptyList()
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

    /** On-demand OCR; screenshots are never written to disk or retained in a session. */
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
                            val copy = try {
                                val original = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                val pixels = original?.copy(Bitmap.Config.ARGB_8888, false)
                                original?.recycle()
                                pixels
                            } catch (_: RuntimeException) {
                                null
                            } finally {
                                buffer.close()
                            }
                            if (copy == null) {
                                completeCapture(requestId, action, prompt, snapshot, false, emptyList())
                                return
                            }
                            if (stopped || requestId != captureRequest) {
                                copy.recycle()
                                return
                            }
                            try {
                                ocr.recognize(copy) { result ->
                                    copy.recycle()
                                    completeCapture(requestId, action, prompt, snapshot,
                                        result.isSuccess, result.getOrDefault(emptyList()))
                                }
                            } catch (_: RuntimeException) {
                                copy.recycle()
                                completeCapture(requestId, action, prompt, snapshot, false, emptyList())
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            completeCapture(requestId, action, prompt, snapshot, false, emptyList())
                        }
                    })
            } catch (_: SecurityException) {
                completeCapture(requestId, action, prompt, snapshot, false, emptyList())
            } catch (_: IllegalStateException) {
                completeCapture(requestId, action, prompt, snapshot, false, emptyList())
            }
        }, 360L)
        handler.postDelayed({
            if (!stopped && requestId == captureRequest && busy) {
                ++captureRequest
                busy = false
                feedback = "Screen recognition timed out. Try again; accessibility is still available."
                root?.visibility = View.VISIBLE
                render()
            }
        }, 8000L)
    }

    private fun completeCapture(
        requestId: Long,
        action: AssistantAction,
        prompt: String?,
        snapshot: ScreenObservation,
        success: Boolean,
        lines: List<OcrTextLine>
    ) {
        if (stopped || requestId != captureRequest) return
        busy = false
        root?.visibility = View.VISIBLE
        // Do not attach OCR from a stale screenshot to a newer application screen.
        if (currentObservation() != snapshot) {
            feedback = "Screen changed during recognition. Please check the current screen again."
            ocrLines = emptyList()
            render()
            return
        }
        ocrLines = if (success) lines else emptyList()
        lastCaptureStatus = if (success)
            "Offline OCR analyzed ${lines.size} text lines. Image pixels were released."
        else "Screenshot/OCR unavailable. Using accessibility-only information."
        feedback = lastCaptureStatus
        if (feature == action) {
            when (action) {
                AssistantAction.ASK_AI -> {
                    val question = prompt ?: messages.lastOrNull { it.fromUser }?.content
                    if (!question.isNullOrBlank()) {
                        val answer = AccessibleScreenAssistant.ask(question, snapshot)
                        val supplement = if (success && lines.isNotEmpty()) {
                            "\nOCR also found: " +
                                lines.take(4).joinToString(", ") { it.text } +
                                ". OCR-only labels are not verified as tappable."
                        } else ""
                        val offlineFallback = answer + supplement
                        if (aiGateway.available()) {
                            busy = true
                            feedback = "Running the local text model..."
                            render()
                            tasks.launch {
                                val modelAnswer = aiGateway.answer(question, snapshot, ocrLines)
                                if (stopped || requestId != captureRequest) return@launch
                                session.addMessage(AssistantChatEntry(false,
                                    if (modelAnswer != null)
                                        "Local AI (unverified explanation): $modelAnswer"
                                    else offlineFallback))
                                busy = false
                                feedback = if (modelAnswer == null)
                                    "Local AI could not run; using accessibility and OCR."
                                else "Generated offline. No automated taps were performed."
                                render()
                            }
                        } else session.addMessage(AssistantChatEntry(false, offlineFallback))
                    }
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
