package com.screenly.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
    private val capture = Runnable {
        observationPending = false
        observeScreen()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> clearObservation()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> scheduleObservation()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay?.dispose()
        overlay = ScreenlyOverlay(this) { observeScreen() }
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
        if (appNavigation || targetScroll || targetWindowChange) overlay?.clearSelection()
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
            clearObservation()
            if (BuildConfig.DEBUG) Log.d(TAG, "Active window unavailable; observation skipped.")
            return false
        }

        try {
            val targetPackage = root.packageName?.toString()
            if (targetPackage == null || targetPackage == packageName) {
                clearObservation()
                return false
            }

            val elements = extractElements(root)
            val next = ScreenObservation(targetPackage, root.windowId, elements)
            overlay?.updateObservation(next)
            if (BuildConfig.DEBUG && next != lastObservation) {
                Log.d(TAG, "Screen package=${sanitizeObservationText(targetPackage)} elements=${elements.size}")
                elements.forEachIndexed { index, element ->
                    Log.d(TAG, "element[$index] $element")
                }
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
            // A focusable Screenly text panel is an ACCESSIBILITY_OVERLAY, not an
            // application window. The target app can temporarily lose active/focused
            // status while its hierarchy remains available beneath our panel.
            val applications = appWindows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
            val target = applications.firstOrNull { it.isActive }
                ?: applications.firstOrNull { it.isFocused }
                ?: applications.firstOrNull { it.id == lastObservation?.windowId }
                ?: applications.maxByOrNull { it.layer }
            return target?.root
        } finally {
            if (Build.VERSION.SDK_INT < 33) appWindows.forEach { it.recycle() }
        }
    }

    private fun extractElements(root: AccessibilityNodeInfo): List<AccessibleUiElement> {
        val elements = mutableListOf<AccessibleUiElement>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visitedNodes >= MAX_NODES || depth > MAX_DEPTH) return
            visitedNodes++

            // Skip the entire subtree before accessing any text or descriptions.
            if (node.isPassword ||
                (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
            ) return

            val editable = node.isEditable
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
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
                    bottom = bounds.bottom
                )
            }

            // Editable controls can expose their value through descendants too.
            if (editable || depth == MAX_DEPTH) return
            for (index in 0 until node.childCount) {
                if (visitedNodes >= MAX_NODES) break
                val child = node.getChild(index)
                if (child == null) {
                    visitedNodes++ // Null/stale child slots must also consume the traversal budget.
                    continue
                }
                try {
                    visit(child, depth + 1)
                } finally {
                    recycleIfNeeded(child)
                }
            }
        }

        visit(root, 0)
        if (BuildConfig.DEBUG && visitedNodes >= MAX_NODES) {
            Log.d(TAG, "Observation reached the $MAX_NODES-node traversal limit.")
        }
        return elements
    }

    override fun onInterrupt() {
        clearObservation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearObservation()
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

    private fun clearObservation() {
        handler.removeCallbacks(capture)
        observationPending = false
        lastObservation = null
        overlay?.clearObservation()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
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
