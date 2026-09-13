package com.beqa.body.action

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import com.beqa.body.notify.BodyNotificationListener
import org.json.JSONArray
import org.json.JSONObject

object ExternalActions {

    private val REPLY_ALLOWLIST = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        // Our own loopback self-check notification. Replying to it reaches a
        // BroadcastReceiver inside this same app and no human being — it exists so the
        // reply path can be PROVEN without messaging anyone.
        "com.beqa.body"
    )

    fun route(
        path: String,
        payload: JSONObject,
        confirm: String?,
        context: Context
    ): JSONObject {
        return when (path) {
            "/sms/send" -> smsSend(payload, confirm, context)
            "/notifications/reply" -> notifReply(payload, confirm, context)
            "/notifications/action" -> notifAction(payload, confirm, context)
            "/notifications/dismiss" -> notifDismiss(payload)
            "/notifications/snooze" -> notifSnooze(payload)
            else -> err("not_found")
        }
    }

    private fun smsSend(payload: JSONObject, confirm: String?, context: Context): JSONObject {
        val to = payload.optString("to")
        val body = payload.optString("body")
        if (to.isBlank() || body.isBlank()) return err("missing_params")

        val canonical = "sms.send|$to|$body"
        val summary = JSONObject()
            .put("to", maskNumber(to))
            .put("body_preview", body.take(40))
            .put("body_len", body.length)

        ConfirmationGate.guard("sms.send", canonical, summary, confirm)?.let { return it }

        return try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(to, null, body, null, null)
            JSONObject()
                .put("ok", true)
                .put("sent", true)
                .put("to", maskNumber(to))
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "sms_failed")
                .put("detail", e.message)
        }
    }

    /**
     * Reply into a notification's RemoteInput.
     *
     * M8d change: optional `action_index` (the `index` field GET /notifications now returns).
     * Without it this fell back to "first action carrying any RemoteInput" and then stuffed
     * the same text into EVERY result key on that action — silently wrong for any app that
     * ships two reply actions, or one action with two distinct inputs. The index is folded
     * into the confirmation canonical string, so a token minted for index 0 cannot be
     * replayed against index 1.
     */
    private fun notifReply(payload: JSONObject, confirm: String?, context: Context): JSONObject {
        val key = payload.optString("key")
        val text = payload.optString("text")
        val actionIndex = if (payload.has("action_index")) payload.optInt("action_index", -1) else -1
        val resultKey = payload.optString("result_key").ifBlank { null }

        val instance = BodyNotificationListener.instance
            ?: return err("listener_not_connected")

        val sbn = findSbn(instance, key) ?: return err("notification_not_found")

        if (sbn.packageName !in REPLY_ALLOWLIST) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_allowlisted")
                .put("pkg", sbn.packageName)
        }

        val actions = sbn.notification.actions
        if (actions == null || actions.isEmpty()) return err("no_reply_action")

        val resolvedIndex: Int = if (actionIndex >= 0) {
            if (actionIndex >= actions.size) return err("action_index_out_of_range")
            actionIndex
        } else {
            actions.indexOfFirst { a ->
                val ri = a?.remoteInputs
                ri != null && ri.isNotEmpty()
            }
        }
        if (resolvedIndex < 0) return err("no_reply_action")

        val action = actions[resolvedIndex] ?: return err("action_not_found")
        val remoteInputs = action.remoteInputs
        if (remoteInputs == null || remoteInputs.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "no_remote_input_on_action")
                .put("action_index", resolvedIndex)
                .put("action_title", action.title?.toString())
        }

        // If the caller named a result_key it must exist on this action.
        if (resultKey != null && remoteInputs.none { it.resultKey == resultKey }) {
            return JSONObject()
                .put("ok", false)
                .put("error", "result_key_not_found")
                .put("action_index", resolvedIndex)
        }

        val canonical = "notif.reply|$key|$resolvedIndex|${resultKey ?: "*"}|$text"
        val summary = JSONObject()
            .put("pkg", sbn.packageName)
            .put("action_index", resolvedIndex)
            .put("action_title", action.title?.toString() ?: "")
            .put("result_keys", JSONArray().also { a ->
                for (ri in remoteInputs) if (resultKey == null || ri.resultKey == resultKey) a.put(ri.resultKey)
            })
            .put("text_preview", text.take(40))

        ConfirmationGate.guard("notif.reply", canonical, summary, confirm)?.let { return it }

        return try {
            val results = Bundle()
            val used = JSONArray()
            for (ri in remoteInputs) {
                if (resultKey != null && ri.resultKey != resultKey) continue
                results.putCharSequence(ri.resultKey, text)
                used.put(ri.resultKey)
            }
            val intent = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(context, 0, intent)
            JSONObject()
                .put("ok", true)
                .put("replied", true)
                .put("pkg", sbn.packageName)
                .put("action_index", resolvedIndex)
                .put("result_keys", used)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "reply_failed")
                .put("detail", e.message)
        }
    }

    private fun notifAction(payload: JSONObject, confirm: String?, context: Context): JSONObject {
        val key = payload.optString("key")
        val title = payload.optString("title")
        val actionIndex = if (payload.has("action_index")) payload.optInt("action_index", -1) else -1

        val instance = BodyNotificationListener.instance
            ?: return err("listener_not_connected")

        val sbn = findSbn(instance, key) ?: return err("notification_not_found")

        if (sbn.packageName !in REPLY_ALLOWLIST) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_allowlisted")
                .put("pkg", sbn.packageName)
        }

        val actions = sbn.notification.actions ?: return err("action_not_found")
        val resolvedIndex = if (actionIndex >= 0) {
            if (actionIndex >= actions.size) return err("action_index_out_of_range")
            actionIndex
        } else {
            actions.indexOfFirst { it?.title?.toString().equals(title, ignoreCase = true) }
        }
        if (resolvedIndex < 0) return err("action_not_found")
        val action: Notification.Action = actions[resolvedIndex] ?: return err("action_not_found")

        val canonical = "notif.action|$key|$resolvedIndex|$title"
        val summary = JSONObject()
            .put("pkg", sbn.packageName)
            .put("action_index", resolvedIndex)
            .put("title", action.title?.toString() ?: title)

        ConfirmationGate.guard("notif.action", canonical, summary, confirm)?.let { return it }

        return try {
            action.actionIntent.send(context, 0, Intent())
            JSONObject()
                .put("ok", true)
                .put("triggered", true)
                .put("action_index", resolvedIndex)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "action_failed")
                .put("detail", e.message)
        }
    }

    /**
     * Shade housekeeping. NOT gated by ConfirmationGate and NOT allowlisted, deliberately:
     * dismissing or snoozing has no effect outside this device — the underlying message
     * still exists in the source app, and a snooze just reposts it later. That matches the
     * gate's philosophy (gate = irreversible or externally visible), but it IS a
     * security-relevant default, so it is stated here rather than left implicit.
     */
    private fun notifDismiss(payload: JSONObject): JSONObject {
        val key = payload.optString("key")
        if (key.isBlank()) return err("missing_params")
        val instance = BodyNotificationListener.instance ?: return err("listener_not_connected")
        val sbn = findSbn(instance, key) ?: return err("notification_not_found")
        if (!sbn.isClearable) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_clearable")
                .put("pkg", sbn.packageName)
        }
        return try {
            instance.cancelNotification(key)
            JSONObject()
                .put("ok", true)
                .put("dismissed", true)
                .put("pkg", sbn.packageName)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "dismiss_failed").put("detail", e.message)
        }
    }

    private fun notifSnooze(payload: JSONObject): JSONObject {
        val key = payload.optString("key")
        if (key.isBlank()) return err("missing_params")
        // Default 10 minutes; clamp to something the framework will honour.
        val duration = payload.optLong("duration_ms", 600_000L).coerceIn(1_000L, 86_400_000L)
        val instance = BodyNotificationListener.instance ?: return err("listener_not_connected")
        val sbn = findSbn(instance, key) ?: return err("notification_not_found")
        return try {
            instance.snoozeNotification(key, duration)
            JSONObject()
                .put("ok", true)
                .put("snoozed", true)
                .put("duration_ms", duration)
                .put("pkg", sbn.packageName)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "snooze_failed").put("detail", e.message)
        }
    }

    private fun findSbn(
        instance: BodyNotificationListener,
        key: String
    ): StatusBarNotification? {
        return try {
            instance.activeNotifications?.firstOrNull { it.key == key }
        } catch (e: Exception) {
            null
        }
    }

    private fun maskNumber(s: String): String {
        if (s.length < 2) return "[number]"
        return "[number …${s.takeLast(2)}]"
    }

    private fun err(error: String): JSONObject =
        JSONObject().put("ok", false).put("error", error)
}
