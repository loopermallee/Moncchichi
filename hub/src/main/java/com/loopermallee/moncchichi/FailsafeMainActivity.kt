package com.loopermallee.moncchichi

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.io.File

class FailsafeMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(File(filesDir, "crash"), "last_crash.txt")
        val content = if (crashFile.exists()) crashFile.readText() else "No crash log found."

        val textView = TextView(this).apply {
            text = "The app recovered from a crash.\nCheck logs in files/crash/last_crash.txt\n\n$content"
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        setContentView(textView)
    }
}
