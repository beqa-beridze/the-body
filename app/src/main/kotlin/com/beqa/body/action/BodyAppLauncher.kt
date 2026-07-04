package com.beqa.body.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.beqa.body.a11y.BodyAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launches apps and fires view-intents for a local caller.
 *
 * Pure Android framework + org.json; no AndroidX, no Gradle.
 */
object BodyAppLauncher {

    /**
     * Launch an app by package name, or open a URL via ACTION_VIEW.
     * Package takes priority when both are given.
     */
    fun launch(pkg: String?, url: String?, context: Context): JSONObject {
        if (pkg != null && pkg.isNotBlank()) {
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return JSONObject()
                    .put("ok", false)
                    .put("error", "app_not_found")
                    .put("pkg", pkg)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(i)
                JSONObject().put("ok", true).put("launched", pkg)
            } catch (e: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("error", "launch_failed")
                    .put("detail", e.message ?: JSONObject.NULL)
            }
        }

        if (url != null && url.isNotBlank()) {
            // Covers https://, geo:, tel:, mailto:, etc.
            return try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                JSONObject().put("ok", true).put("opened", url)
            } catch (e: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("error", "launch_failed")
                    .put("detail", e.message ?: JSONObject.NULL)
            }
        }

        return JSONObject().put("ok", false).put("error", "missing_params")
    }

    /**
     * List launchable apps, optionally filtered by label or package
     * (case-insensitive substring), sorted by label.
     */
    fun listApps(filter: String? = null, context: Context): JSONObject {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(intent, 0)

            var entries = resolved.mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                val packageName = ai.packageName ?: return@mapNotNull null
                val label = try {
                    info.loadLabel(pm)?.toString() ?: packageName
                } catch (e: Exception) {
                    packageName
                }
                Pair(packageName, label)
            }

            if (filter != null && filter.isNotBlank()) {
                entries = entries.filter { (packageName, label) ->
                    label.contains(filter, ignoreCase = true) ||
                        packageName.contains(filter, ignoreCase = true)
                }
            }

            entries = entries.sortedBy { it.second.lowercase() }

            val apps = JSONArray()
            for ((packageName, label) in entries) {
                apps.put(
                    JSONObject()
                        .put("package", packageName)
                        .put("label", label)
                )
            }

            JSONObject()
                .put("ok", true)
                .put("count", apps.length())
                .put("apps", apps)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "list_failed")
                .put("detail", e.message ?: JSONObject.NULL)
        }
    }

    /** Report the current foreground package as seen by the accessibility service. */
    fun currentApp(): JSONObject {
        val pkg = BodyAccessibilityService.instance?.foregroundPackage
        return JSONObject()
            .put("ok", true)
            .put("package", pkg ?: JSONObject.NULL)
    }
}
