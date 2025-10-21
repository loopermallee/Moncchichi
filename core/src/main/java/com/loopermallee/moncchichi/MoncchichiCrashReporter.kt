package com.loopermallee.moncchichi

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Volatile

object MoncchichiCrashReporter : Thread.UncaughtExceptionHandler {
    private const val TAG = "MoncchichiCrashReporter"
    private const val GIST_API = "https://api.github.com/gists"
    private const val CRASH_DIR_NAME = "crash"
    private const val LAST_CRASH_FILE = "last_crash.txt"
    private const val LOG_TAIL_BYTES = 96 * 1024

    private val initialized = AtomicBoolean(false)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupHooks = ConcurrentHashMap<String, () -> Unit>()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var application: Application? = null
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile
    private var tokenProvider: (() -> String?)? = null

    fun init(app: Application, provider: () -> String?) {
        if (!initialized.compareAndSet(false, true)) {
            Log.i(TAG, "Crash reporter already initialized")
            return
        }
        application = app
        tokenProvider = provider
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Crash reporter installed for ${app.packageName}")
    }

    fun registerCleanupHook(key: String, hook: () -> Unit) {
        cleanupHooks[key] = hook
    }

    fun unregisterCleanupHook(key: String) {
        cleanupHooks.remove(key)
    }

    fun reportNonFatal(tag: String, throwable: Throwable) {
        Log.e(TAG, "Non-fatal exception captured: $tag", throwable)
        backgroundScope.launch {
            val app = application ?: return@launch
            val crashFile = writeCrashFile(app, Thread.currentThread(), throwable, tag)
            val token = tokenProvider?.invoke().orEmpty()
            if (token.isNotBlank()) {
                uploadToGist(crashFile, token, tag, isFatal = false)
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val app = application
        if (app == null) {
            Log.e(TAG, "Uncaught exception before reporter initialization", throwable)
            previousHandler?.uncaughtException(thread, throwable)
            return
        }
        Log.e(TAG, "Fatal exception caught", throwable)
        runCleanupHooks()
        val crashFile = writeCrashFile(app, thread, throwable, fatalReason = "Unhandled exception")
        val token = tokenProvider?.invoke().orEmpty()
        if (token.isNotBlank()) {
            backgroundScope.launch {
                uploadToGist(crashFile, token, fatalReason = "Unhandled exception", isFatal = true)
            }
        }
        launchFailsafe(app.applicationContext)
        previousHandler?.uncaughtException(thread, throwable)
            ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
    }

    private fun runCleanupHooks() {
        cleanupHooks.entries.forEach { entry ->
            runCatching { entry.value.invoke() }.onFailure {
                Log.w(TAG, "Cleanup hook ${entry.key} failed: ${it.message}")
            }
        }
    }

    private fun writeCrashFile(
        app: Application,
        thread: Thread,
        throwable: Throwable,
        fatalReason: String,
    ): File {
        val crashDir = File(app.filesDir, CRASH_DIR_NAME).apply { mkdirs() }
        val logFile = File(crashDir, LAST_CRASH_FILE)
        val timestamp = System.currentTimeMillis()
        val formatted = buildString {
            appendLine("=== Moncchichi Crash Log ===")
            appendLine("Timestamp: ${Date(timestamp)}")
            appendLine("Thread: ${thread.name}")
            appendLine("Fatal Reason: $fatalReason")
            appendLine("App Version: ${getAppVersion(app)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("--- Stacktrace ---")
            appendLine(throwable.stackTraceToString())
            val tail = MoncchichiLogger.recentLogTail(LOG_TAIL_BYTES)
            if (tail.isNotBlank()) {
                appendLine()
                appendLine("--- Recent Log Tail ---")
                appendLine(tail)
            }
        }
        runCatching { logFile.writeText(formatted) }.onFailure {
            Log.e(TAG, "Failed to write crash log", it)
        }
        return logFile
    }

    private fun uploadToGist(
        crashFile: File,
        token: String,
        fatalReason: String,
        isFatal: Boolean,
    ) {
        val fileName = "MoncchichiCrashLog_${gistTimestamp()}.txt"
        val payload = JSONObject().apply {
            put("description", buildDescription(fatalReason, isFatal))
            put("public", false)
            put("files", JSONObject().apply {
                put(fileName, JSONObject().apply {
                    put("content", crashFile.readText())
                })
            })
        }
        runCatching {
            val connection = (URL(GIST_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { stream ->
                    val body = payload.toString().toByteArray(Charsets.UTF_8)
                    stream.write(body)
                    stream.flush()
                }
            }
            val responseCode = connection.responseCode
            val reader = if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream))
            } else {
                BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
            }
            val response = reader.use { it.readText() }
            if (responseCode in 200..299) {
                Log.i(TAG, "Crash log uploaded to gist: $response")
            } else {
                Log.e(TAG, "Failed to upload crash log: HTTP $responseCode $response")
            }
            connection.disconnect()
        }.onFailure {
            Log.e(TAG, "Error uploading crash log", it)
        }
    }

    private fun buildDescription(reason: String, isFatal: Boolean): String {
        val type = if (isFatal) "Fatal" else "Non-Fatal"
        return "$type crash report captured on ${Date()} — $reason"
    }

    private fun getAppVersion(context: Context): String {
        return runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName ?: "unknown"} (${pkg.longVersionCode})"
        }.getOrElse { "unknown" }
    }

    private fun launchFailsafe(context: Context) {
        runCatching {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.loopermallee.moncchichi.FailsafeMainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Unable to launch failsafe activity: ${it.message}")
        }
    }

    private fun gistTimestamp(): String = synchronized(timestampFormat) { timestampFormat.format(Date()) }
}
