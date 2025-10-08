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

class MoncchichiLogger private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private val logFile by lazy { createLogFile() }

    fun d(tag: String, message: String) = log(tag, message, Log.DEBUG)
    fun debug(tag: String, message: String) = d(tag, message)
    fun i(tag: String, message: String) = log(tag, message, Log.INFO)
    fun w(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.WARN, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.ERROR, error)

    private fun log(tag: String, message: String, priority: Int, error: Throwable? = null) {
        val formatted = "$tag $message"
        Log.println(priority, DEFAULT_TAG, formatted)
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
        val file = runCatching { logFile }.getOrNull() ?: return
        ioScope.launch {
            fileMutex.withLock {
                runCatching {
                    writeToFile(file, entry)
                }.onFailure {
                    Log.d(DEFAULT_TAG, "File logging disabled: ${it.message}")
                }
            }
        }
    }

    private fun createLogFile(): File? {
        return runCatching {
            val logsDir = appContext.getExternalFilesDir(null)?.parentFile?.let { parent ->
                File(parent, "logs")
            } ?: File(appContext.filesDir, "logs")
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                throw IllegalStateException("Unable to prepare logs directory at ${logsDir.absolutePath}")
            }
            File(logsDir, "moncchichi.log")
        }.onFailure { error ->
            Log.d(DEFAULT_TAG, "Unable to prepare file logger: ${error.message}")
        }.getOrNull()
    }

    private fun writeToFile(target: File, entry: String) {
        if (!target.exists()) {
            target.createNewFile()
        }
        if (target.length() > MAX_FILE_BYTES) {
            val backup = File(target.parentFile, "moncchichi_${System.currentTimeMillis()}.bak")
            target.copyTo(backup, overwrite = true)
            target.writeText("")
        }
        target.appendText(entry)
    }

    companion object {
        private const val DEFAULT_TAG = "Moncchichi"
        private const val MAX_FILE_BYTES = 1_000_000L

        operator fun invoke(context: Context): MoncchichiLogger = MoncchichiLogger(context)
    }
}
