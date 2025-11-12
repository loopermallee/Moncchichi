package com.loopermallee.moncchichi

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.loopermallee.moncchichi.R
import java.io.File

class FailsafeMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(File(filesDir, "crash"), "last_crash.txt")
        val content = if (crashFile.exists()) crashFile.readText() else "No crash log found."

        setContentView(R.layout.activity_failsafe)

        val messageView = findViewById<TextView>(R.id.text_failsafe_message)
        val logView = findViewById<TextView>(R.id.text_failsafe_log)

        messageView.text = "The app recovered from a crash.\nCheck logs in files/crash/last_crash.txt"
        logView.text = content
    }
}
