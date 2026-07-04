package com.beqa.body.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service — the app's "eyes". Tracks the foreground package and exposes
 * the live window roots for the screen reader. Screen serialization/query logic lives
 * in the screen module; this class is just the connection + a stable handle to the tree.
 */
class BodyAccessibilityService : AccessibilityService() {

    @Volatile
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { foregroundPackage = it.toString() }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Root of the currently-focused window, or null. */
    fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        null
    }

    /** Roots of all interactive windows (dialogs, IME, overlays), falling back to the active root. */
    fun allRoots(): List<AccessibilityNodeInfo> = try {
        val roots = windows?.mapNotNull { w -> try { w.root } catch (e: Exception) { null } } ?: emptyList()
        if (roots.isNotEmpty()) roots else listOfNotNull(activeRoot())
    } catch (e: Exception) {
        listOfNotNull(activeRoot())
    }

    companion object {
        @Volatile
        var instance: BodyAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
