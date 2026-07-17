package com.beqa.body.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Build
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

    /**
     * Roots of all windows on a SPECIFIC display, via getWindowsOnAllDisplays() (API 30+).
     * Lets the reader/resolver target a hidden virtual display (e.g. a quiet-shot display)
     * instead of the default-display `windows`. Empty if the display is gone/unsupported.
     */
    fun rootsForDisplay(displayId: Int): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val all = windowsOnAllDisplays ?: return emptyList()
            val wins = all.get(displayId) ?: return emptyList()
            wins.mapNotNull { w -> try { w.root } catch (e: Exception) { null } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * DIAGNOSTIC: per-display window summary via getWindowsOnAllDisplays() (API 30+).
     * Returns (displayId, windowCount, [rootPackage...]) so we can test whether this
     * service can see apps on a SECONDARY/virtual display (e.g. a hidden scrcpy display),
     * which the default `windows` property does not expose. Read-only; does not act.
     */
    fun displaySummary(): List<Triple<Int, Int, List<String?>>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val all = windowsOnAllDisplays ?: return emptyList()
            val out = mutableListOf<Triple<Int, Int, List<String?>>>()
            for (i in 0 until all.size()) {
                val displayId = all.keyAt(i)
                val wins = all.valueAt(i) ?: emptyList()
                val pkgs = wins.map { w ->
                    try { w.root?.packageName?.toString() } catch (e: Exception) { null }
                }
                out.add(Triple(displayId, wins.size, pkgs))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        @Volatile
        var instance: BodyAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
