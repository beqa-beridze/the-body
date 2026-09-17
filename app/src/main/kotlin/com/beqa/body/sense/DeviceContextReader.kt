package com.beqa.body.sense

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.beqa.body.BuildConfig
import com.beqa.body.a11y.BodyAccessibilityService
import com.beqa.body.notify.BodyNotificationListener
import org.json.JSONObject

/**
 * M8a, "where is the phone right now": foreground app, screen/lock state, power, thermal.
 *
 * Deliberately uses NO new permission. `foreground_app` comes from the accessibility
 * service (already granted), NOT from UsageStatsManager, because PACKAGE_USAGE_STATS would need
 * an explicit Settings > Usage Access tap, and it is not declared in the manifest.
 *
 * Every field is individually try/caught: one OEM quirk must never blank the whole reply.
 */
object DeviceContextReader {

    fun read(ctx: Context): JSONObject {
        val out = JSONObject()
        out.put("ok", true)
        out.put("version", BuildConfig.VERSION_NAME)
        out.put("sdk_int", Build.VERSION.SDK_INT)
        out.put("now_ms", System.currentTimeMillis())
        out.put("uptime_ms", SystemClock.elapsedRealtime())

        // ---- foreground app -------------------------------------------------------------
        val svc = BodyAccessibilityService.instance
        out.put("accessibility", svc != null)
        out.put("notifications", BodyNotificationListener.isConnected())

        // Cached: last TYPE_WINDOW_STATE_CHANGED package the a11y service saw.
        val cached = try { svc?.foregroundPackage } catch (t: Throwable) { null }
        out.put("foreground_app", cached ?: JSONObject.NULL)

        // Live: read the window list right now. This is ground truth at request time, but it
        // can legitimately be an IME/overlay, so BOTH are reported rather than one guess.
        val live = try { svc?.activeAppPackage() } catch (t: Throwable) { null }
        out.put("active_window_app", live ?: JSONObject.NULL)
        out.put("foreground_agrees", cached != null && cached == live)

        try {
            val root = svc?.activeRoot()
            out.put("active_window_root", root?.packageName?.toString() ?: JSONObject.NULL)
        } catch (t: Throwable) {
            out.put("active_window_root", JSONObject.NULL)
        }

        // ---- screen / keyguard ----------------------------------------------------------
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            out.put("screen_on", pm.isInteractive)
            out.put("power_save_mode", pm.isPowerSaveMode)
            out.put("device_idle_mode", pm.isDeviceIdleMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val t = pm.currentThermalStatus
                out.put("thermal_status", t)
                out.put("thermal_status_name", thermalName(t))
            }
        } catch (t: Throwable) {
            out.put("power_error", t.javaClass.simpleName)
        }

        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            out.put("display_state", d?.state ?: -1)
            out.put("display_state_name", displayStateName(d?.state ?: -1))
        } catch (t: Throwable) {
            out.put("display_state", -1)
        }

        try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            out.put("keyguard_locked", km.isKeyguardLocked)
            out.put("keyguard_secure", km.isKeyguardSecure)
            out.put("device_locked", km.isDeviceLocked)
            out.put("device_secure", km.isDeviceSecure)
        } catch (t: Throwable) {
            out.put("keyguard_error", t.javaClass.simpleName)
        }

        // ---- battery --------------------------------------------------------------------
        out.put("battery", battery(ctx))

        return out
    }

    /** Battery, from the sticky ACTION_BATTERY_CHANGED broadcast + BatteryManager properties. */
    fun battery(ctx: Context): JSONObject {
        val b = JSONObject()
        try {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                b.put("percent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                b.put("status", status)
                b.put("status_name", batteryStatusName(status))
                b.put(
                    "charging",
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                )
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                b.put("plugged", plugged)
                b.put("plugged_name", pluggedName(plugged))
                b.put("health", i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
                b.put("health_name", healthName(i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
                // Framework reports tenths of a degree C.
                val t10 = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t10 != Int.MIN_VALUE) b.put("temperature_c", t10 / 10.0)
                val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (mv >= 0) b.put("voltage_mv", mv)
                i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { b.put("technology", it) }
                b.put("present", i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true))
            }
        } catch (t: Throwable) {
            b.put("error", t.javaClass.simpleName)
        }
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            // Instantaneous current in microamps; negative = discharging on most devices.
            b.put("current_now_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            b.put("capacity_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            b.put("charge_counter_uah", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
        } catch (t: Throwable) { /* optional */ }
        return b
    }

    private fun thermalName(v: Int): String = when (v) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNKNOWN"
    }

    private fun displayStateName(v: Int): String = when (v) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN"
    }

    private fun batteryStatusName(v: Int): String = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        else -> "UNKNOWN"
    }

    private fun pluggedName(v: Int): String = when (v) {
        0 -> "NONE"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        else -> "OTHER"
    }

    private fun healthName(v: Int): String = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
        else -> "UNKNOWN"
    }
}
