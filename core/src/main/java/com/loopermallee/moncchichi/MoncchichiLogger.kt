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
import kotlin.jvm.Volatile

class MoncchichiLogger private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private val logDir by lazy { prepareLogsDir() }
    @Volatile
    private var cachedLogFile: File? = null
    @Volatile
    private var cachedDay: String? = null
    private val dayFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    fun d(tag: String, message: String) = log(tag, message, Log.DEBUG)
    fun debug(tag: String, message: String) = d(tag, message)
    fun i(tag: String, message: String) = log(tag, message, Log.INFO)
    fun w(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.WARN, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(tag, message, Log.ERROR, error)
    fun heartbeat(tag: String, message: String) = logWithCategory("HEARTBEAT", tag, message, Log.DEBUG)
    fun backoff(tag: String, message: String) = logWithCategory("BACKOFF", tag, message, Log.INFO)
    fun recovery(tag: String, message: String, error: Throwable? = null) =
        logWithCategory("RECOVERY", tag, message, Log.INFO, error)

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
        ioScope.launch {
            fileMutex.withLock {
                val file = resolveLogFile() ?: return@withLock
                runCatching {
                    writeToFile(file, entry)
                }.onFailure {
                    Log.d(DEFAULT_TAG, "File logging disabled: ${it.message}")
                }
            }
        }
    }

    private fun prepareLogsDir(): File? {
        return runCatching {
            val targetDir = appContext.getExternalFilesDir(null)?.parentFile?.let { parent ->
                File(parent, "logs")
            } ?: File(appContext.filesDir, "logs")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IllegalStateException("Unable to prepare logs directory at ${targetDir.absolutePath}")
            }
            targetDir
        }.onFailure { error ->
            Log.d(DEFAULT_TAG, "Unable to prepare file logger: ${error.message}")
        }.getOrNull()
    }

    private fun resolveLogFile(): File? {
        val directory = logDir ?: return null
        val today = dayFormat.format(Date())
        val cached = cachedDay
        if (cached == today) {
            val file = cachedLogFile
            if (file != null && file.exists()) {
                return file
            }
        }
        cleanupOldLogs(directory)
        val file = File(directory, "moncchichi_$today.log")
        if (!file.exists()) {
            runCatching { file.createNewFile() }.onFailure {
                Log.d(DEFAULT_TAG, "Unable to create log file: ${it.message}")
                return null
            }
        }
        cachedDay = today
        cachedLogFile = file
        return file
    }

    private fun cleanupOldLogs(directory: File) {
        val files = directory.listFiles { _, name -> name.startsWith("moncchichi_") && name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(LOG_FILE_RETENTION).forEach { old ->
            runCatching { old.delete() }
        }
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

    private fun logWithCategory(
        category: String,
        tag: String,
        message: String,
        priority: Int,
        error: Throwable? = null,
    ) {
        log("$tag [$category]", message, priority, error)
    }

    companion object {
        private const val DEFAULT_TAG = "Moncchichi"
        private const val MAX_FILE_BYTES = 1_000_000L
        private const val LOG_FILE_RETENTION = 3

        operator fun invoke(context: Context): MoncchichiLogger = MoncchichiLogger(context)
    }
}
