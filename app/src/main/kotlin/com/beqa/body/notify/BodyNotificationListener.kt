package com.beqa.body.notify

import android.service.notification.NotificationListenerService

/**
 * Notification listener. In M1 this is only a bindable shell so onboarding can grant
 * notification access and the app can report it as connected; reading/replying to
 * notifications is added in later increments.
 */
class BodyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    companion object {
        @Volatile
        var connected = false

        fun isConnected(): Boolean = connected
    }
}
