package com.loopermallee.moncchichi

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        val stackTrace = e.stackTraceToString()

        Handler(Looper.getMainLooper()).post {
            showDialog(stackTrace)
        }
        defaultHandler?.uncaughtException(t, e)
    }

    private fun showDialog(trace: String) {
        val textView = TextView(context).apply {
            text = trace
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        val scrollView = ScrollView(context).apply { addView(textView) }

        AlertDialog.Builder(context)
            .setTitle("App Error")
            .setView(scrollView)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Crash", trace)
                clipboard.setPrimaryClip(clip)
            }
            .setNegativeButton("Close") { _, _ ->
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
            Handler(Looper.getMainLooper()).post {
                Thread.currentThread().uncaughtExceptionHandler = CrashHandler(context)
            }
        }
    }
}
