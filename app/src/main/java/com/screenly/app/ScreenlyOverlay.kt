package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.graphics.withTranslation
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import com.screenly.app.ai.navigation.NavigationEngine
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
    private var dockRight = true
    private var dockY: Int? = null
    private var snapAnimator: ValueAnimator? = null
    private var disposed = false
    private var bubble: View? = null
    private var picker: View? = null
    private var assistantMenu: View? = null
    private var featureController: ScreenlyFeaturePanel? = null
    private val featureSession = AssistantSessionStore()
    private val sessionId = nextSession.incrementAndGet()
    private var selectionInvalidated = false
    private var guideTarget: AccessibleUiElement? = null
    private var guideStep = 0
    private var guideLabel: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inference = LocalInference(service)
    private val engine = NavigationEngine { prompt -> inference.initialize(); inference.generate(prompt) }
    private val guidanceController = GuidanceController(
        scope,
        plan = { goal, snapshot, history ->
            engine.decideCandidates(goal, snapshot.planningElements, snapshot.candidateIndices,
                snapshot.observation.packageName, verifiedRoutes(goal, snapshot), history)
        },
        refresh = { if (refreshObservation()) currentGuidanceSnapshot() else null },
        render = ::renderGuidance
    )
    private val bubbleBitmap by lazy {
        BitmapFactory.decodeResource(service.resources, R.drawable.screenly_bubble,
            BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false })
    }
    private var highlight: HighlightView? = null
    private var outsideDismissalDownTime: Long? = null
    private var bubbleClickClosesPicker = false

    fun updateObservation(next: ScreenObservation) {
        if (disposed) return
        selectionInvalidated = false
        if (state.update(next)) {
            closePicker()
            closeMenu(immediate = true)
            clearHighlight()
        }
        showBubble()
        guidanceController.observe(currentGuidanceSnapshot())
    }

    fun clearSelection() {
        state.invalidateSelection()
        selectionInvalidated = true
        closePicker()
        closeMenu(immediate = true)
        clearHighlight()
        guidanceController.observe(null)
    }

    fun clearObservation(keepGuidance: Boolean = false) {
        state.clear()
        selectionInvalidated = true
        if (keepGuidance) guidanceController.observe(null) else guidanceController.stop()
        clearHighlight()
        snapAnimator?.cancel()
        snapAnimator = null
        closePicker()
        closeMenu(immediate = true)
        if (keepGuidance) return
        featureController?.dismiss(immediate = true, restoreBubble = false)
        featureController = null
        featureSession.clear()
        clearHighlight()
        val previousBubble = bubble
        bubble = null
        previousBubble?.let(::detach)
        outsideDismissalDownTime = null
        bubbleClickClosesPicker = false
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        guidanceController.close()
        clearObservation()
        scope.launch {
            try { inference.close() }
            catch (_: LocalInferenceException) { Log.w("ScreenlyGuidance", "Local engine cleanup failed.") }
        }
        scope.cancel()
    }

    private fun currentGuidanceSnapshot(): GuidanceSnapshot? {
        if (disposed || selectionInvalidated) return null
        val observation = state.snapshot ?: return null
        val display = windowManager.currentWindowMetrics.bounds
        return guidanceSnapshot(SnapshotKey(sessionId, state.revision), observation, display.width(), display.height())
    }

    private fun verifiedRoutes(goal: String, snapshot: GuidanceSnapshot): Set<Int> = SettingsWorkflow.verifiedRoutes(
        goal, snapshot.observation.packageName,
        Build.MANUFACTURER.equals("Google", ignoreCase = true) || Build.MANUFACTURER.equals("Android", ignoreCase = true),
        snapshot.planningElements, snapshot.candidateIndices, Build.VERSION.SDK_INT
    )

    private fun startGuidance(goal: String) {
        guideStep = 0
        guideLabel = null
        refreshObservation()
        guidanceController.start(goal)
    }

    private fun confirmGuidance() {
        guidanceController.stop()
        featureSession.guidance = GuidancePanelState(
            service.getString(R.string.guidance_user_confirmed),
            service.getString(R.string.guidance_confirmation_notice), completed = true
        )
        featureController?.updateGuidance()
    }

    private fun renderGuidance(update: GuidanceUpdate) {
        if (disposed) return
        clearHighlight()
        featureSession.guidance = when (update.status) {
            GuidanceStatus.IDLE -> null
            GuidanceStatus.WAITING_FOR_SCREEN -> GuidancePanelState(
                service.getString(R.string.guidance_waiting), service.getString(R.string.guidance_manual_notice), guideStep.coerceAtLeast(1)
            )
            GuidanceStatus.PLANNING -> GuidancePanelState(
                service.getString(R.string.guidance_planning), service.getString(R.string.guidance_manual_notice),
                guideStep.coerceAtLeast(1), planning = true
            )
            GuidanceStatus.UNCERTAIN -> GuidancePanelState(
                service.getString(if (update.result?.decision?.failure != null)
                    R.string.guidance_uncertain_model_unavailable else R.string.guidance_uncertain),
                service.getString(R.string.guidance_manual_notice), guideStep.coerceAtLeast(1)
            )
            GuidanceStatus.NEXT -> {
                val request = update.request ?: return
                val result = update.result?.decision ?: return
                val current = currentGuidanceSnapshot() ?: return
                if (!guidanceController.canPresent(request)) return
                val element = validatedGuidanceTarget(request.snapshot, current, request.goal,
                    result.elementIndex, verifiedRoutes(request.goal, current)) ?: return
                val label = current.planningElements[result.elementIndex!!].let { it.text ?: it.contentDescription }
                    ?: service.getString(R.string.unlabelled_element)
                if (guideLabel != label) guideStep++
                guideLabel = label
                showHighlight(element)
                guideTarget = element
                GuidancePanelState(service.getString(R.string.guidance_step, label),
                    service.getString(if (result.failure != null) R.string.guidance_rule_unavailable
                        else if (result.source == com.screenly.app.ai.navigation.DecisionSource.MODEL)
                            R.string.guidance_model_source else R.string.guidance_rule_source), guideStep.coerceAtLeast(1))
            }
        }
        if (BuildConfig.DEBUG && update.result != null) {
            val result = update.result.decision
            Log.d("ScreenlyGuidance", "decision session=$sessionId revision=${update.request?.snapshot?.key?.revision} " +
                "index=${result.elementIndex} source=${result.source} outcome=${result.modelOutcome} ms=${result.totalMillis}")
        }
        featureController?.updateGuidance()
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(BUBBLE_SIZE_DP)
        val area = usableScreenBounds()
        if (area.width() <= 0 || area.height() <= 0) return
        val (edgeX, retainedY) = BubbleDocking.position(
            area.left, area.top, area.right, area.bottom,
            size, size, dp(CORNER_MARGIN_DP), dockRight,
            dockY ?: (area.bottom - size - dp(CORNER_MARGIN_DP))
        )
        dockY = retainedY
        val params = overlayParams(size, size).apply { x = edgeX; y = retainedY }
        val view = FrameLayout(service).apply {
            contentDescription = service.getString(R.string.assistant_bubble_description)
            elevation = dp(8).toFloat()
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(dp(2), Color.WHITE)
            }
            clipToOutline = true
            setOnClickListener {
                if (disposed || bubble !== it) return@setOnClickListener
                val closesPanel = picker != null || assistantMenu != null ||
                    featureController != null || bubbleClickClosesPicker
                bubbleClickClosesPicker = false
                if (closesPanel) {
                    closePicker()
                    closeMenu()
                    featureController?.dismiss()
                } else showMenu()
            }
        }
        view.addView(ImageView(service).apply {
            setImageBitmap(bubbleBitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        enableDragging(view, params)
        if (attach(view, params)) bubble = view
    }

    @SuppressLint("ClickableViewAccessibility") // Tap/drag gestures call performClick for accessibility.
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
                    snapAnimator?.cancel()
                    snapAnimator = null
                    bubbleClickClosesPicker = picker != null || assistantMenu != null ||
                        featureController != null || outsideDismissalDownTime == event.downTime
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
                        closeMenu(immediate = true)
                        featureController?.dismiss(immediate = true)
                        val area = usableScreenBounds()
                        params.x = (startX + deltaX.toInt()).coerceIn(
                            area.left, (area.right - params.width).coerceAtLeast(area.left)
                        )
                        params.y = (startY + deltaY.toInt()).coerceIn(
                            area.top, (area.bottom - params.height).coerceAtLeast(area.top)
                        )
                        updateBubbleLayout(touchedView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) snapToNearestSide(touchedView, params)
                    else touchedView.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToNearestSide(touchedView, params)
                    bubbleClickClosesPicker = false
                }
            }
            true
        }
    }

    private fun snapToNearestSide(view: View, params: WindowManager.LayoutParams) {
        if (disposed || bubble !== view) return
        val area = usableScreenBounds()
        dockRight = BubbleDocking.nearestSide(
            params.x + params.width / 2, area.left, area.right
        )
        val target = BubbleDocking.position(
            area.left, area.top, area.right, area.bottom,
            params.width, params.height, dp(CORNER_MARGIN_DP), dockRight, params.y
        )
        dockY = target.second
        val initialX = params.x
        val initialY = params.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (disposed || bubble !== view) {
                    cancel()
                } else {
                    val fraction = animation.animatedValue as Float
                    params.x = initialX + ((target.first - initialX) * fraction).toInt()
                    params.y = initialY + ((target.second - initialY) * fraction).toInt()
                    updateBubbleLayout(view, params)
                }
            }
            start()
        }
    }

    private fun updateBubbleLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            clearObservation()
        }
    }

    /** Compact native controls; the target app still receives touches outside the menu. */
    @SuppressLint("ClickableViewAccessibility") // Only ACTION_OUTSIDE dismisses the panel.
    private fun showMenu() {
        if (disposed || assistantMenu != null) return
        closePicker()
        snapAnimator?.end()
        snapAnimator = null
        val area = usableScreenBounds()
        if (area.width() <= 0 || area.height() <= 0) return
        val width = min(dp(MENU_WIDTH_DP), area.width())
        val height = min(dp(MENU_HEIGHT_DP), area.height())
        var expectedView: View? = null
        val view = FloatingAssistantViews.menu(service, onAction = actionClick@ { action ->
            if (disposed || assistantMenu !== expectedView) return@actionClick
            closeMenu(immediate = false, restoreBubble = false)
            val bubbleView = bubble ?: return@actionClick
            val controller = ScreenlyFeaturePanel(
                service = service,
                windowManager = windowManager,
                bubble = bubbleView,
                session = featureSession,
                layoutParams = { width, height -> featurePanelParams(width, height) },
                refreshObservation = refreshObservation,
                currentObservation = { state.snapshot },
                startGuidance = ::startGuidance,
                retryGuidance = { guidanceController.retry() },
                stopGuidance = { guidanceController.stop() },
                confirmGuidance = ::confirmGuidance,
                manualPicker = {
                    if (refreshObservation()) showPicker()
                    else Toast.makeText(service, R.string.screen_changed, Toast.LENGTH_SHORT).show()
                },
                onClosed = { featureController = null }
            )
            featureController = controller
            controller.open(action)
        })
        expectedView = view
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                outsideDismissalDownTime = event.downTime
                closeMenu()
                true
            } else false
        }
        val size = dp(BUBBLE_SIZE_DP).toFloat()
        val panelParams = sidePanelParams(width, height)
        val bubbleParams = bubble?.layoutParams as? WindowManager.LayoutParams
        val bubbleX = bubbleParams?.x ?: panelParams.x
        val bubbleY = bubbleParams?.y ?: (dockY ?: panelParams.y)
        // Animate from the actual bubble position, including the user's chosen height.
        view.pivotX = (bubbleX + size / 2 - panelParams.x).coerceIn(0f, width.toFloat())
        view.pivotY = (bubbleY + size / 2 - panelParams.y).coerceIn(0f, height.toFloat())
        view.alpha = 0f
        view.scaleX = size / width
        view.scaleY = size / height
        if (attach(view, panelParams)) {
            assistantMenu = view
            bubble?.let { bubbleView ->
                bubbleView.animate().cancel()
                bubbleView.animate().alpha(0f).scaleX(0.76f).scaleY(0.76f)
                    .setDuration(170L).withEndAction {
                        if (assistantMenu === view) bubbleView.visibility = View.INVISIBLE
                    }.start()
            }
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun sidePanelParams(
        width: Int, height: Int, area: Rect = usableScreenBounds()
    ): WindowManager.LayoutParams {
        val bubbleY = (bubble?.layoutParams as? WindowManager.LayoutParams)?.y
            ?: dockY ?: area.bottom - dp(BUBBLE_SIZE_DP)
        val (edgeX, alignedY) = BubbleDocking.panelPosition(
            area.left, area.top, area.right, area.bottom,
            width, height, dp(CORNER_MARGIN_DP), dockRight,
            bubbleY, dp(BUBBLE_SIZE_DP)
        )
        return overlayParams(width, height).apply {
            x = edgeX
            y = alignedY
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
    }

    private fun featurePanelParams(width: Int, height: Int): WindowManager.LayoutParams {
        val area = usableScreenBounds()
        val insets = windowManager.currentWindowMetrics.windowInsets
        if (insets.isVisible(WindowInsets.Type.ime())) {
            val keyboardTop = windowManager.currentWindowMetrics.bounds.height() -
                insets.getInsets(WindowInsets.Type.ime()).bottom
            area.bottom = min(area.bottom, keyboardTop).coerceAtLeast(area.top)
        }
        val adjustedWidth = min(width, (area.width() - dp(12)).coerceAtLeast(1))
        return sidePanelParams(adjustedWidth, height, area).apply {
            guideTarget?.let { target ->
                // Keep the current compact panel clear of the control the user must tap.
                val locations = listOf(x to y, x to area.top, x to (area.bottom - height),
                    area.left to area.top, (area.right - adjustedWidth) to (area.bottom - height))
                locations.firstOrNull { (left, top) ->
                    !Rect.intersects(Rect(left, top, left + adjustedWidth, top + height),
                        Rect(target.left - dp(8), target.top - dp(8), target.right + dp(8), target.bottom + dp(8)))
                }?.let { (left, top) -> x = left; y = top }
            }
        }
    }

    private fun closeMenu(immediate: Boolean = false, restoreBubble: Boolean = true) {
        val previous = assistantMenu ?: return
        assistantMenu = null
        previous.animate().cancel()
        if (immediate || disposed) {
            detach(previous)
        } else {
            val size = dp(BUBBLE_SIZE_DP).toFloat()
            val width = previous.layoutParams.width
            val height = previous.layoutParams.height
            previous.animate().alpha(0f).scaleX(size / width).scaleY(size / height)
                .setDuration(170L).withEndAction { detach(previous) }.start()
        }
        if (restoreBubble) bubble?.let { bubbleView ->
            bubbleView.animate().cancel()
            bubbleView.visibility = View.VISIBLE
            if (immediate || disposed) {
                bubbleView.alpha = 1f
                bubbleView.scaleX = 1f
                bubbleView.scaleY = 1f
            } else {
                bubbleView.alpha = 0f
                bubbleView.scaleX = 0.76f
                bubbleView.scaleY = 0.76f
                bubbleView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180L).start()
            }
        }
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
        guideTarget = null
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
        const val BUBBLE_SIZE_DP = 72
        const val CORNER_MARGIN_DP = 12
        const val MENU_WIDTH_DP = 220
        const val MENU_HEIGHT_DP = 198
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
