package com.beqa.body

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.beqa.body.security.BridgeTokenStore

/**
 * Settings-style screen that shows the local bridge API token and lets the
 * user copy or rotate it. Pure framework Views, no XML, no AndroidX.
 */
class BridgeTokenActivity : Activity() {

    private var store: BridgeTokenStore? = null
    private var tokenView: TextView? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = BridgeTokenStore(this)

        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Title
        column.addView(TextView(this).apply {
            text = "Bridge token"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        })

        // Subtitle
        column.addView(TextView(this).apply {
            text = "The local API key the Hermes agent uses to reach this phone. Keep it private."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })

        // Token box (bordered, padded, monospace, selectable)
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            val boxPad = dp(12)
            setPadding(boxPad, boxPad, boxPad, boxPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#888888"))
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        tokenView = tv
        column.addView(tv)
        refreshToken()

        // Buttons row
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }

        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener { copyToken() }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { rightMargin = dp(8) }
        })

        buttons.addView(Button(this).apply {
            text = "Rotate"
            setOnClickListener { confirmRotate() }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = dp(8) }
        })

        column.addView(buttons)

        // Helper footer
        column.addView(TextView(this).apply {
            text = "Give this to Hermes as ANDROID_BRIDGE_TOKEN in ~/.hermes/.env, " +
                "or write it to ~/.config/body/bridge_token."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })
    }

    private fun currentToken(): String? = try {
        store?.getOrCreate()
    } catch (e: Exception) {
        null
    }

    private fun refreshToken() {
        tokenView?.text = currentToken() ?: "(unable to load token)"
    }

    private fun copyToken() {
        try {
            val token = currentToken()
            if (token == null) {
                Toast.makeText(this, "No token to copy", Toast.LENGTH_SHORT).show()
                return
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("bridge token", token))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRotate() {
        AlertDialog.Builder(this)
            .setTitle("Rotate token?")
            .setMessage("Rotate token? The agent will need the new value.")
            .setPositiveButton("Rotate") { _, _ -> rotateToken() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rotateToken() {
        try {
            store?.rotate()
            refreshToken()
            Toast.makeText(this, "Rotated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Rotate failed", Toast.LENGTH_SHORT).show()
        }
    }
}
