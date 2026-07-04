package com.beqa.body.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.beqa.body.security.BridgeTokenStore
import com.beqa.body.server.AuthRateLimiter
import com.beqa.body.server.BridgeHttpServer

class BodyForegroundService : Service() {

    private var server: BridgeHttpServer? = null

    companion object {
        const val CHANNEL = "body_service"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.beqa.body.action.STOP"
        const val TAG = "BodyBridge"

        @Volatile
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL,
            "Body",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        if (server == null) {
            try {
                val tokens = BridgeTokenStore(this).also { it.getOrCreate() }
                server = BridgeHttpServer(tokens, AuthRateLimiter(), this).also { it.start() }
                Log.i(TAG, "bridge listening on ${BridgeHttpServer.BIND_ADDR}:${BridgeHttpServer.PORT}")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start bridge", e)
            }
        }
        isRunning = true
        return START_STICKY
    }

    private fun stopBridge() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(
                ComponentName(this, "com.beqa.body.MainActivity")
            ),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BodyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = Notification.Action.Builder(
            null,
            "STOP",
            stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Body")
            .setContentText("running · local only")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .build()
    }

    override fun onDestroy() {
        stopBridge()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
