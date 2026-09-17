package com.beqa.body.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import com.beqa.body.R
import org.json.JSONObject
import java.security.SecureRandom

/**
 * THE PHONE RULE'S ALARM. Nothing else.
 *
 * WHY THIS EXISTS
 * ---------------
 * The phone rule: if something genuinely cannot be done on the background display, the
 * agent does NOT grab my real screen. It ASKS ME and nags until I answer. That escape
 * hatch was posted through `termux-notification`, whose channel on this device is
 *   NotificationChannel{mId='termux-notification', mImportance=1, mOriginalImp=3, mUserLockedFields=0}
 * IMPORTANCE_MIN: no sound, no heads-up, no peek. mUserLockedFields=0 means I never muted it,
 * the app lowered its own channel, and an app can never RAISE a channel's importance after
 * creation, nor can `cmd notification` set importance from adb. So the one hard rule's alarm was
 * silent and there was no way to fix it in Termux:API. This class moves the alarm into OUR app,
 * onto a channel WE create, at IMPORTANCE_HIGH, with sound and vibration and two real buttons.
 *
 * "NO AUTOMATIC NOTIFICATIONS", ENFORCED BY THE SHAPE OF THE CLASS
 * ----------------------------------------------------------
 * I do not want automatic notifications. This channel is allowed to make noise
 * precisely because it can only ever be used for one thing, and that is enforced by shape:
 *
 *   1. There is NO informational mode. Every post creates a YES/NO gate with a pending answer.
 *      You cannot send a message through here, only a question that somebody must answer.
 *   2. ONE pending ask at a time. A second post while one is outstanding is refused (the caller
 *      is told which ask is already pending); it cannot stack alarms.
 *   3. A hard cap of MAX_ASKS_PER_HOUR NEW asks per rolling hour. Re-nags of the SAME ask are
 *      free (nagging is the rule's requirement) but new alarms are not.
 *   4. Every notification carries setTimeoutAfter(), so a crashed caller cannot leave a stuck
 *      alarm on my phone, and the system removes it on its own.
 *   5. Answering cancels the notification immediately, from inside the receiver.
 *
 * Nothing else in this app may use CHANNEL_ID. The bridge exposes exactly one route (/ask).
 *
 * DURABILITY
 * ----------
 * The answer is written to SharedPreferences by the BroadcastReceiver, not just held in memory,
 * so an LMK kill between my tap and the caller's next poll does not lose the answer. If it is
 * lost anyway the caller sees "no answer", which means DO NOT PROCEED, the safe default.
 */
object AskGate {

    /** Never reused, never renamed. A channel's importance is fixed at creation; a new id is a new channel. */
    const val CHANNEL_ID = "body_phone_rule_ask"
    const val NOTIF_ID = 7317

    const val ACTION_YES = "com.beqa.body.action.ASK_YES"
    const val ACTION_NO = "com.beqa.body.action.ASK_NO"
    const val EXTRA_ID = "ask_id"

    private const val PREFS = "body_ask_gate"
    private const val K_ID = "id"
    private const val K_QUESTION = "question"
    private const val K_CREATED = "created_at_ms"
    private const val K_EXPIRES = "expires_at_ms"
    private const val K_NAGS = "nags"
    private const val K_ANSWER = "answer"
    private const val K_ANSWERED = "answered_at_ms"
    private const val K_CLEARED = "cleared"
    private const val K_HOUR_START = "hour_start_ms"
    private const val K_HOUR_COUNT = "hour_count"

    private const val MAX_ASKS_PER_HOUR = 6
    private const val MAX_QUESTION_CHARS = 240
    private const val DEFAULT_TIMEOUT_MS = 3_600_000L
    private const val MAX_TIMEOUT_MS = 14_400_000L   // 4h ceiling: an alarm cannot outlive a shift
    private const val MIN_TIMEOUT_MS = 60_000L

    private val random = SecureRandom()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nm(ctx: Context): NotificationManager =
        ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ---------------------------------------------------------------- channel

