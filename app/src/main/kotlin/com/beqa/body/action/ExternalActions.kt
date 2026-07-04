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
import org.json.JSONObject

object ExternalActions {

    private val REPLY_ALLOWLIST = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android"
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

    private fun notifReply(payload: JSONObject, confirm: String?, context: Context): JSONObject {
        val key = payload.optString("key")
        val text = payload.optString("text")

        val instance = BodyNotificationListener.instance
            ?: return err("listener_not_connected")

        val sbn = findSbn(instance, key) ?: return err("notification_not_found")

        if (sbn.packageName !in REPLY_ALLOWLIST) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_allowlisted")
                .put("pkg", sbn.packageName)
        }

        val action = sbn.notification.actions?.firstOrNull {
            val ri = it.remoteInputs
            ri != null && ri.isNotEmpty()
        } ?: return err("no_reply_action")

        val canonical = "notif.reply|$key|$text"
        val summary = JSONObject()
            .put("pkg", sbn.packageName)
            .put("text_preview", text.take(40))

        ConfirmationGate.guard("notif.reply", canonical, summary, confirm)?.let { return it }

        return try {
            val results = Bundle()
            for (ri in action.remoteInputs) results.putCharSequence(ri.resultKey, text)
            val intent = Intent()
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            JSONObject()
                .put("ok", true)
                .put("replied", true)
                .put("pkg", sbn.packageName)
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

        val instance = BodyNotificationListener.instance
            ?: return err("listener_not_connected")

        val sbn = findSbn(instance, key) ?: return err("notification_not_found")

        if (sbn.packageName !in REPLY_ALLOWLIST) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_allowlisted")
                .put("pkg", sbn.packageName)
        }

        val action: Notification.Action = sbn.notification.actions?.firstOrNull {
            it.title?.toString().equals(title, ignoreCase = true)
        } ?: return err("action_not_found")

        val canonical = "notif.action|$key|$title"
        val summary = JSONObject()
            .put("pkg", sbn.packageName)
            .put("title", title)

        ConfirmationGate.guard("notif.action", canonical, summary, confirm)?.let { return it }

        return try {
            action.actionIntent.send(context, 0, Intent())
            JSONObject()
                .put("ok", true)
                .put("triggered", true)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "action_failed")
                .put("detail", e.message)
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
