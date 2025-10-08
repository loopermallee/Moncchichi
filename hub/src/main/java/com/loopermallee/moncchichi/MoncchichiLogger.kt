package com.loopermallee.moncchichi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MoncchichiLogger {
    private const val DEFAULT_TAG = "Moncchichi"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val externalRoot = context.getExternalFilesDir(null)?.parentFile
        val targetDir = (externalRoot?.let { File(it, "logs") } ?: File(context.filesDir, "logs"))
            .apply { mkdirs() }
        logFile = File(targetDir, "moncchichi.log")
        log("[Service]", "Logger initialized at ${logFile?.absolutePath}")
    }

    fun d(tag: String, message: String) = log(tag, message, Log.DEBUG)
    fun debug(tag: String, message: String) = d(tag, message)
    fun i(tag: String, message: String) = log(tag, message, Log.INFO)
    fun w(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.WARN, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.ERROR, error)

    private fun log(tag: String, message: String, priority: Int = Log.DEBUG, error: Throwable? = null) {
        val formattedMessage = "$tag $message"
        Log.println(priority, DEFAULT_TAG, formattedMessage)
        error?.let { Log.println(priority, DEFAULT_TAG, Log.getStackTraceString(it)) }
        val entry = buildString {
            append(dateFormat.format(Date()))
            append(' ')
            append(tag)
            append(' ')
            append(message)
            error?.let {
                append('\n')
                append(Log.getStackTraceString(it))
            }
            append('\n')
        }
        ioScope.launch {
            fileMutex.withLock {
                try {
                    writeToFile(entry)
                } catch (t: Throwable) {
                    Log.e(DEFAULT_TAG, "Failed to persist log", t)
                }
            }
        }
    }

    private suspend fun writeToFile(entry: String) {
        val file = logFile ?: return
        if (!file.exists()) {
            file.createNewFile()
        }
        if (file.length() > 1_000_000) {
            val logDir = file.parentFile ?: return
            val backup = File(logDir, "moncchichi_${System.currentTimeMillis()}.bak")
            file.copyTo(backup, overwrite = true)
            file.writeText("")
        }
        file.appendText(entry)
    }
}
