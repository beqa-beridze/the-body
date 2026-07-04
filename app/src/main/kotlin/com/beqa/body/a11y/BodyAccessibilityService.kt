package com.beqa.body.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service. In M1 this is only a bindable shell so onboarding can enable
 * it and the app can report it as connected; the screen-reading/action logic is added
 * in later increments. The singleton seam is established now so those increments have a
 * stable handle to the live service.
 */
class BodyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op until perception is added
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: BodyAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
