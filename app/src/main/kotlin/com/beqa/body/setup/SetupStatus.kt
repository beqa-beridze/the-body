package com.beqa.body.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Reports the status of one-time device setup items and provides an Intent
 * to resolve each one. No AndroidX, framework APIs only (minSdk 26, target 33).
 */
object SetupStatus {

    data class Item(
        val key: String,
        val title: String,
        val detail: String,
        val done: Boolean,          // true if already satisfied (null/unknown -> false)
        val actionLabel: String,    // e.g. "Open settings"
        val fixIntent: Intent?,     // startActivity target to resolve it, or null if purely informational
        val ackable: Boolean = false // true if not OS-queryable and user may manually mark it done
    )

    private const val PREFS = "body_setup"

    private fun isAcked(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ack_$key", false)

    fun acknowledge(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("ack_$key", true).commit()
    }

    fun unacknowledge(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("ack_$key", false).commit()
    }

    private const val A11Y_PKG = "com.beqa.body"
    private const val A11Y_CLS = "com.beqa.body.a11y.BodyAccessibilityService"
    private const val NOTIF_PKG = "com.beqa.body"
    private const val NOTIF_CLS = "com.beqa.body.notify.BodyNotificationListener"

    fun items(ctx: Context): List<Item> {
        val pkgUri = Uri.parse("package:" + ctx.packageName)

        return listOf(
            Item(
                key = "restricted",
                title = "Allow restricted settings",
                detail = "Samsung/Android blocks sideloaded apps from some toggles — " +
                        "in the app's App info screen, tap the ⋮ menu → Allow restricted settings.",
                done = isAcked(ctx, "restricted"), // not programmatically queryable — user marks it done
                actionLabel = "Open app info",
                fixIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri),
                ackable = true
            ),
            Item(
                key = "autoblocker",
                title = "Turn off Auto Blocker",
                detail = "Samsung Auto Blocker blocks sideloading and some permissions. " +
                        "Settings → Security and privacy → Auto Blocker → off.",
                done = isAcked(ctx, "autoblocker"), // informational — user marks it done
                actionLabel = "Open security settings",
                fixIntent = securitySettingsIntent(ctx),
                ackable = true
            ),
            Item(
                key = "battery",
                title = "Ignore battery optimization",
                detail = "So Android doesn't kill the app in the background.",
                done = isIgnoringBatteryOptimizations(ctx),
                actionLabel = "Fix",
                fixIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            ),
            Item(
                key = "notifications",
                title = "Allow notifications",
                detail = "For the ongoing status notification.",
                done = notificationsAllowed(ctx),
                actionLabel = "Open notification settings",
                fixIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            ),
            Item(
                key = "accessibility",
                title = "Enable accessibility service",
                detail = "Lets the app read and navigate the screen.",
                done = isComponentEnabledInSecureSetting(
                    ctx,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    A11Y_PKG,
                    A11Y_CLS
                ),
                actionLabel = "Open accessibility settings",
                fixIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ),
            Item(
                key = "notiflistener",
                title = "Enable notification access",
                detail = "Lets the app read and reply to notifications.",
                done = isComponentEnabledInSecureSetting(
                    ctx,
                    "enabled_notification_listeners",
                    NOTIF_PKG,
                    NOTIF_CLS
                ),
                actionLabel = "Open notification access",
                fixIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            )
        )
    }

    private fun securitySettingsIntent(ctx: Context): Intent? {
        return try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            if (intent.resolveActivity(ctx.packageManager) != null) intent else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun notificationsAllowed(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks whether the flattened component (pkg/cls) appears in a colon-separated
     * Settings.Secure list. Accepts both the full flattened form
     * ("pkg/fully.qualified.Cls") and the short form ("pkg/.RelativeCls"),
     * case-insensitively. Null/blank settings read as false.
     */
    private fun isComponentEnabledInSecureSetting(
        ctx: Context,
        settingKey: String,
        pkg: String,
        cls: String
    ): Boolean {
        val raw: String? = try {
            Settings.Secure.getString(ctx.contentResolver, settingKey)
        } catch (_: Exception) {
            null
        }
        if (raw.isNullOrEmpty()) return false

        val component = ComponentName(pkg, cls)
        val full = component.flattenToString()          // pkg/fully.qualified.Cls
        val short = component.flattenToShortString()    // pkg/.RelativeCls (when cls starts with pkg)

        for (entryRaw in raw.split(':')) {
            val entry = entryRaw.trim()
            if (entry.isEmpty()) continue
            if (entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)) {
                return true
            }
            // Be forgiving: unflatten and compare canonical forms too.
            val parsed = ComponentName.unflattenFromString(entry)
            if (parsed != null && parsed.flattenToString().equals(full, ignoreCase = true)) {
                return true
            }
        }
        // Last resort: raw contains match (covers odd formatting from OEM builds).
        return raw.contains(full, ignoreCase = true) || raw.contains(short, ignoreCase = true)
    }
}
