package io.github.jeromekai.latent.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The screen shown after a crash. Deliberately built from plain framework views:
 * it runs in its own process and must not depend on anything that could be what
 * crashed (see [CrashReporter]).
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent?.getStringExtra(CrashReporter.EXTRA_REPORT)
            ?: CrashReporter.readLast(this)
            ?: "No crash details were recorded."

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val title = TextView(this).apply {
            text = "Latent stopped unexpectedly"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = "Copy this report and send it to whoever is fixing the app. " +
                "It contains your phone model and the error, but not your photos, prompts or API key."
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val body = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, pad / 2, 0, 0)
            addView(button("Copy") { copy(report) })
            addView(button("Share") { share(report) })
            addView(button("Close") { finishAndRemoveTask() })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                // Edge-to-edge is enforced on recent Android; this keeps the text clear of the bars.
                fitsSystemWindows = true
                addView(title)
                addView(hint)
                addView(scroll)
                addView(buttons)
            },
        )
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Latent crash report", report))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(report: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                "Share crash report",
            ),
        )
    }
}
