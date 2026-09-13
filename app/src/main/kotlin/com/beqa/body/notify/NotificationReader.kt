package com.beqa.body.notify

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the device's currently-active notifications into JSON.
 *
 * M8b: the full picture, not the summary. Every extras field the framework exposes to a
 * listener, the complete action schema (including each action's RemoteInput result keys,
 * so a caller can address a specific reply box instead of guessing), conversation history
 * where the app used MessagingStyle, and the Ranking data (importance, channel, whether
 * this is a conversation) that the listener grant already entitles us to.
 *
 * Depends on [BodyNotificationListener] being connected (its companion `instance` non-null).
 * Framework + org.json only.
 *
 * NOTE ON "dismissible": that is the existing `clearable` field (StatusBarNotification.isClearable).
 * There is deliberately no second field with the same meaning.
 */
object NotificationReader {

    fun list(limit: Int = 50, pkgFilter: String? = null): JSONObject {
        val listener = BodyNotificationListener.instance
            ?: return JSONObject().put("ok", false).put("error", "listener_not_connected")

        val active: Array<StatusBarNotification> = try {
            listener.activeNotifications ?: emptyArray()
        } catch (t: Throwable) {
            return JSONObject()
                .put("ok", false)
                .put("error", "read_failed: ${t.javaClass.simpleName}")
        }

        // One RankingMap fetch for the whole list; per-notification lookups are local.
        val rankingMap: NotificationListenerService.RankingMap? = try {
            listener.currentRanking
        } catch (t: Throwable) {
            null
        }

        val arr = JSONArray()
        val sorted = try {
            active.sortedByDescending { it.postTime }
        } catch (t: Throwable) {
            active.toList()
        }

        var totalMatched = 0
        for (sbn in sorted) {
            if (pkgFilter != null) {
                val p = try { sbn.packageName } catch (t: Throwable) { null }
                if (p == null || !p.contains(pkgFilter, ignoreCase = true)) continue
            }
            totalMatched++
            if (arr.length() >= limit) continue
            val obj = try {
                serialize(sbn, listener, rankingMap)
            } catch (t: Throwable) {
                null
            }
            if (obj != null) arr.put(obj)
        }

        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("total", totalMatched)
            .put("notifications", arr)
    }