    /**
     * Create the channel at IMPORTANCE_HIGH with sound + vibration.
     *
     * createNotificationChannel() on an EXISTING channel can only lower importance, never raise it,
     * so this is idempotent and cannot repair itself if the id ever gets demoted. That is exactly
     * how termux-notification's channel died. If this one is ever found below 4 in
     * `dumpsys notification`, the fix is a NEW channel id, not a code change here.
     */
    private fun ensureChannel(ctx: Context): NotificationChannel {
        val manager = nm(ctx)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return existing

        val ch = NotificationChannel(
            CHANNEL_ID,
            "Toph needs an answer (phone rule)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description =
                "The ONLY channel the companion may make noise on. Used when an agent cannot do " +
                "something on the hidden background display and must ask before touching your screen. " +
                "Always a YES/NO question. Never status, never updates."
            setShowBadge(true)
            enableLights(true)
            lightColor = 0xFFFF5522.toInt()
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // Ignored unless the app holds notification-policy access; harmless if it is.
            setBypassDnd(true)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(ch)
        return manager.getNotificationChannel(CHANNEL_ID) ?: ch
    }

    /** What the OS actually stored: read back, never assumed. */
    fun channelInfo(ctx: Context): JSONObject {
        val ch = nm(ctx).getNotificationChannel(CHANNEL_ID)
            ?: return JSONObject().put("exists", false).put("id", CHANNEL_ID)
        return JSONObject()
            .put("exists", true)
            .put("id", ch.id)
            .put("importance", ch.importance)
            .put("sound", ch.sound?.toString() ?: JSONObject.NULL)
            .put("vibration", ch.shouldVibrate())
            .put("bypass_dnd", ch.canBypassDnd())
            .put("blocked_app_level", !nm(ctx).areNotificationsEnabled())
    }

    // ---------------------------------------------------------------- post / nag

    fun post(ctx: Context, questionRaw: String?, timeoutMsRaw: Long): JSONObject {
        val question = (questionRaw ?: "").trim()
        if (question.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "question_required")
        }
        if (question.length > MAX_QUESTION_CHARS) {
            return JSONObject().put("ok", false)
                .put("error", "question_too_long")
                .put("max_chars", MAX_QUESTION_CHARS)
        }

        val p = prefs(ctx)
        val now = System.currentTimeMillis()

        // (2) one alarm at a time.
        val pendingId = p.getString(K_ID, null)
        if (pendingId != null && stateOf(p, now) == "pending") {
            return JSONObject().put("ok", false)
                .put("error", "ask_already_pending")
                .put("pending_id", pendingId)
                .put("pending_question", p.getString(K_QUESTION, ""))
        }

        // (3) hard cap on NEW alarms per rolling hour.
        var hourStart = p.getLong(K_HOUR_START, 0L)
        var hourCount = p.getInt(K_HOUR_COUNT, 0)
        if (now - hourStart >= 3_600_000L) { hourStart = now; hourCount = 0 }
        if (hourCount >= MAX_ASKS_PER_HOUR) {
            return JSONObject().put("ok", false)
                .put("error", "rate_limited")
                .put("max_per_hour", MAX_ASKS_PER_HOUR)
                .put("retry_after_s", ((hourStart + 3_600_000L - now) / 1000L).coerceAtLeast(1))
        }

        val timeoutMs = timeoutMsRaw.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val id = "ask-" + now + "-" + randomHex()

        p.edit()
            .putString(K_ID, id)
            .putString(K_QUESTION, question)
            .putLong(K_CREATED, now)
            .putLong(K_EXPIRES, now + timeoutMs)
            .putInt(K_NAGS, 1)
            .remove(K_ANSWER)
            .putLong(K_ANSWERED, 0L)
            .putBoolean(K_CLEARED, false)
            .putLong(K_HOUR_START, hourStart)
            .putInt(K_HOUR_COUNT, hourCount + 1)
            .apply()

        return try {
            ensureChannel(ctx)
            nm(ctx).notify(NOTIF_ID, build(ctx, id, question, 1, now + timeoutMs))
            JSONObject()
                .put("ok", true)
                .put("posted", true)
                .put("id", id)
                .put("expires_at_ms", now + timeoutMs)
                .put("channel", channelInfo(ctx))
        } catch (t: Throwable) {
            p.edit().remove(K_ID).apply()
            JSONObject().put("ok", false)
                .put("error", "post_failed: ${t.javaClass.simpleName}")
                .put("detail", t.message ?: JSONObject.NULL)
        }
    }

