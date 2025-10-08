package com.loopermallee.moncchichi

import android.app.Application
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MoncchichiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Install global crash handler
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val errorText = sw.toString()

            Log.e("CrashGuard", "Uncaught exception: $errorText")

            // Save crash log for later review
            try {
                val file = File(filesDir, "last_crash.txt")
                file.writeText(errorText)
            } catch (_: Exception) {}

            // Show a dialog with copyable text
            Handler(Looper.getMainLooper()).post {
                try {
                    showCrashDialog(this, throwable, errorText)
                } catch (e: Exception) {
                    Log.e("CrashGuard", "Unable to show crash dialog: ${e.message}")
                }
            }

            // Give UI time to render
            Thread.sleep(4000)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        Log.i("AppBoot", "MoncchichiApp started at ${System.currentTimeMillis()}")
    }

    private fun showCrashDialog(context: Context, throwable: Throwable, details: String) {
        val input = EditText(context)
        input.setText("⚠️ Crash detected:\n${throwable::class.simpleName}: ${throwable.message}\n\n$details")
        input.isSingleLine = false
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.setSelection(0, 0)
        input.setTextIsSelectable(true)
        input.minLines = 10
        input.maxLines = 20

        AlertDialog.Builder(context)
            .setTitle("Moncchichi Crash Report")
            .setView(input)
            .setPositiveButton("Copy & Exit") { dialog, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CrashReport", input.text.toString()))
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
