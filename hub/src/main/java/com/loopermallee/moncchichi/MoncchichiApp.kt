package com.loopermallee.moncchichi

import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MoncchichiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists() && crashFile.length() > 0) {
            val intent = Intent(this, FailsafeMainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            Log.w("CrashGuard", "Detected previous crash log. Redirecting to failsafe activity.")
            return
        }

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
            } catch (_: Exception) {
                Log.e("CrashGuard", "Unable to write crash log to disk")
            }

            // Upload crash log for CI or remote diagnostics
            try {
                CrashUploader.uploadCrashLog(this, errorText)
            } catch (e: Exception) {
                Log.w("CrashGuard", "Crash upload failed: ${e.message}")
            }

            // Redirect to failsafe diagnostics screen on the next activity launch
            try {
                val intent = Intent(applicationContext, FailsafeMainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("CrashGuard", "Unable to launch failsafe activity: ${e.message}")
            }

            // Give process time to flush pending work
            Thread.sleep(1000)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        Log.i("AppBoot", "MoncchichiApp started at ${System.currentTimeMillis()}")
    }
}
