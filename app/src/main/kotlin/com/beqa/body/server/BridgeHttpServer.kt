package com.beqa.body.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.beqa.body.BuildConfig
import com.beqa.body.a11y.BodyAccessibilityService
import com.beqa.body.action.BodyActionExecutor
import com.beqa.body.action.BodyAppLauncher
import com.beqa.body.action.ExternalActions
import com.beqa.body.notify.BodyNotificationListener
import com.beqa.body.notify.NotificationReader
import com.beqa.body.screen.BodyScreenReader
import com.beqa.body.security.BridgeTokenStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class BridgeHttpServer(
    private val tokens: BridgeTokenStore,
    private val rateLimiter: AuthRateLimiter,
    context: Context
) : NanoHTTPD(BIND_ADDR, PORT) {

    companion object {
        const val BIND_ADDR = "127.0.0.1"
        const val PORT = 8765
    }

    private val appContext: Context = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        return try {
            val remote = session.remoteIpAddress
            if (remote != "127.0.0.1" && remote != "::1") {
                return reply(403, error("forbidden"))
            }

            var path = session.uri ?: "/"
            if (path.length > 1 && path.endsWith("/")) {
                path = path.trimEnd('/')
                if (path.isEmpty()) path = "/"
            }

            if (path == "/" || path == "/health") {
                return reply(200, health())
            }

            val ip = session.remoteIpAddress ?: "?"
            if (rateLimiter.isBlocked(ip)) {
                return reply(429, error("rate_limited"))
            }

            val auth = session.headers["authorization"]
            if (auth != null && auth.startsWith("Bearer ") &&
                tokens.validate(auth.substring("Bearer ".length).trim())
            ) {
                rateLimiter.recordSuccess(ip)
            } else {
                rateLimiter.recordFailure(ip)
                return reply(401, error("unauthorized"))
            }

            when (path) {
                "/state" -> reply(200, state())
                "/read_screen" -> reply(200, BodyScreenReader.readScreen(
                    mode = qStr(session, "mode", "interactive"),
                    includeBounds = qBool(session, "bounds", false),
                    includeSystemUi = qBool(session, "system_ui", false),
                    maxNodes = qInt(session, "max", 500)
                ))
                "/find_nodes" -> reply(200, BodyScreenReader.findNodes(
                    text = qStrOrNull(session, "text"),
                    contentDesc = qStrOrNull(session, "desc"),
                    resourceId = qStrOrNull(session, "rid"),
                    className = qStrOrNull(session, "class"),
                    clickableOnly = qBool(session, "clickable", false),
                    exact = qBool(session, "exact", false),
                    limit = qInt(session, "limit", 20)
                ))
                "/describe_node" -> reply(200, BodyScreenReader.describeNode(qStr(session, "id", "")))
                "/screen_diff" -> reply(200, BodyScreenReader.screenDiff(qStr(session, "hash", "")))
                "/tap" -> reply(200, BodyActionExecutor.tap(
                    id = qStrOrNull(session, "id"),
                    x = qIntOrNull(session, "x"),
                    y = qIntOrNull(session, "y"),
                    fallbackText = qStrOrNull(session, "text")
                ))
                "/long_press" -> reply(200, BodyActionExecutor.longPress(
                    id = qStrOrNull(session, "id"),
                    x = qIntOrNull(session, "x"),
                    y = qIntOrNull(session, "y"),
                    durationMs = qInt(session, "duration", 600)
                ))
                "/type_text" -> reply(200, BodyActionExecutor.typeText(
                    text = qStr(session, "text", ""),
                    id = qStrOrNull(session, "id"),
                    clearFirst = qBool(session, "clear", true),
                    submit = qBool(session, "submit", false)
                ))
                "/scroll" -> reply(200, BodyActionExecutor.scroll(
                    direction = qStr(session, "direction", "down"),
                    id = qStrOrNull(session, "id"),
                    distance = qStr(session, "distance", "medium")
                ))
                "/swipe" -> reply(200, BodyActionExecutor.swipe(
                    direction = qStr(session, "direction", "up"),
                    distance = qStr(session, "distance", "medium")
                ))
                "/press_key" -> reply(200, BodyActionExecutor.pressKey(qStr(session, "key", "")))
                "/notifications" -> reply(200, NotificationReader.list(qInt(session, "limit", 50)))
                "/sms/send", "/notifications/reply", "/notifications/action" -> {
                    val payload = payloadOf(session)
                    val confirm = payload.optString("confirm").ifBlank { null }
                    reply(200, ExternalActions.route(path, payload, confirm, appContext))
                }
                "/launch" -> {
                    val payload = payloadOf(session)
                    reply(200, BodyAppLauncher.launch(
                        pkg = payload.optString("pkg").ifBlank { null },
                        url = payload.optString("url").ifBlank { null },
                        context = appContext
                    ))
                }
                "/list_apps" -> reply(200, BodyAppLauncher.listApps(qStrOrNull(session, "filter"), appContext))
                "/current_app" -> reply(200, BodyAppLauncher.currentApp())
                "/wait_for" -> reply(200, BodyActionExecutor.waitFor(
                    text = qStrOrNull(session, "text"),
                    resourceId = qStrOrNull(session, "rid"),
                    app = qStrOrNull(session, "app"),
                    gone = qBool(session, "gone", false),
                    timeoutMs = qInt(session, "timeout", 5000)
                ))
                else -> reply(404, error("not_found"))
            }
        } catch (e: Exception) {
            reply(500, error("server_error"))
        }
    }

    private fun health(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("app", "body")
            .put("version", BuildConfig.VERSION_NAME)
            .put("milestone", "M7")
            .put(
                "capabilities",
                JSONObject()
                    .put("accessibility", BodyAccessibilityService.isConnected())
                    .put("notifications", BodyNotificationListener.isConnected())
            )
    }

    private fun state(): JSONObject {
        val batteryIntent: Intent? = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        var batteryPercent = -1
        var charging = false
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = level * 100 / scale
            }
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

        return JSONObject()
            .put("ok", true)
            .put("battery", batteryPercent)
            .put("charging", charging)
            .put("screenOn", powerManager.isInteractive)
            .put("uptimeMs", SystemClock.elapsedRealtime())
            .put("accessibility", BodyAccessibilityService.isConnected())
            .put("notifications", BodyNotificationListener.isConnected())
    }

    private fun qStrOrNull(s: IHTTPSession, k: String): String? =
        s.parameters[k]?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun qStr(s: IHTTPSession, k: String, d: String): String = qStrOrNull(s, k) ?: d

    private fun qInt(s: IHTTPSession, k: String, d: Int): Int = qStrOrNull(s, k)?.toIntOrNull() ?: d

    private fun qIntOrNull(s: IHTTPSession, k: String): Int? = qStrOrNull(s, k)?.toIntOrNull()

    /** Merge query params + JSON POST body into one JSONObject (for external-effect routes). */
    private fun payloadOf(s: IHTTPSession): JSONObject {
        val o = JSONObject()
        try {
            if (s.method == Method.POST || s.method == Method.PUT) {
                val map = HashMap<String, String>()
                s.parseBody(map)
                val raw = map["postData"] ?: s.parameters.keys.firstOrNull { it.trimStart().startsWith("{") }
                if (!raw.isNullOrBlank()) {
                    val body = JSONObject(raw)
                    body.keys().forEach { k -> o.put(k, body.get(k)) }
                }
            }
        } catch (e: Exception) { /* fall back to query params */ }
        s.parameters.forEach { (k, v) -> if (!o.has(k) && v.isNotEmpty()) o.put(k, v[0]) }
        return o
    }

    private fun qBool(s: IHTTPSession, k: String, d: Boolean): Boolean =
        qStrOrNull(s, k)?.let { it == "1" || it.equals("true", true) } ?: d

    private fun error(code: String): JSONObject {
        return JSONObject().put("ok", false).put("error", code)
    }

    private fun reply(httpCode: Int, json: JSONObject): Response {
        return newFixedLengthResponse(statusFor(httpCode), "application/json", json.toString())
    }

    private fun statusFor(code: Int): Response.IStatus {
        return when (code) {
            200 -> Response.Status.OK
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            500 -> Response.Status.INTERNAL_ERROR
            429 -> object : Response.IStatus {
                override fun getRequestStatus(): Int = 429
                override fun getDescription(): String = "429 Too Many Requests"
            }
            else -> object : Response.IStatus {
                override fun getRequestStatus(): Int = code
                override fun getDescription(): String = "$code Unknown"
            }
        }
    }
}