    /**
     * Re-alert the SAME ask. Free of the hourly cap, because nagging until I answer is the rule's
     * literal requirement ("spam me until i answer"). Re-notifying an existing id re-alerts
     * because the notification does NOT set onlyAlertOnce.
     */
    fun nag(ctx: Context, id: String?): JSONObject {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val cur = p.getString(K_ID, null)
            ?: return JSONObject().put("ok", false).put("error", "no_ask")
        if (id != null && id != cur) {
            return JSONObject().put("ok", false).put("error", "id_mismatch").put("current_id", cur)
        }
        val state = stateOf(p, now)
        if (state != "pending") {
            return JSONObject().put("ok", true).put("nagged", false).put("state", state)
                .put("answer", p.getString(K_ANSWER, null) ?: JSONObject.NULL)
        }
        val nags = p.getInt(K_NAGS, 1) + 1
        p.edit().putInt(K_NAGS, nags).apply()
        return try {
            ensureChannel(ctx)
            nm(ctx).notify(
                NOTIF_ID,
                build(ctx, cur, p.getString(K_QUESTION, "") ?: "", nags, p.getLong(K_EXPIRES, now))
            )
            JSONObject().put("ok", true).put("nagged", true).put("id", cur).put("nags", nags)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "nag_failed: ${t.javaClass.simpleName}")
        }
    }

    private fun build(
        ctx: Context,
        id: String,
        question: String,
        nags: Int,
        expiresAtMs: Long
    ): Notification {
        val title = if (nags <= 1) "Toph needs a YES or NO" else "Toph needs a YES or NO (asked ${nags}×)"
        val remainMs = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1000L)

        val yes = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_launcher), "YES", intentFor(ctx, id, ACTION_YES)
        ).build()
        val no = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_launcher), "NO", intentFor(ctx, id, ACTION_NO)
        ).build()

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(question)
            // BigTextStyle so a long question is readable when expanded. termux-notification
            // could not do this (memory: notifications-cant-expand).
            .setStyle(Notification.BigTextStyle().bigText(question)
                .setSummaryText("phone rule · answer to unblock"))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)                 // he must answer it, not swipe it
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)          // (deliberate) every nag re-alerts
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            // (4) a crashed caller cannot leave a stuck alarm on my phone.
            .setTimeoutAfter(remainMs)
            .addAction(yes)
            .addAction(no)
            .build()
    }

    /**
     * The PendingIntent a button carries. FLAG_IMMUTABLE: these need no RemoteInput, so nothing
     * outside may rewrite them. Distinct action + distinct data (askgate://<id>/<answer>) keeps
     * YES and NO from collapsing into one another, because PendingIntent equality ignores extras.
     */
    private fun intentFor(ctx: Context, id: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            requestCode(id, action),
            Intent(ctx, AskGateReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("askgate://$id/${if (action == ACTION_YES) "yes" else "no"}"))
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Fire the EXACT PendingIntent the button holds, without touching display 0.
     *
     * This is not a simulation of the answer. It is the same PendingIntent object, resolved with
     * FLAG_NO_CREATE so it only succeeds if the notification really registered one, then .send().
     * That is precisely what SystemUI does when I tap the button. It proves the whole callback
     * path (PendingIntent -> receiver -> persisted answer -> notification cancel -> bridge status)
     * with the single exception of the physical touch event itself, which we may never fake.
     */
    fun fireActionPendingIntent(ctx: Context, id: String, answer: String): JSONObject {
        val action = when (answer.uppercase()) {
            "YES" -> ACTION_YES
            "NO" -> ACTION_NO
            else -> return JSONObject().put("ok", false).put("error", "answer_must_be_YES_or_NO")
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            requestCode(id, action),
            Intent(ctx, AskGateReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("askgate://$id/${if (action == ACTION_YES) "yes" else "no"}"))
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return JSONObject().put("ok", false)
            .put("error", "no_such_pending_intent")
            .put("hint", "the notification never registered this button for id=$id")
        return try {
            pi.send()
            JSONObject().put("ok", true).put("sent", true).put("id", id).put("answer", answer.uppercase())
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "send_failed: ${t.javaClass.simpleName}")
        }
    }

    private fun requestCode(id: String, action: String): Int =
        (id.hashCode() * 31 + action.hashCode()) and 0x7fffffff

    private fun randomHex(): String {
        val b = ByteArray(4)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- answer / status

    /** Called from the receiver. Idempotent: the first answer wins. */
    fun record(ctx: Context, id: String, answer: String) {
        val p = prefs(ctx)
        if (p.getString(K_ID, null) != id) return
        if (!p.getString(K_ANSWER, null).isNullOrEmpty()) return
        p.edit()
            .putString(K_ANSWER, answer)
            .putLong(K_ANSWERED, System.currentTimeMillis())
            .apply()
        // (5) stop nagging the instant I answer, do not wait for the caller to poll.
        try { nm(ctx).cancel(NOTIF_ID) } catch (_: Throwable) {}
    }

    private fun stateOf(p: SharedPreferences, now: Long): String {
        if (p.getString(K_ID, null) == null) return "none"
        val ans = p.getString(K_ANSWER, null)
        if (!ans.isNullOrEmpty()) return ans
        if (p.getBoolean(K_CLEARED, false)) return "cleared"
        if (now >= p.getLong(K_EXPIRES, 0L)) return "expired"
        return "pending"
    }

    fun status(ctx: Context, id: String?): JSONObject {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val cur = p.getString(K_ID, null)
        val out = JSONObject()
            .put("ok", true)
            .put("id", cur ?: JSONObject.NULL)
            .put("channel", channelInfo(ctx))
            .put("asks_this_hour", p.getInt(K_HOUR_COUNT, 0))
            .put("max_asks_per_hour", MAX_ASKS_PER_HOUR)
        if (cur == null) return out.put("state", "none")
        if (id != null && id != cur) {
            // The caller is asking about an ask we no longer hold. Never guess YES.
            return out.put("state", "unknown").put("queried_id", id)
        }
        val state = stateOf(p, now)
        // An expired ask leaves no alarm behind.
        if (state == "expired") try { nm(ctx).cancel(NOTIF_ID) } catch (_: Throwable) {}
        return out
            .put("state", state)
            .put("question", p.getString(K_QUESTION, "") ?: "")
            .put("nags", p.getInt(K_NAGS, 0))
            .put("created_at_ms", p.getLong(K_CREATED, 0L))
            .put("expires_at_ms", p.getLong(K_EXPIRES, 0L))
            .put("answered_at_ms", p.getLong(K_ANSWERED, 0L))
            .put("answer", p.getString(K_ANSWER, null) ?: JSONObject.NULL)
    }

    /** Take the alarm off his phone and close the ask. Always safe to call. */
    fun clear(ctx: Context, id: String?): JSONObject {
        val p = prefs(ctx)
        val cur = p.getString(K_ID, null)
        if (id != null && cur != null && id != cur) {
            return JSONObject().put("ok", true).put("cleared", false)
                .put("error", "id_mismatch").put("current_id", cur)
        }
        try { nm(ctx).cancel(NOTIF_ID) } catch (_: Throwable) {}
        p.edit().putBoolean(K_CLEARED, true).apply()
        return JSONObject().put("ok", true).put("cleared", true).put("id", cur ?: JSONObject.NULL)
    }
}

/**
 * Receives the YES/NO button press. Manifest-registered and NOT exported, so nothing outside this
 * app can forge an answer, including `am broadcast` from the adb shell. The only two ways in are
 * a real tap on the notification and AskGate.fireActionPendingIntent(), which is reachable solely
 * over the token-gated loopback bridge.
 */
class AskGateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val id = intent.getStringExtra(AskGate.EXTRA_ID) ?: return
        val answer = when (intent.action) {
            AskGate.ACTION_YES -> "YES"
            AskGate.ACTION_NO -> "NO"
            else -> return
        }
        AskGate.record(context, id, answer)
    }
}
