package com.loopermallee.moncchichi

import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

class MoncchichiApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
        }

        Log.i("AppBoot", "MoncchichiApp initialized safely.")
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        try {
            val logDir = File(filesDir, "crash").apply { mkdirs() }
            val logFile = File(logDir, "last_crash.txt")

            logFile.writeText(
                "=== Uncaught Exception ===\n" +
                    "Thread: ${'$'}{thread.name}\n" +
                    "Time: ${'$'}{System.currentTimeMillis()}\n" +
                    "Message: ${'$'}{throwable.message}\n\n" +
                    Log.getStackTraceString(throwable)
            )

            Log.e("MoncchichiApp", "Fatal exception caught", throwable)

            val intent = Intent(this, FailsafeMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } catch (error: Throwable) {
            Log.e("MoncchichiApp", "Error writing crash log", error)
        } finally {
            Thread.sleep(500)
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}
