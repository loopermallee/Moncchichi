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

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val errorText = sw.toString()

            try {
                val file = File(filesDir, "last_crash.txt")
                file.writeText(errorText)
                Log.e("CrashGuard", "Crash saved to ${'$'}{file.absolutePath}")
            } catch (e: Exception) {
                Log.e("CrashGuard", "Failed to write crash log: ${'$'}{e.message}")
            }

            try {
                val intent = Intent(applicationContext, FailsafeMainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("CrashGuard", "Failsafe launch failed: ${'$'}{e.message}")
            }

            Thread.sleep(500)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        Log.i("AppBoot", "MoncchichiApp initialized safely.")
    }
}
