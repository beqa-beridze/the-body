package com.beqa.body

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.beqa.body.service.BodyForegroundService

/**
 * Minimal landing screen: ensures the foreground service is running and shows status.
 * (Grows in later increments; for now it exists so the service has a launcher + a way
 * to be (re)started, and so POST_NOTIFICATIONS is requested on API 33+.)
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val svc = Intent(this, BodyForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 128, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "Body"
            textSize = 34f
        })
        root.addView(TextView(this).apply {
            text = "foreground service running · local only\ncom.beqa.body · v${BuildConfig.VERSION_NAME}"
            textSize = 15f
            setPadding(0, 32, 0, 0)
        })
        setContentView(root)
    }
}
