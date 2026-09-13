package com.beqa.body.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.beqa.body.R
import org.json.JSONObject

/**
 * A harmless, self-contained proof that the RemoteInput reply path actually works.
 *
 * The reply code in ExternalActions has existed since M6 and had never once been fired on
 * this device. The only honest way to test it without messaging a real human is to have
 * the app post a notification to ITSELF carrying a real RemoteInput action, drive the
 * normal POST /notifications/reply path against it, and then check whether the text came
 * back out the other end. Same code, same allowlist, same confirmation gate — the only
 * thing that changes is that the recipient is a BroadcastReceiver in this process.
 *
 * Kept deliberately quiet on a live daily-driver phone:
 *   IMPORTANCE_MIN channel, no sound, no vibration, no lights, no badge, local-only.
 * `clear()` cancels it; callers should always clear when the check is done.
 */
object LoopbackReplyProbe {

    const val CHANNEL_ID = "body_loopback"
    const val NOTIF_ID = 4242
    const val RESULT_KEY = "body_loopback_text"

    @Volatile var lastText: String? = null
    @Volatile var lastAtMs: Long = 0L
    @Volatile var receivedCount: Int = 0
    @Volatile var lastPostedAtMs: Long = 0L

    fun post(ctx: Context): JSONObject {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Body self-check",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                description = "Silent internal loopback used to verify the reply path."
            }
            nm.createNotificationChannel(ch)

            val intent = Intent(ctx, LoopbackReplyReceiver::class.java)
                .setAction("com.beqa.body.action.LOOPBACK_REPLY")
            // FLAG_MUTABLE is mandatory from API 31: RemoteInput has to be able to write
            // its results into this PendingIntent's intent. FLAG_IMMUTABLE silently loses
            // the typed text, which would look exactly like "reply didn't work".
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)

            val remoteInput = RemoteInput.Builder(RESULT_KEY)
                .setLabel("Reply (loopback)")
                .setAllowFreeFormInput(true)
                .build()

            val action = Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_launcher),
                "Reply",
                pi
            )
                .addRemoteInput(remoteInput)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(false)
                .build()

            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Body self-check")
                .setContentText("Internal reply-path test. Safe to ignore or swipe away.")
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .addAction(action)
                .build()

            nm.notify(NOTIF_ID, n)
            lastPostedAtMs = System.currentTimeMillis()

            JSONObject()
                .put("ok", true)
                .put("posted", true)
                .put("notif_id", NOTIF_ID)
                .put("result_key", RESULT_KEY)
                .put("hint", "GET /notifications?pkg=com.beqa.body to read its key + action index")
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", "post_failed: ${t.javaClass.simpleName}")
                .put("detail", t.message)
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("ok", true)
        .put("posted_at_ms", lastPostedAtMs)
        .put("received_count", receivedCount)
        .put("last_text", lastText ?: JSONObject.NULL)
        .put("last_at_ms", lastAtMs)

    fun clear(ctx: Context): JSONObject {
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
            JSONObject().put("ok", true).put("cleared", true)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "clear_failed: ${t.javaClass.simpleName}")
        }
    }

    fun component(ctx: Context): ComponentName =
        ComponentName(ctx, LoopbackReplyReceiver::class.java)
}

/** Receives the loopback reply and records what actually arrived. */
class LoopbackReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val results = RemoteInput.getResultsFromIntent(intent)
        val text = results?.getCharSequence(LoopbackReplyProbe.RESULT_KEY)?.toString()
        LoopbackReplyProbe.lastText = text
        LoopbackReplyProbe.lastAtMs = System.currentTimeMillis()
        LoopbackReplyProbe.receivedCount += 1
    }
}
