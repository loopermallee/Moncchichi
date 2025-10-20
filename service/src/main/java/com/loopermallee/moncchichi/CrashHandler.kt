package com.loopermallee.moncchichi

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

class CrashHandler private constructor(context: Context) : Thread.UncaughtExceptionHandler {

    private val appContext = context.applicationContext

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val logDir = File(appContext.filesDir, "crash").apply { mkdirs() }
            val logFile = File(logDir, "last_crash.txt")
            logFile.writeText(
                "=== Uncaught Exception ===\n" +
                    "Thread: ${'$'}{t.name}\n" +
                    "Time: ${'$'}{System.currentTimeMillis()}\n" +
                    "Message: ${'$'}{e.message}\n\n" +
                    e.stackTraceToString()
            )

            Log.e(TAG, "Fatal exception caught", e)

            val intent = Intent().apply {
                setClassName(appContext.packageName, "com.loopermallee.moncchichi.FailsafeMainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            appContext.startActivity(intent)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to handle crash", error)
        } finally {
            Thread.sleep(500)
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        private const val TAG = "CrashHandler"

        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }
    }
}
