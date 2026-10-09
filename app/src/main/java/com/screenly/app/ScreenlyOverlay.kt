package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
<<<<<<< feat/backend-tests
import android.graphics.drawable.GradientDrawable
=======
import android.os.Build
import android.text.InputFilter
import android.text.InputType
>>>>>>> local
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
<<<<<<< feat/backend-tests
=======
import android.widget.EditText
import android.widget.ImageView
>>>>>>> local
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.graphics.withTranslation
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import com.screenly.app.ai.navigation.NavigationEngine
import com.screenly.app.ai.navigation.NavigationRules
import com.screenly.app.ai.navigation.SettingsWorkflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
<<<<<<< feat/backend-tests
=======
    private var assistantMenu: View? = null
    private var infoPanel: View? = null
    private var goalPanel: View? = null
    private var guidancePanel: View? = null
    private var lastGoal = ""
    private val sessionId = nextSession.incrementAndGet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inference = LocalInference(service)
    private val engine = NavigationEngine { prompt -> inference.initialize(); inference.generate(prompt) }
    private val controller = GuidanceController(
        scope,
        plan = { goal, snapshot, history ->
            engine.decideCandidates(goal, snapshot.planningElements, snapshot.candidateIndices,
                snapshot.observation.packageName, verifiedRoutes(goal, snapshot), history)
        },
        refresh = { refreshObservation(); currentGuidanceSnapshot() },
        render = ::renderGuidance
    )
    private val bubbleBitmap by lazy {
        BitmapFactory.decodeResource(service.resources, R.drawable.screenly_bubble,
            BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false })
    }
>>>>>>> local
    private var highlight: HighlightView? = null
    private var outsideDismissalDownTime: Long? = null
    private var bubbleClickClosesPicker = false

    fun updateObservation(next: ScreenObservation) {
        if (disposed) return
        if (state.update(next)) {
            closePicker()
            clearHighlight()
            clearGuidancePanel()
        }
        showBubble()
        controller.observe(currentGuidanceSnapshot())
    }

    fun clearSelection() {
        state.invalidateSelection()
        closePicker()
        clearHighlight()
        clearGuidancePanel()
        controller.observe(null)
    }

    fun clearObservation() {
        state.clear()
        closePicker()
<<<<<<< feat/backend-tests
=======
        closeMenu()
        closeInfoPanel()
        closeGoalPanel()
>>>>>>> local
        clearHighlight()
        clearGuidancePanel()
        controller.observe(null)
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
        // Main.immediate enters close's NonCancellable block before the owner scope is cancelled.
        scope.launch {
            try { inference.close() }
            catch (_: LocalInferenceException) { Log.w("ScreenlyGuidance", "Local engine cleanup failed.") }
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
        val view = TextView(service).apply {
            text = service.getString(R.string.assistant_bubble)
            contentDescription = service.getString(R.string.assistant_bubble_description)
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(28, 63, 95))
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener {
                if (disposed || bubble !== it) return@setOnClickListener
<<<<<<< feat/backend-tests
                val closesPicker = picker != null || bubbleClickClosesPicker
                bubbleClickClosesPicker = false
                if (closesPicker) closePicker()
                else if (refreshObservation()) showPicker()
=======
                val closesPanel = picker != null || assistantMenu != null ||
                    infoPanel != null || goalPanel != null || bubbleClickClosesPicker
                bubbleClickClosesPicker = false
                if (closesPanel) {
                    closePicker()
                    closeMenu()
                    closeInfoPanel()
                    closeGoalPanel()
                } else showMenu()
>>>>>>> local
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
<<<<<<< feat/backend-tests
                    bubbleClickClosesPicker = picker != null || outsideDismissalDownTime == event.downTime
=======
                    bubbleClickClosesPicker = picker != null || assistantMenu != null ||
                        infoPanel != null || goalPanel != null || outsideDismissalDownTime == event.downTime
>>>>>>> local
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
<<<<<<< feat/backend-tests
=======
                        closeMenu()
                        closeInfoPanel()
                        closeGoalPanel()
>>>>>>> local
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

<<<<<<< feat/backend-tests
=======

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
                AssistantAction.GUIDE_ME, AssistantAction.ASK_AI -> showGoalPanel()
                AssistantAction.EXPLAIN -> showInfoPanel(
                    R.string.assistant_action_explain, R.string.assistant_explain_unavailable
                )
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
            service, service.getString(title), service.getString(message)
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
        return guidanceSnapshot(SnapshotKey(sessionId, state.revision), observation, display.width(), display.height())
    }

    private fun verifiedRoutes(goal: String, snapshot: GuidanceSnapshot): Set<Int> = SettingsWorkflow.verifiedRoutes(
        goal, snapshot.observation.packageName,
        Build.MANUFACTURER.equals("Google", ignoreCase = true) || Build.MANUFACTURER.equals("Android", ignoreCase = true),
        snapshot.planningElements, snapshot.candidateIndices
    )

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
            controller.start(goal)
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(service).apply { setText(label); isAllCaps = false; setOnClickListener { action() } })
        }
        button(R.string.guidance_start, ::submit)
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
        val message = when (update.status) {
            GuidanceStatus.IDLE -> return
            GuidanceStatus.WAITING_FOR_SCREEN -> service.getString(R.string.guidance_waiting)
            GuidanceStatus.PLANNING -> service.getString(R.string.guidance_planning)
            GuidanceStatus.UNCERTAIN -> service.getString(
                if (update.result?.decision?.failure != null) R.string.guidance_uncertain_model_unavailable else R.string.guidance_uncertain
            )
            GuidanceStatus.NEXT -> {
                val request = update.request ?: return
                val result = update.result ?: return
                val snapshot = request.snapshot
                val index = result.decision.elementIndex ?: return
                val element = snapshot.observation.elements.getOrNull(index) ?: return
                if (!controller.canPresent(request) || index !in snapshot.candidateIndices ||
                    !state.canSelect(snapshot.observation, snapshot.key.revision, element) ||
                    NavigationRules.rejection(SettingsWorkflow.canonicalGoal(request.goal), index,
                        snapshot.planningElements, snapshot.candidateIndices, verifiedRoutes(request.goal, snapshot)) != null
                ) return
                showHighlight(element)
                val label = snapshot.planningElements[index].text ?: snapshot.planningElements[index].contentDescription
                    ?: service.getString(R.string.unlabelled_element)
                if (BuildConfig.DEBUG) Log.d("ScreenlyGuidance",
                    "next session=$sessionId revision=${snapshot.key.revision} index=$index source=${result.decision.source} " +
                        "outcome=${result.decision.modelOutcome} ms=${result.decision.totalMillis}")
                service.getString(if (result.decision.failure != null) R.string.guidance_step_model_unavailable else R.string.guidance_step, label)
            }
        }
        showGuidanceMessage(message)
    }

    private fun showGuidanceMessage(message: String) {
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
        if (attach(view, params)) guidancePanel = view
    }

    private fun clearGuidancePanel() {
        val previous = guidancePanel
        guidancePanel = null
        previous?.let(::detach)
    }

>>>>>>> local
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
