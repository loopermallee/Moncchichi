package com.loopermallee.moncchichi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private val logDir by lazy { prepareLogsDir() }
    private val timestampFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

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
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = message,
            throwable = error?.let { Log.getStackTraceString(it) }
        )
        logStream.tryEmit(event)
        val entry = buildString {
            append(currentTimestamp())
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
        recordInMemory(entry)
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
        val file = File(directory, LOG_FILE_NAME)
        if (!file.exists()) {
            runCatching { file.createNewFile() }.onFailure {
                Log.d(DEFAULT_TAG, "Unable to create log file: ${it.message}")
                return null
            }
        }
        return file
    }

    private fun writeToFile(target: File, entry: String) {
        if (!target.exists()) {
            target.createNewFile()
        }
        val directory = target.parentFile ?: return
        val entryBytes = entry.toByteArray(Charsets.UTF_8)
        if (target.length() + entryBytes.size > MAX_FILE_BYTES) {
            rotateLogs(directory, target)
        }
        target.appendBytes(entryBytes)
        trimLogDirectory(directory)
    }

    private fun rotateLogs(directory: File, activeFile: File) {
        for (index in MAX_ROLLED_FILES downTo 1) {
            val from = File(directory, if (index == 1) LOG_FILE_NAME else "$LOG_FILE_NAME.${index - 1}")
            if (!from.exists()) continue
            val to = File(directory, "$LOG_FILE_NAME.$index")
            if (to.exists()) {
                runCatching { to.delete() }
            }
            runCatching { from.renameTo(to) }
        }
        runCatching { activeFile.createNewFile() }
    }

    private fun trimLogDirectory(directory: File) {
        val files = directory.listFiles { _, name -> name.startsWith(LOG_FILE_NAME) } ?: return
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_DIRECTORY_BYTES) return
        val rotated = files
            .filter { it.name != LOG_FILE_NAME }
            .sortedByDescending { nameSuffixIndex(it.name) }
        for (file in rotated) {
            if (totalBytes <= MAX_DIRECTORY_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
            }
        }
    }

    private fun recordInMemory(entry: String) {
        val byteCount = entry.toByteArray(Charsets.UTF_8).size
        synchronized(memoryLock) {
            memoryBuffer.addLast(BufferEntry(entry, byteCount))
            memoryBufferSize += byteCount
            while (memoryBufferSize > MAX_IN_MEMORY_BYTES && memoryBuffer.isNotEmpty()) {
                val removed = memoryBuffer.removeFirst()
                memoryBufferSize -= removed.byteCount
            }
        }
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
        private const val LOG_FILE_NAME = "moncchichi.log"
        private const val MAX_FILE_BYTES = 2_500_000L
        private const val MAX_ROLLED_FILES = 1
        private const val MAX_DIRECTORY_BYTES = 5_000_000L
        private const val MAX_IN_MEMORY_BYTES = 128 * 1024

        private val logStream = MutableSharedFlow<LogEvent>(
            replay = 50,
            extraBufferCapacity = 150,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        private val memoryBuffer = ArrayDeque<BufferEntry>()
        private val memoryLock = Any()
        private var memoryBufferSize = 0
        private data class BufferEntry(val text: String, val byteCount: Int)

        operator fun invoke(context: Context): MoncchichiLogger = MoncchichiLogger(context)

        fun logEvents(): SharedFlow<LogEvent> = logStream.asSharedFlow()

        fun recentLogTail(maxBytes: Int = MAX_IN_MEMORY_BYTES): String {
            val snapshot: List<BufferEntry>
            synchronized(memoryLock) {
                if (memoryBuffer.isEmpty()) {
                    return ""
                }
                snapshot = memoryBuffer.toList()
            }
            val collected = ArrayDeque<String>()
            var collectedBytes = 0
            for (entry in snapshot.asReversed()) {
                if (collectedBytes + entry.byteCount > maxBytes) {
                    continue
                }
                collectedBytes += entry.byteCount
                collected.addFirst(entry.text)
            }
            return buildString {
                collected.forEach { append(it) }
            }
        }

        private fun nameSuffixIndex(name: String): Int = name.substringAfterLast('.', missingDelimiterValue = "-1").toIntOrNull() ?: -1
    }

    private fun currentTimestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }

    data class LogEvent(
        val timestamp: Long,
        val priority: Int,
        val tag: String,
        val message: String,
        val throwable: String? = null,
    )
}
