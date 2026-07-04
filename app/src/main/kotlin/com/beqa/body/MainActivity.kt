package com.beqa.body

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.beqa.body.service.BodyForegroundService
import com.beqa.body.setup.OnboardingActivity
import com.beqa.body.setup.SetupStatus

/**
 * Landing screen: ensures the foreground service is running, shows setup progress, and
 * links to the onboarding checklist.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

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
        root.addView(TextView(this).apply { text = "Body"; textSize = 34f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24); gravity = Gravity.CENTER }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Set up permissions"
            setOnClickListener { startActivity(Intent(this@MainActivity, OnboardingActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Bridge token"
            setOnClickListener { startActivity(Intent(this@MainActivity, BridgeTokenActivity::class.java)) }
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val items = SetupStatus.items(this)
        val done = items.count { it.done }
        status.text = "foreground service · local only\nsetup: $done of ${items.size} complete\ncom.beqa.body · v${BuildConfig.VERSION_NAME}"
    }
}
