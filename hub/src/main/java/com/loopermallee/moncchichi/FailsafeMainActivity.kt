package com.loopermallee.moncchichi

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class FailsafeMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "\uD83D\uDD0D Booting Moncchichi diagnostics...\n"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 14f
            setPadding(24, 48, 24, 24)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        setContentView(scrollView)

        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                textView.append("\n\uD83D\uDCC4 Previous crash log:\n\n" + crashFile.readText())
            } else {
                textView.append("\n✅ No previous crash log found.")
            }

            textView.append("\n\n\uD83D\uDCBC App files path:\n${filesDir.absolutePath}")
        } catch (e: Exception) {
            textView.append("\n❌ Failsafe error: ${e.message}")
        }
    }
}
