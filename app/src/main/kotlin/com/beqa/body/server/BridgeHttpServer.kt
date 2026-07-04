package com.beqa.body.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.beqa.body.BuildConfig
import com.beqa.body.a11y.BodyAccessibilityService
import com.beqa.body.notify.BodyNotificationListener
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
            .put("milestone", "M2")
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
