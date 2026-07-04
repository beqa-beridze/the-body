package com.beqa.body.notify

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the device's currently-active notifications into JSON.
 *
 * Depends on [BodyNotificationListener] being connected (its companion
 * `instance` non-null). Framework + org.json only.
 */
object NotificationReader {

    fun list(limit: Int = 50): JSONObject {
        val listener = BodyNotificationListener.instance
            ?: return JSONObject().put("ok", false).put("error", "listener_not_connected")

        val active: Array<StatusBarNotification> = try {
            listener.activeNotifications ?: emptyArray()
        } catch (t: Throwable) {
            return JSONObject()
                .put("ok", false)
                .put("error", "read_failed: ${t.javaClass.simpleName}")
        }

        val arr = JSONArray()
        val sorted = try {
            active.sortedByDescending { it.postTime }
        } catch (t: Throwable) {
            active.toList()
        }

        for (sbn in sorted) {
            if (arr.length() >= limit) break
            val obj = try {
                serialize(sbn, listener)
            } catch (t: Throwable) {
                null
            }
            if (obj != null) arr.put(obj)
        }

        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("notifications", arr)
    }

    private fun serialize(sbn: StatusBarNotification, context: Context): JSONObject {
        val obj = JSONObject()

        try { obj.put("key", sbn.key) } catch (t: Throwable) { /* ignore */ }

        val pkg = try { sbn.packageName } catch (t: Throwable) { null }
        if (!pkg.isNullOrBlank()) {
            obj.put("pkg", pkg)
            obj.put("app", appLabel(context, pkg))
        }

        try { obj.put("post_time", sbn.postTime) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("clearable", sbn.isClearable) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("ongoing", sbn.isOngoing) } catch (t: Throwable) { /* ignore */ }

        val notification: Notification? = try { sbn.notification } catch (t: Throwable) { null }
        if (notification != null) {
            try {
                val extras = notification.extras
                if (extras != null) {
                    putIfNotBlank(obj, "title", extras.getCharSequence(Notification.EXTRA_TITLE))
                    putIfNotBlank(obj, "text", extras.getCharSequence(Notification.EXTRA_TEXT))
                    putIfNotBlank(obj, "big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                    putIfNotBlank(obj, "sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                }
            } catch (t: Throwable) {
                // Some OEM builds throw when unparceling extras; skip them.
            }

            try {
                val actions = notification.actions
                if (actions != null && actions.isNotEmpty()) {
                    val actionsArr = JSONArray()
                    for (action in actions) {
                        if (action == null) continue
                        val actionObj = JSONObject()
                        val title = try { action.title?.toString() } catch (t: Throwable) { null }
                        if (!title.isNullOrBlank()) actionObj.put("title", title)
                        val hasRemoteInput = try {
                            val inputs = action.remoteInputs
                            inputs != null && inputs.isNotEmpty()
                        } catch (t: Throwable) {
                            false
                        }
                        actionObj.put("has_remote_input", hasRemoteInput)
                        actionsArr.put(actionObj)
                    }
                    if (actionsArr.length() > 0) obj.put("actions", actionsArr)
                }
            } catch (t: Throwable) {
                // ignore malformed actions
            }
        }

        return obj
    }

    private fun appLabel(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info)?.toString()
            if (label.isNullOrBlank()) pkg else label
        } catch (t: Throwable) {
            pkg
        }
    }

    private fun putIfNotBlank(obj: JSONObject, key: String, value: CharSequence?) {
        val s = value?.toString()
        if (!s.isNullOrBlank()) obj.put(key, s)
    }
}
