package com.loopermallee.moncchichi

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CrashUploader {
    fun uploadCrashLog(context: Context, log: String) {
        try {
            // Persist a copy that CI pipelines can scoop up as an artifact
            val artifactFile = File(context.filesDir, "upload_crash.txt")
            artifactFile.writeText(log)
            Log.i("CrashUploader", "Crash log persisted for CI artifact collection")

            // Optionally forward to a webhook when configured
            val webhookUrl = System.getenv("CRASH_WEBHOOK_URL") ?: return
            val connection = URL(webhookUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(log.toByteArray())
            }
            Log.i("CrashUploader", "Crash log uploaded to webhook")
        } catch (e: Exception) {
            Log.e("CrashUploader", "Failed to upload crash log: ${e.message}")
        }
    }
}
