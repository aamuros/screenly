package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.graphics.withTranslation
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import com.screenly.app.ai.LiteRtMultimodalInference
import com.screenly.app.ai.navigation.AiMode
import com.screenly.app.ai.navigation.ScreenPlanner
import com.screenly.app.ai.navigation.ScreenDecisionProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

/** Owns only transient observations and native accessibility-overlay Views. */
internal class ScreenlyOverlay(
    private val service: AccessibilityService,
    private val refreshObservation: () -> Boolean
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val state = ScreenObservationState()
    private var disposed = false
    private var bubble: View? = null
    private var picker: View? = null
    private var assistantMenu: View? = null
    private var infoPanel: View? = null
    private var goalPanel: View? = null
    private var guidancePanel: View? = null
    private var lastGoal = ""
    private val sessionId = nextSession.incrementAndGet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inference = LocalInference(service)
    private val vision = LiteRtMultimodalInference(service)
    private val imageCapture = ScreenImageCapture(service)
    private var questionJob: Job? = null
    private var questionVersion = 0L
    private var questionSnapshotKey: SnapshotKey? = null
    private var cachedGuidanceSnapshot: GuidanceSnapshot? = null
    private var cachedDisplaySize: Pair<Int, Int>? = null
    private val chatHistory = mutableListOf<String>()
    private var assistantAction = AssistantAction.GUIDE_ME
    private val planner = ScreenPlanner(
        vision, text = { prompt -> inference.initialize(); inference.generate(prompt) },
        releaseText = { inference.close(); inference = LocalInference(service) },
        capture = { snapshot -> captureImage(snapshot) },
        fresh = { snapshot -> currentGuidanceSnapshot()?.let { it.key == snapshot.key && it.observation == snapshot.observation } == true }
    )
    private val controller = GuidanceController(
        scope,
        plan = { goal, snapshot, history ->
            planner.plan(goal, snapshot, history)
        },
        refresh = { refreshObservation(); currentGuidanceSnapshot() },
        render = ::renderGuidance
    )
    private val bubbleBitmap by lazy {
        BitmapFactory.decodeResource(service.resources, R.drawable.screenly_bubble,
            BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false })
    }
    private var highlight: HighlightView? = null
    private var outsideDismissalDownTime: Long? = null
    private var bubbleClickClosesPicker = false

    val isEnteringGoal: Boolean
        get() = goalPanel != null

    fun updateObservation(next: ScreenObservation) {
        if (disposed) return
        val previous = state.snapshot
        val changedApplication = previous != null &&
            (previous.packageName != next.packageName || previous.windowId != next.windowId)
        if (state.update(next)) {
            closePicker()
            // Assistant menus contain no captured target IDs. Opening our windows can
            // itself change the app's accessibility revision; keep these controls usable.
            if (changedApplication) {
                closeMenu()
                closeInfoPanel()
            }
            clearHighlight()
            if (!questionIsSettling()) clearGuidancePanel()
            invalidateQuestionFromObservation()
        }
        showBubble()
        controller.observe(currentGuidanceSnapshot())
    }

    fun clearSelection() {
        state.invalidateSelection()
        closePicker()
        clearHighlight()
        if (!questionIsSettling()) clearGuidancePanel()
        invalidateQuestionFromObservation()
        controller.observe(null)
    }

    /** Content events can be noisy; extraction decides whether immutable screen data changed.
     * Remove actionable UI immediately, and refresh again before any inference is presented. */
    fun clearVisibleTargets() {
        val hadTarget = highlight != null
        closePicker()
        clearHighlight()
        if (hadTarget && controller.status != GuidanceStatus.PLANNING && questionJob?.isActive != true) clearGuidancePanel()
    }

    private fun questionIsSettling() = questionJob?.isActive == true && questionSnapshotKey == null

    fun clearObservation(showWaiting: Boolean = false) {
        invalidateQuestion()
        chatHistory.clear()
        cachedGuidanceSnapshot = null
        cachedDisplaySize = null
        state.clear()
        closePicker()
        closeMenu()
        closeInfoPanel()
        closeGoalPanel()
        clearHighlight()
        // Keep one stable caption for an unavailable root; recreating it emits more window
        // events. Locks, our activity and lifecycle cleanup still remove every overlay.
        if (!showWaiting || controller.status != GuidanceStatus.WAITING_FOR_SCREEN) clearGuidancePanel()
        controller.observe(null)
        if (!showWaiting) clearGuidancePanel()
        else if (controller.status == GuidanceStatus.WAITING_FOR_SCREEN && guidancePanel == null) {
            showGuidanceMessage(service.getString(R.string.guidance_waiting))
        }
        val previousBubble = bubble
        bubble = null
        previousBubble?.let(::detach)
        outsideDismissalDownTime = null
        bubbleClickClosesPicker = false
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        controller.close()
        clearObservation()
        // Both native owners must close even after cancellation of the overlay's parent scope.
        scope.launch(NonCancellable) {
            try { inference.close() }
            catch (_: LocalInferenceException) { Log.w("ScreenlyGuidance", "Local engine cleanup failed.") }
            try { vision.close() }
            catch (_: LocalInferenceException) { Log.w("ScreenlyGuidance", "Image engine cleanup failed.") }
        }
        scope.cancel()
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(56)
        val area = usableScreenBounds()
        val params = overlayParams(size, size).apply {
            x = (area.right - size - dp(16)).coerceAtLeast(area.left)
            y = (area.bottom - size - dp(24)).coerceAtLeast(area.top)
        }
        val view = ImageView(service).apply {
            setImageBitmap(bubbleBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = service.getString(R.string.assistant_bubble_description)
            elevation = dp(6).toFloat()
            setOnClickListener {
                if (disposed || bubble !== it) return@setOnClickListener
                val closesPanel = picker != null || assistantMenu != null ||
                    infoPanel != null || goalPanel != null || bubbleClickClosesPicker
                bubbleClickClosesPicker = false
                if (closesPanel) {
                    closePicker()
                    closeMenu()
                    closeInfoPanel()
                    closeGoalPanel()
                } else showMenu()
            }
        }
        enableDragging(view, params)
        if (attach(view, params)) bubble = view
    }

    private fun enableDragging(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // ACTION_OUTSIDE can dismiss the picker before this window receives DOWN.
                    bubbleClickClosesPicker = picker != null || assistantMenu != null ||
                        infoPanel != null || goalPanel != null || outsideDismissalDownTime == event.downTime
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (abs(deltaX) > slop || abs(deltaY) > slop) dragging = true
                    if (dragging && bubble === touchedView) {
                        bubbleClickClosesPicker = false
                        closePicker()
                        closeMenu()
                        closeInfoPanel()
                        closeGoalPanel()
                        val area = usableScreenBounds()
                        params.x = (startX + deltaX.toInt()).coerceIn(
                            area.left, (area.right - params.width).coerceAtLeast(area.left)
                        )
                        params.y = (startY + deltaY.toInt()).coerceIn(
                            area.top, (area.bottom - params.height).coerceAtLeast(area.top)
                        )
                        try {
                            windowManager.updateViewLayout(touchedView, params)
                        } catch (_: IllegalArgumentException) {
                            // The system may remove the window when its service token is revoked.
                            clearObservation()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) touchedView.performClick()
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    bubbleClickClosesPicker = false
                }
            }
            true
        }
    }


    /** Compact native controls; the target app still receives touches outside the menu. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        if (disposed || assistantMenu != null) return
        closePicker()
        closeInfoPanel()
        val area = usableScreenBounds()
        if (area.width() <= 0 || area.height() <= 0) return
        var expectedView: View? = null
        val view = FloatingAssistantViews.menu(service, onAction = actionClick@ { action ->
            if (disposed || assistantMenu !== expectedView) return@actionClick
            closeMenu()
            when (action) {
                AssistantAction.GUIDE_ME, AssistantAction.ASK_AI -> {
                    assistantAction = action
                    showGoalPanel()
                }
                AssistantAction.EXPLAIN -> askScreen(service.getString(R.string.explain_screen_prompt))
                AssistantAction.PRIVACY -> showInfoPanel(
                    R.string.assistant_action_privacy, R.string.privacy_notice
                )
            }
        })
        expectedView = view
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                outsideDismissalDownTime = event.downTime
                closeMenu()
                true
            } else false
        }
        if (attach(view, anchoredPanelParams(
            min(dp(272), area.width()), min(dp(244), area.height())
        ))) assistantMenu = view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInfoPanel(title: Int, message: Int) {
        if (disposed) return
        closeInfoPanel()
        val area = usableScreenBounds()
        if (area.width() <= 0 || area.height() <= 0) return
        val view = FloatingAssistantViews.infoPanel(
            service, service.getString(title), service.getString(message),
            onClear = if (title == R.string.assistant_action_privacy) ({
                controller.stop()
                invalidateQuestion()
                chatHistory.clear()
                lastGoal = ""
                clearHighlight()
                closeInfoPanel()
                showGuidanceMessage(service.getString(R.string.assistant_session_cleared))
            }) else null
        ) { closeInfoPanel() }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                outsideDismissalDownTime = event.downTime
                closeInfoPanel()
                true
            } else false
        }
        if (attach(view, anchoredPanelParams(
            min(dp(288), area.width()), min(dp(228), area.height())
        ))) infoPanel = view
    }

    private fun anchoredPanelParams(width: Int, height: Int): WindowManager.LayoutParams {
        val area = usableScreenBounds()
        val bubbleParams = bubble?.layoutParams as? WindowManager.LayoutParams
        val bubbleX = bubbleParams?.x ?: (area.right - dp(56))
        val bubbleY = bubbleParams?.y ?: (area.bottom - dp(56))
        val above = bubbleY - height - dp(8)
        return overlayParams(width, height).apply {
            x = (bubbleX + dp(56) - width).coerceIn(
                area.left, (area.right - width).coerceAtLeast(area.left)
            )
            y = if (above >= area.top) above else {
                (bubbleY + dp(56) + dp(8)).coerceAtMost(area.bottom - height)
                    .coerceAtLeast(area.top)
            }
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
    }

    private fun closeMenu() {
        val previous = assistantMenu
        assistantMenu = null
        previous?.let(::detach)
    }

    private fun closeInfoPanel() {
        val previous = infoPanel
        infoPanel = null
        previous?.let(::detach)
    }

    private fun currentGuidanceSnapshot(): GuidanceSnapshot? {
        if (disposed) return null
        val observation = state.snapshot ?: return null
        val display = windowManager.currentWindowMetrics.bounds
        val key = SnapshotKey(sessionId, state.revision)
        val size = display.width() to display.height()
        cachedGuidanceSnapshot?.takeIf { it.key == key && cachedDisplaySize == size }?.let { return it }
        return guidanceSnapshot(key, observation, size.first, size.second).also {
            cachedGuidanceSnapshot = it
            cachedDisplaySize = size
        }
    }

    private suspend fun captureImage(snapshot: GuidanceSnapshot): ScreenCapture {
        closeMenu()
        closeInfoPanel()
        closeGoalPanel()
        var imageSnapshot = snapshot
        guidancePanel?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { caption ->
            val location = IntArray(2)
            caption.getLocationOnScreen(location)
            val observation = snapshot.observation
            val crop = ImageCropBounds(observation.windowLeft, observation.windowTop, observation.windowRight, observation.windowBottom)
                .excluding(ImageCropBounds(location[0], location[1], location[0] + caption.width, location[1] + caption.height))
                ?: return ScreenCapture.Failed(CaptureFailure.PRIVACY)
            // Keep the status visible; its exact window rectangle is excluded from AI pixels.
            imageSnapshot = snapshot.copy(observation = observation.copy(
                windowLeft = crop.left, windowTop = crop.top, windowRight = crop.right, windowBottom = crop.bottom))
        }
        val hidden = listOfNotNull(bubble, highlight)
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            delay(350) // Let keyboard/overlay surfaces disappear before capture.
            refreshObservation()
            val fresh = currentGuidanceSnapshot()
            if (fresh?.key != snapshot.key || fresh.observation != snapshot.observation) {
                return ScreenCapture.Failed(CaptureFailure.STALE)
            }
            val bounds = windowManager.currentWindowMetrics.bounds
            return imageCapture.capture(imageSnapshot, bounds.width(), bounds.height())
        } finally {
            if (!disposed) hidden.forEach { it.visibility = View.VISIBLE }
        }
    }

    private fun invalidateQuestion() {
        questionVersion++
        questionSnapshotKey = null
        questionJob?.cancel()
        questionJob = null
    }

    private fun invalidateQuestionFromObservation() {
        if (questionSnapshotKey != null) {
            invalidateQuestion()
            showGuidanceMessage(service.getString(R.string.ai_screen_changed))
        }
    }

    private fun askScreen(question: String) {
        controller.stop()
        invalidateQuestion()
        val version = questionVersion
        showGuidanceMessage(service.getString(R.string.guidance_planning))
        questionJob = scope.launch {
            delay(600) // Allow the input panel, keyboard and app layout to settle.
            refreshObservation()
            if (disposed || version != questionVersion) return@launch
            val snapshot = currentGuidanceSnapshot() ?: run {
                showGuidanceMessage(service.getString(R.string.guidance_waiting))
                return@launch
            }
            questionSnapshotKey = snapshot.key
            showGuidanceMessage(service.getString(R.string.guidance_planning))
            val result = try {
                planner.plan(question, snapshot, chatHistory.toList(), question = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                com.screenly.app.ai.navigation.ScreenDecision(
                    com.screenly.app.ai.navigation.NavigationAction.UNCERTAIN, null, null, "", AiMode.UNAVAILABLE, "failed")
            }
            refreshObservation()
            if (disposed || version != questionVersion || currentGuidanceSnapshot()?.key != snapshot.key) return@launch
            val answer = decisionMessage(result)
            chatHistory += "Question: $question; answer: ${result.explanation}"
            while (chatHistory.size > 4) chatHistory.removeAt(0)
            val target = result.index?.takeIf { ScreenDecisionProtocol.grounded(result, snapshot) }
                ?.let { snapshot.observation.elements[it] }
            target?.let(::showHighlight)
            questionSnapshotKey = null
            showGuidanceMessage(answer, target)
        }
    }

    private fun decisionMessage(result: com.screenly.app.ai.navigation.ScreenDecision): String {
        val mode = service.getString(when (result.mode) {
            AiMode.MULTIMODAL -> R.string.ai_multimodal
            AiMode.TEXT_ONLY -> R.string.ai_text_only
            AiMode.UNAVAILABLE -> R.string.guidance_uncertain_model_unavailable
        })
        val explanation = result.explanation.ifBlank { service.getString(R.string.guidance_uncertain) }
        val visionReason = when (result.visionStatus) {
            "missing" -> R.string.ai_vision_missing
            "unverified" -> R.string.ai_vision_unverified
            "privacy" -> R.string.ai_vision_private
            "failed", "unsupported" -> R.string.ai_vision_failed
            "android_denied", "decode", "timeout", "stale" -> R.string.ai_capture_failed
            else -> R.string.ai_vision_not_used
        }
        val label = result.index?.let { currentGuidanceSnapshot()?.planningElements?.getOrNull(it) }
            ?.let { it.text ?: it.contentDescription } ?: service.getString(R.string.unlabelled_element)
        val instruction = if (result.explanation.isNotBlank()) explanation else when (result.action) {
            com.screenly.app.ai.navigation.NavigationAction.NAVIGATE -> service.getString(R.string.guidance_step, label)
            com.screenly.app.ai.navigation.NavigationAction.ADJUST -> service.getString(
                if (result.direction == "INCREASE") R.string.guidance_increase else R.string.guidance_decrease, label)
            com.screenly.app.ai.navigation.NavigationAction.TOGGLE -> service.getString(
                if (result.direction == "ON") R.string.guidance_turn_on else R.string.guidance_turn_off, label)
            com.screenly.app.ai.navigation.NavigationAction.SCROLL -> service.getString(
                if (result.direction == "FORWARD") R.string.guidance_scroll_forward else R.string.guidance_scroll_backward, label)
            com.screenly.app.ai.navigation.NavigationAction.DONE -> service.getString(R.string.guidance_goal_appears_complete)
            else -> explanation
        }
        return "$mode\n$instruction" + if (result.mode == AiMode.TEXT_ONLY) "\n" + service.getString(visionReason) else ""
    }

    private fun showGoalPanel() {
        if (disposed || goalPanel != null) return
        closePicker()
        closeInfoPanel()
        val area = usableScreenBounds()
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(service).apply {
            setText(R.string.guidance_goal_title)
            setTextColor(Color.BLACK)
            textSize = 18f
        })
        val input = EditText(service).apply {
            hint = service.getString(R.string.guidance_goal_hint)
            setText(lastGoal)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(160))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 3
        }
        content.addView(input)
        fun submit() {
            val goal = input.text.toString().trim()
            if (goal.isBlank()) { input.error = service.getString(R.string.guidance_goal_required); return }
            lastGoal = goal
            closeGoalPanel()
            refreshObservation()
            invalidateQuestion()
            if (assistantAction == AssistantAction.ASK_AI) askScreen(goal) else controller.start(goal)
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(service).apply { setText(label); isAllCaps = false; setOnClickListener { action() } })
        }
        button(if (assistantAction == AssistantAction.ASK_AI) R.string.ask_send else R.string.guidance_start, ::submit)
        button(R.string.guidance_retry) { closeGoalPanel(); controller.retry() }
        button(R.string.guidance_stop) { closeGoalPanel(); controller.stop() }
        button(R.string.guidance_confirm) {
            closeGoalPanel(); controller.stop()
            showGuidanceMessage(service.getString(R.string.guidance_user_confirmed))
        }
        button(R.string.guidance_manual_picker) {
            closeGoalPanel(); controller.stop()
            if (refreshObservation()) showPicker()
        }
        button(R.string.assistant_close) { closeGoalPanel() }
        val view = ScrollView(service).apply { addView(content) }
        val params = anchoredPanelParams(min(dp(320), area.width()), min(dp(420), area.height() * 2 / 3)).apply {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (attach(view, params)) goalPanel = view
    }

    private fun closeGoalPanel() {
        val previous = goalPanel
        goalPanel = null
        if (previous != null) {
            service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(previous.windowToken, 0)
            detach(previous)
        }
    }

    private fun renderGuidance(update: GuidanceUpdate) {
        if (disposed) return
        clearHighlight()
        clearGuidancePanel()
        var instructionTarget: AccessibleUiElement? = null
        val message = when (update.status) {
            GuidanceStatus.IDLE -> return
            GuidanceStatus.WAITING_FOR_SCREEN -> service.getString(R.string.guidance_waiting)
            GuidanceStatus.PLANNING -> service.getString(R.string.guidance_planning)
            GuidanceStatus.UNCERTAIN -> update.result?.let(::decisionMessage) ?: service.getString(R.string.guidance_uncertain)
            GuidanceStatus.DONE -> (update.result?.let(::decisionMessage) ?: "") + "\n" + service.getString(R.string.guidance_confirm_needed)
            GuidanceStatus.NEXT -> {
                val request = update.request ?: return
                val result = update.result ?: return
                val snapshot = request.snapshot
                val index = result.index ?: return
                val element = snapshot.observation.elements.getOrNull(index) ?: return
                if (!controller.canPresent(request) || !ScreenDecisionProtocol.grounded(result, snapshot) ||
                    currentGuidanceSnapshot()?.key != snapshot.key || state.snapshot != snapshot.observation
                ) return
                showHighlight(element)
                instructionTarget = element
                if (BuildConfig.DEBUG) Log.d("ScreenlyGuidance",
                    "revision=${snapshot.key.revision} index=$index mode=${result.mode} action=${result.action} ms=${result.millis}")
                decisionMessage(result)
            }
        }
        showGuidanceMessage(message, instructionTarget)
    }

    private fun showGuidanceMessage(message: String, target: AccessibleUiElement? = null) {
        clearGuidancePanel()
        val area = usableScreenBounds()
        val view = TextView(service).apply {
            text = message
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 28, 63, 95))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val params = overlayParams(min(dp(320), area.width()), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = area.left
            y = area.top
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (target != null) view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (disposed || guidancePanel !== view || view.height <= 0) return@addOnLayoutChangeListener
            val targetBounds = Rect(target.left, target.top, target.right, target.bottom)
            val topPlacement = Rect(area.left, area.top, area.left + params.width, area.top + view.height)
            val nextY = if (Rect.intersects(topPlacement, targetBounds)) {
                (area.bottom - view.height).coerceAtLeast(area.top)
            } else area.top
            if (params.y != nextY) {
                params.y = nextY
                try { windowManager.updateViewLayout(view, params) }
                catch (_: IllegalArgumentException) { clearGuidancePanel() }
            }
        }
        if (attach(view, params)) guidancePanel = view
    }

    private fun clearGuidancePanel() {
        val previous = guidancePanel
        guidancePanel = null
        previous?.let(::detach)
    }

    @SuppressLint("ClickableViewAccessibility") // Handles only ACTION_OUTSIDE; normal clicks use ScrollView.
    private fun showPicker() {
        if (disposed || picker != null) return
        val snapshot = state.snapshot ?: return
        val revision = state.revision
        val display = windowManager.currentWindowMetrics.bounds
        val candidates = snapshot.elements.filter {
            it.clickable && it.enabled && it.intersectsScreen(display.width(), display.height())
        }
        val area = usableScreenBounds()
        if (area.width() <= 0 || area.height() <= 0) return
        val view = ScrollView(service)
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(service).apply {
            text = service.getString(R.string.picker_title, snapshot.packageName)
            textSize = 18f
            setTextColor(Color.BLACK)
        })
        content.addView(Button(service).apply {
            setText(R.string.clear_highlight)
            setOnClickListener { clearHighlight(); closePicker() }
        })
        content.addView(Button(service).apply {
            setText(R.string.close_picker)
            setOnClickListener { closePicker() }
        })
        if (candidates.isEmpty()) {
            content.addView(TextView(service).apply {
                setText(R.string.no_highlight_candidates)
                setTextColor(Color.BLACK)
            })
        }
        candidates.forEach { element ->
            content.addView(Button(service).apply {
                isAllCaps = false
                val label = elementLabel(element, snapshot.elements)
                text = service.getString(
                    R.string.highlight_candidate, label,
                    element.left, element.top, element.right, element.bottom
                )
                setOnClickListener {
                    if (disposed || picker !== view) return@setOnClickListener
                    closePicker()
                    // Re-read the app before using coordinates from the picker.
                    if (refreshObservation() && state.canSelect(snapshot, revision, element)) showHighlight(element)
                    else Toast.makeText(service, R.string.screen_changed, Toast.LENGTH_SHORT).show()
                }
            })
        }
        view.apply {
            addView(content)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    outsideDismissalDownTime = event.downTime
                    closePicker()
                    true
                } else false
            }
        }
        val width = min(dp(360), area.width())
        val height = min(dp(480), area.height() * 2 / 3).coerceAtLeast(1)
        val params = overlayParams(width, height).apply {
            x = area.left + (area.width() - width) / 2
            y = area.top + (area.height() - height) / 2
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        if (attach(view, params)) picker = view
    }

    private fun elementLabel(element: AccessibleUiElement, all: List<AccessibleUiElement>): String {
        val descendant = all.firstOrNull {
            it !== element && (it.text != null || it.contentDescription != null) &&
                it.left >= element.left && it.top >= element.top &&
                it.right <= element.right && it.bottom <= element.bottom
        }
        return element.text ?: element.contentDescription ?: descendant?.text
            ?: descendant?.contentDescription ?: element.viewId ?: element.className
            ?: service.getString(R.string.unlabelled_element)
    }

    private fun showHighlight(element: AccessibleUiElement) {
        val display = windowManager.currentWindowMetrics.bounds
        if (disposed || !element.intersectsScreen(display.width(), display.height())) return
        clearHighlight()
        val view = HighlightView(service, element)
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (attach(view, params)) highlight = view
    }

    private fun closePicker() {
        val previousPicker = picker
        picker = null
        previousPicker?.let(::detach)
    }

    private fun clearHighlight() {
        val previousHighlight = highlight
        highlight = null
        previousHighlight?.let(::detach)
    }

    private fun detach(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // Idempotent cleanup also handles windows already removed by Android.
        }
    }

    @SuppressLint("RtlHardcoded") // x/y are absolute screen coordinates, independent of layout direction.
    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun usableScreenBounds(): Rect {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return Rect(
            insets.left, insets.top,
            metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom
        )
    }

    private fun attach(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (_: WindowManager.BadTokenException) {
            Log.w("ScreenlyAccessibility", "Overlay unavailable: accessibility service token is no longer valid.")
            false
        } catch (_: WindowManager.InvalidDisplayException) {
            Log.w("ScreenlyAccessibility", "Overlay unavailable: display was removed.")
            false
        }
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        val nextSession = AtomicLong()
    }
}

/** Converts absolute accessibility bounds to the overlay's actual on-screen origin. */
@SuppressLint("ViewConstructor") // Created programmatically with a target; never inflated from XML.
private class HighlightView(
    service: AccessibilityService,
    element: AccessibleUiElement
) : View(service) {
    private val bounds = Rect(element.left, element.top, element.right, element.bottom)
    private val origin = IntArray(2)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 170, 230)
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(origin)
        canvas.withTranslation(-origin[0].toFloat(), -origin[1].toFloat()) {
            drawRect(bounds, outline)
        }
    }
}
