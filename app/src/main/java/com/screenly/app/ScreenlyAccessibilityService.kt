package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ScreenlyAccessibilityService : AccessibilityService() {
    private var overlay: ScreenlyOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastCapture: Long? = null
    private var lastObservation: ScreenObservation? = null
    private var observationPending = false
    private var receiverRegistered = false
    private var imageAllowed = false
    private val capture = Runnable {
        observationPending = false
        observeScreen()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> clearObservation()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> scheduleObservation()
                MainActivity.ACTION_APP_VISIBILITY -> {
                    val visible = intent.getBooleanExtra(MainActivity.EXTRA_VISIBLE, false)
                    overlay?.setOwnAppVisible(visible)
                    if (!visible) scheduleObservation()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay?.dispose()
        overlay = ScreenlyOverlay(this) { observeScreen() }
        overlay?.setOwnAppVisible(MainActivity.isForeground)
        registerScreenReceiver()
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Service connected. Open Android Settings to inspect its interface.")
        }
        observeScreen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never read event.text: text-change events may contain sensitive input.
        if (event == null) return
        if (event.packageName?.toString() == packageName &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val targetWindow = lastObservation?.windowId
        val appNavigation = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() != packageName
        val targetScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.windowId == targetWindow
        val targetWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.windowId == targetWindow &&
            event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0
        val targetContentChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.windowId == targetWindow
        if (appNavigation || targetScroll || targetWindowChange) overlay?.clearSelection()
        else if (targetContentChange) overlay?.clearVisibleTargets()
        // Keep only a scheduled capture, never an event or live node across callbacks.
        scheduleObservation()
    }

    private fun scheduleObservation() {
        if (overlay == null) return
        if (!canObserve()) {
            clearObservation()
            return
        }
        if (!observationPending) {
            observationPending = true
            handler.postDelayed(capture, observationDelayMillis(lastCapture, SystemClock.uptimeMillis()))
        }
    }

    private fun canObserve(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun observeScreen(): Boolean {
        handler.removeCallbacks(capture)
        observationPending = false
        if (overlay == null || !canObserve()) {
            clearObservation()
            return false
        }
        lastCapture = SystemClock.uptimeMillis()
        val root = activeApplicationRoot()
        if (root == null) {
            clearObservation(showWaiting = true, preserveCapturedQuestion = true)
            if (BuildConfig.DEBUG) Log.d(TAG, "Active window unavailable; observation skipped.")
            return false
        }

        try {
            val targetPackage = root.packageName?.toString()
            if (targetPackage == null || targetPackage == packageName) {
                clearObservation(preserveCapturedQuestion = true)
                return false
            }

            val elements = extractElements(root)
            val appWindows = windows
            val appBounds = Rect()
            try {
                appWindows.firstOrNull { it.id == root.windowId }?.getBoundsInScreen(appBounds)
                val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val safe = Rect(insets.left, insets.top, metrics.bounds.width() - insets.right,
                    metrics.bounds.height() - insets.bottom)
                if (!appBounds.intersect(safe)) imageAllowed = false
                if (appWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        (it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id != root.windowId) }) imageAllowed = false
                fun exclude(bounds: Rect) {
                    val cropped = ImageCropBounds(appBounds.left, appBounds.top, appBounds.right, appBounds.bottom)
                        .excluding(ImageCropBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    if (cropped == null) imageAllowed = false
                    else appBounds.set(cropped.left, cropped.top, cropped.right, cropped.bottom)
                }
                for (window in appWindows) {
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    if (!Rect.intersects(appBounds, bounds)) continue
                    if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) exclude(bounds)
                    if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        val overlayRoot = window.root
                        try {
                            if (overlayRoot?.packageName?.toString() != packageName) exclude(bounds)
                        } finally { overlayRoot?.let(::recycleIfNeeded) }
                    }
                }
            } finally {
                if (Build.VERSION.SDK_INT < 33) {
                    @Suppress("DEPRECATION")
                    appWindows.forEach { it.recycle() }
                }
            }
            val next = ScreenObservation(targetPackage, root.windowId, elements, imageAllowed,
                appBounds.left, appBounds.top, appBounds.right, appBounds.bottom)
            overlay?.updateObservation(next)
            if (BuildConfig.DEBUG && next != lastObservation) {
                Log.d(TAG, "Screen package=${sanitizeObservationText(targetPackage)} elements=${elements.size}")
            }
            lastObservation = next
            return true
        } finally {
            recycleIfNeeded(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun activeApplicationRoot(): AccessibilityNodeInfo? {
        // Overlay windows must never replace the app being inspected.
        val appWindows = windows
        try {
            val active = appWindows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
            } ?: appWindows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: appWindows.firstOrNull {
                // Our focusable goal editor temporarily owns focus. Keep observing only the
                // same still-present application window, under the existing unlock/privacy guards.
                overlay?.isEnteringGoal == true && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.id == lastObservation?.windowId
            }
            return active?.takeIf { it.displayId == Display.DEFAULT_DISPLAY }?.root
        } finally {
            if (Build.VERSION.SDK_INT < 33) appWindows.forEach { it.recycle() }
        }
    }

    private fun extractElements(root: AccessibilityNodeInfo): List<AccessibleUiElement> {
        val elements = mutableListOf<AccessibleUiElement>()
        var visitedNodes = 0
        imageAllowed = true
        var sensitiveScreen = false

        fun visit(node: AccessibilityNodeInfo, depth: Int, parentIndex: Int?) {
            if (visitedNodes >= MAX_NODES || depth > MAX_DEPTH) { imageAllowed = false; return }
            visitedNodes++

            // Skip the entire subtree before accessing any text or descriptions.
            if (node.isPassword ||
                (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
            ) {
                imageAllowed = false
                sensitiveScreen = true
                return
            }

            val editable = node.isEditable
            if (editable) imageAllowed = false
            var capturedIndex = parentIndex
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                capturedIndex = elements.size
                elements += AccessibleUiElement(
                    text = if (editable) null else sanitizeObservationText(node.text),
                    contentDescription = if (editable) null else sanitizeObservationText(node.contentDescription),
                    className = sanitizeObservationText(node.className),
                    viewId = sanitizeObservationText(node.viewIdResourceName),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    checked = node.isChecked,
                    scrollable = node.isScrollable,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    parentIndex = parentIndex,
                    checkable = node.isCheckable,
                    editable = editable,
                    actions = node.actionList.map { it.id }.toList(),
                    range = node.rangeInfo?.let { ControlRange(it.min, it.max, it.current) }
                )
            }

            // Editable controls can expose their value through descendants too.
            if (editable || depth == MAX_DEPTH) {
                if (depth == MAX_DEPTH && node.childCount > 0) imageAllowed = false
                return
            }
            for (index in 0 until node.childCount) {
                if (visitedNodes >= MAX_NODES) { imageAllowed = false; break }
                val child = node.getChild(index)
                if (child == null) {
                    imageAllowed = false
                    visitedNodes++ // Null/stale child slots must also consume the traversal budget.
                    continue
                }
                try {
                    visit(child, depth + 1, capturedIndex)
                } finally {
                    recycleIfNeeded(child)
                }
            }
        }

        visit(root, 0, null)
        if (BuildConfig.DEBUG && visitedNodes >= MAX_NODES) {
            Log.d(TAG, "Observation reached the $MAX_NODES-node traversal limit.")
        }
        // A password/authentication screen cannot be partially summarized to AI.
        return if (sensitiveScreen) emptyList() else elements
    }

    override fun onInterrupt() {
        clearObservation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearObservation(preserveCapturedQuestion = canObserve())
        observeScreen()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun clearObservation(showWaiting: Boolean = false, preserveCapturedQuestion: Boolean = false) {
        handler.removeCallbacks(capture)
        observationPending = false
        lastObservation = null
        overlay?.clearObservation(showWaiting, preserveCapturedQuestion)
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(MainActivity.ACTION_APP_VISIBILITY)
        }
        // Contains an app-private visibility broadcast, so it must be non-exported.
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun disconnect() {
        clearObservation()
        overlay?.dispose()
        overlay = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        lastCapture = null
    }

    @Suppress("DEPRECATION")
    private fun recycleIfNeeded(node: AccessibilityNodeInfo) {
        // Android 13 removed node pooling and deprecated recycle().
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    private companion object {
        const val TAG = "ScreenlyAccessibility"
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
    }
}
