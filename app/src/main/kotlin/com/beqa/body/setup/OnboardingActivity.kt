package com.beqa.body.setup

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OnboardingActivity : Activity() {

    private val density: Float
        get() = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val items = SetupStatus.items(this)
        val doneCount = items.count { it.done }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // Header
        root.addView(TextView(this).apply {
            text = "Set up Body"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Grant these once. Return here to see them turn green."
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(16) }
        })

        // Rows
        for (item in items) {
            root.addView(buildRow(item))
        }

        // Summary
        root.addView(TextView(this).apply {
            text = if (doneCount == items.size && items.isNotEmpty())
                "${items.size} of ${items.size} complete\nAll set — you can close this."
            else
                "$doneCount of ${items.size} complete"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        })

        // Refresh button
        root.addView(Button(this).apply {
            text = "Refresh"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL }
            setOnClickListener { render() }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)
    }

    private fun buildRow(item: SetupStatus.Item): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), 0xFFCCCCCC.toInt())
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(TextView(this).apply {
            text = if (item.done) "✓" else "○"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (item.done) 0xFF2E7D32.toInt() else 0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(12) }
        })

        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        textBlock.addView(TextView(this).apply {
            text = item.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        textBlock.addView(TextView(this).apply {
            text = item.detail
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        top.addView(textBlock)
        row.addView(top)

        if (!item.done && item.fixIntent != null) {
            row.addView(Button(this).apply {
                text = item.actionLabel
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6); gravity = Gravity.END }
                setOnClickListener {
                    try {
                        startActivity(item.fixIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@OnboardingActivity,
                            "Couldn't open: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        }

        if (!item.done && item.ackable) {
            row.addView(Button(this).apply {
                text = "Mark done"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); gravity = Gravity.END }
                setOnClickListener {
                    SetupStatus.acknowledge(this@OnboardingActivity, item.key)
                    render()
                }
            })
        }

        return row
    }
}
