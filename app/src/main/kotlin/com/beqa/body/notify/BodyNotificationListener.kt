package com.beqa.body.notify

import android.service.notification.NotificationListenerService

/**
 * Notification listener, the app's "ears". Exposes a live handle so the reader can
 * enumerate active notifications; reading/replying logic lives in the notify module.
 */
class BodyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        instance = this
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var connected = false

        @Volatile
        var instance: BodyNotificationListener? = null

        fun isConnected(): Boolean = connected && instance != null
    }
}