    private fun serialize(
        sbn: StatusBarNotification,
        listener: BodyNotificationListener,
        rankingMap: NotificationListenerService.RankingMap?
    ): JSONObject {
        val obj = JSONObject()
        val context: Context = listener

        try { obj.put("key", sbn.key) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("id", sbn.id) } catch (t: Throwable) { /* ignore */ }
        try { sbn.tag?.let { obj.put("tag", it) } } catch (t: Throwable) { /* ignore */ }

        val pkg = try { sbn.packageName } catch (t: Throwable) { null }
        if (!pkg.isNullOrBlank()) {
            obj.put("pkg", pkg)
            obj.put("app", appLabel(context, pkg))
        }

        try { obj.put("post_time", sbn.postTime) } catch (t: Throwable) { /* ignore */ }
        // isClearable IS the "can I dismiss this" answer. No duplicate field.
        try { obj.put("clearable", sbn.isClearable) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("ongoing", sbn.isOngoing) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("group_key", sbn.groupKey) } catch (t: Throwable) { /* ignore */ }
        try { obj.put("is_group", sbn.isGroup) } catch (t: Throwable) { /* ignore */ }

        val notification: Notification? = try { sbn.notification } catch (t: Throwable) { null }
        if (notification != null) {
            try { notification.category?.let { obj.put("category", it) } } catch (t: Throwable) { /* ignore */ }
            try { obj.put("flags", notification.flags) } catch (t: Throwable) { /* ignore */ }
            try {
                obj.put(
                    "is_group_summary",
                    (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                )
            } catch (t: Throwable) { /* ignore */ }
            try { if (notification.number != 0) obj.put("number", notification.number) } catch (t: Throwable) { /* ignore */ }
            try { if (notification.`when` != 0L) obj.put("when", notification.`when`) } catch (t: Throwable) { /* ignore */ }
            try { notification.channelId?.let { obj.put("channel_id", it) } } catch (t: Throwable) { /* ignore */ }
            try { notification.group?.let { obj.put("group", it) } } catch (t: Throwable) { /* ignore */ }
            try { notification.sortKey?.let { obj.put("sort_key", it) } } catch (t: Throwable) { /* ignore */ }
            try { notification.shortcutId?.let { obj.put("shortcut_id", it) } } catch (t: Throwable) { /* ignore */ }
            try { obj.put("has_content_intent", notification.contentIntent != null) } catch (t: Throwable) { /* ignore */ }

            try {
                val extras = notification.extras
                if (extras != null) {
                    putIfNotBlank(obj, "title", extras.getCharSequence(Notification.EXTRA_TITLE))
                    putIfNotBlank(obj, "title_big", extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
                    putIfNotBlank(obj, "text", extras.getCharSequence(Notification.EXTRA_TEXT))
                    putIfNotBlank(obj, "big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                    putIfNotBlank(obj, "sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                    putIfNotBlank(obj, "info_text", extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
                    putIfNotBlank(obj, "summary_text", extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
                    putIfNotBlank(obj, "conversation_title", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
                    putIfNotBlank(obj, "self_display_name", extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME))
                    putIfNotBlank(obj, "template", extras.getCharSequence(Notification.EXTRA_TEMPLATE))
                    try {
                        extras.getString(Notification.EXTRA_TEMPLATE)?.let { obj.put("template", it) }
                    } catch (t: Throwable) { /* ignore */ }
                    try {
                        if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                            obj.put(
                                "is_group_conversation",
                                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                            )
                        }
                    } catch (t: Throwable) { /* ignore */ }
                    try {
                        if (extras.containsKey(Notification.EXTRA_SHOW_WHEN)) {
                            obj.put("show_when", extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
                        }
                    } catch (t: Throwable) { /* ignore */ }
                    try {
                        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                        if (lines != null && lines.isNotEmpty()) {
                            val la = JSONArray()
                            for (l in lines) l?.toString()?.let { if (it.isNotBlank()) la.put(it) }
                            if (la.length() > 0) obj.put("text_lines", la)
                        }
                    } catch (t: Throwable) { /* ignore */ }

                    people(extras)?.let { if (it.length() > 0) obj.put("people", it) }
                    messages(extras, Notification.EXTRA_MESSAGES)?.let {
                        if (it.length() > 0) obj.put("messages", it)
                    }
                    messages(extras, Notification.EXTRA_HISTORIC_MESSAGES)?.let {
                        if (it.length() > 0) obj.put("historic_messages", it)
                    }
                }
            } catch (t: Throwable) {
                // Some OEM builds throw when unparceling extras; skip them.
            }

            try {
                val actions = notification.actions
                if (actions != null && actions.isNotEmpty()) {
                    val actionsArr = JSONArray()
                    var replyIndex = -1
                    for ((i, action) in actions.withIndex()) {
                        if (action == null) continue
                        val actionObj = JSONObject()
                        // STABLE INDEX: this is what POST /notifications/reply?action_index=N
                        // addresses. Without it a caller can only say "the first reply-ish
                        // action", which is silently wrong when an app ships two of them.
                        actionObj.put("index", i)
                        val title = try { action.title?.toString() } catch (t: Throwable) { null }
                        if (!title.isNullOrBlank()) actionObj.put("title", title)
                        try { actionObj.put("semantic_action", action.semanticAction) } catch (t: Throwable) { /* ignore */ }
                        try { actionObj.put("allow_generated_replies", action.allowGeneratedReplies) } catch (t: Throwable) { /* ignore */ }
                        try { actionObj.put("contextual", action.isContextual) } catch (t: Throwable) { /* ignore */ }
                        try { actionObj.put("has_intent", action.actionIntent != null) } catch (t: Throwable) { /* ignore */ }

                        val inputsArr = JSONArray()
                        try {
                            val inputs = action.remoteInputs
                            if (inputs != null) {
                                for (ri in inputs) {
                                    if (ri == null) continue
                                    val rio = JSONObject()
                                    try { rio.put("result_key", ri.resultKey) } catch (t: Throwable) { /* ignore */ }
                                    try { ri.label?.toString()?.let { rio.put("label", it) } } catch (t: Throwable) { /* ignore */ }
                                    try { rio.put("allows_free_form_input", ri.allowFreeFormInput) } catch (t: Throwable) { /* ignore */ }
                                    try { rio.put("edit_choices_before_sending", ri.editChoicesBeforeSending) } catch (t: Throwable) { /* ignore */ }
                                    try { rio.put("data_only", ri.isDataOnly) } catch (t: Throwable) { /* ignore */ }
                                    try {
                                        val ch = ri.choices
                                        if (ch != null && ch.isNotEmpty()) {
                                            val ca = JSONArray()
                                            for (c in ch) c?.toString()?.let { ca.put(it) }
                                            rio.put("choices", ca)
                                        }
                                    } catch (t: Throwable) { /* ignore */ }
                                    inputsArr.put(rio)
                                }
                            }
                        } catch (t: Throwable) { /* ignore */ }

                        actionObj.put("has_remote_input", inputsArr.length() > 0)
                        if (inputsArr.length() > 0) {
                            actionObj.put("remote_inputs", inputsArr)
                            if (replyIndex < 0) replyIndex = i
                        }
                        actionsArr.put(actionObj)
                    }
                    if (actionsArr.length() > 0) obj.put("actions", actionsArr)
                    // Convenience: the index POST /notifications/reply would pick by default.
                    if (replyIndex >= 0) {
                        obj.put("reply_action_index", replyIndex)
                        obj.put("can_reply", true)
                    } else {
                        obj.put("can_reply", false)
                    }
                } else {
                    obj.put("can_reply", false)
                }
            } catch (t: Throwable) {
                // ignore malformed actions
            }
        }

        // ---- Ranking: importance, channel, conversation-ness. Same listener grant, free. ----
        if (rankingMap != null) {
            try {
                val r = NotificationListenerService.Ranking()
                if (rankingMap.getRanking(sbn.key, r)) {
                    val ro = JSONObject()
                    try { ro.put("rank", r.rank) } catch (t: Throwable) { /* ignore */ }
                    try {
                        ro.put("importance", r.importance)
                        ro.put("importance_name", importanceName(r.importance))
                    } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("ambient", r.isAmbient) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("matches_interruption_filter", r.matchesInterruptionFilter()) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("can_bubble", r.canBubble()) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("can_show_badge", r.canShowBadge()) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("suspended", r.isSuspended) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("last_audibly_alerted_ms", r.lastAudiblyAlertedMillis) } catch (t: Throwable) { /* ignore */ }
                    try { ro.put("user_sentiment", r.userSentiment) } catch (t: Throwable) { /* ignore */ }
                    try {
                        val isConv = r.isConversation
                        ro.put("is_conversation", isConv)
                        obj.put("is_conversation", isConv)
                    } catch (t: Throwable) { /* ignore */ }
                    try {
                        val ch = r.channel
                        if (ch != null) {
                            ro.put("channel_id", ch.id)
                            ch.name?.toString()?.let { ro.put("channel_name", it) }
                            ro.put("channel_importance", ch.importance)
                        }
                    } catch (t: Throwable) { /* ignore */ }
                    try {
                        val sr = r.smartReplies
                        if (sr != null && sr.isNotEmpty()) {
                            val sa = JSONArray()
                            for (s in sr) s?.toString()?.let { sa.put(it) }
                            ro.put("smart_replies", sa)
                        }
                    } catch (t: Throwable) { /* ignore */ }
                    obj.put("ranking", ro)
                    // Promote the two fields a caller almost always wants at the top level.
                    try { obj.put("importance", r.importance) } catch (t: Throwable) { /* ignore */ }
                    try { r.channel?.name?.toString()?.let { obj.put("channel_name", it) } } catch (t: Throwable) { /* ignore */ }
                }
            } catch (t: Throwable) {
                // ranking unavailable for this key
            }
        }

        return obj
    }

    /** EXTRA_PEOPLE_LIST is API 28+ and holds android.app.Person. */
    private fun people(extras: android.os.Bundle): JSONArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            @Suppress("DEPRECATION")
            val list: ArrayList<Person>? = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
            if (list == null) return null
            val arr = JSONArray()
            for (p in list) {
                if (p == null) continue
                val o = JSONObject()
                try { p.name?.toString()?.let { o.put("name", it) } } catch (t: Throwable) { /* ignore */ }
                try { p.uri?.let { o.put("uri", it) } } catch (t: Throwable) { /* ignore */ }
                try { p.key?.let { o.put("key", it) } } catch (t: Throwable) { /* ignore */ }
                try { o.put("is_bot", p.isBot) } catch (t: Throwable) { /* ignore */ }
                try { o.put("is_important", p.isImportant) } catch (t: Throwable) { /* ignore */ }
                if (o.length() > 0) arr.put(o)
            }
            arr
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Conversation history from a MessagingStyle notification.
     *
     * Notification.MessagingStyle.extractMessagingStyle() is NOT in the API-33 stub this app
     * compiles against, so this goes at the same data the public way: the raw EXTRA_MESSAGES
     * Parcelable[] plus the public static Message.getMessagesFromBundleArray().
     */
    private fun messages(extras: android.os.Bundle, key: String): JSONArray? {
        return try {
            @Suppress("DEPRECATION")
            val raw: Array<Parcelable>? = extras.getParcelableArray(key)
            if (raw == null || raw.isEmpty()) return null
            val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
                ?: return null
            val arr = JSONArray()
            for (m in msgs) {
                if (m == null) continue
                val o = JSONObject()
                try { m.text?.toString()?.let { o.put("text", it) } } catch (t: Throwable) { /* ignore */ }
                try { o.put("timestamp", m.timestamp) } catch (t: Throwable) { /* ignore */ }
                try { m.sender?.toString()?.let { o.put("sender", it) } } catch (t: Throwable) { /* ignore */ }
                try {
                    val p = m.senderPerson
                    if (p != null) {
                        p.name?.toString()?.let { o.put("sender", it) }
                        p.uri?.let { o.put("sender_uri", it) }
                        o.put("sender_is_bot", p.isBot)
                    }
                } catch (t: Throwable) { /* ignore */ }
                try { m.dataMimeType?.let { o.put("data_mime_type", it) } } catch (t: Throwable) { /* ignore */ }
                if (o.length() > 0) arr.put(o)
            }
            arr
        } catch (t: Throwable) {
            null
        }
    }

    private fun importanceName(v: Int): String = when (v) {
        0 -> "NONE"; 1 -> "MIN"; 2 -> "LOW"; 3 -> "DEFAULT"; 4 -> "HIGH"; 5 -> "MAX"
        else -> "UNSPECIFIED"
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
