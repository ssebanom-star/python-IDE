package com.ssebanom.pythonide

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A crash-proof screen that shows a stack trace on the device. Its UI is
 * built entirely in code (no XML, no Material theme) so it can display even
 * if the normal UI failed to inflate. Launched by the global uncaught
 * exception handler and by MainActivity's own try/catch.
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_TRACE = "trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "No details."

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1F22"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Python IDE crashed"
            setTextColor(Color.parseColor("#F26D6D"))
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "Please screenshot this or tap Share, and send it to the " +
                "developer so it can be fixed."
            setTextColor(Color.parseColor("#BCBEC4"))
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        val details = TextView(this).apply {
            text = trace
            setTextColor(Color.parseColor("#D8DBE0"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("crash", trace))
            }
        })
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Python IDE crash report")
                    putExtra(Intent.EXTRA_TEXT, trace)
                }
                startActivity(Intent.createChooser(send, "Share crash report"))
            }
        })
        buttons.addView(Button(this).apply {
            text = "Restart"
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage(packageName)
                i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                finish()
            }
        })
        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT))
    }
}
