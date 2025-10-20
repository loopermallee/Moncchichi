package com.loopermallee.moncchichi

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class FailsafeMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "last_crash.txt")
        val content = if (crashFile.exists()) crashFile.readText() else "No crash log found."

        val textView = TextView(this).apply {
            text = "\u26A0\uFE0F The app encountered an error and recovered safely:\n\n$content"
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }

        val scrollView = ScrollView(this).apply { addView(textView) }
        setContentView(scrollView)

        // Auto-clear crash log to avoid repeated failsafe redirects once user has seen the log
        crashFile.delete()
    }
}
