package com.loopermallee.moncchichi.core

import android.util.Log
import java.io.File
import java.io.FileWriter

object MoncchichiLogger {
    private const val TAG = "Moncchichi"
    private val logFile = File("/data/data/com.loopermallee.moncchichi/files/moncchichi.log")

    private fun write(level: String, msg: String, throwable: Throwable? = null) {
        try {
            FileWriter(logFile, true).use { fw ->
                fw.appendLine("[${'$'}{System.currentTimeMillis()}][${'$'}level] ${'$'}msg")
                throwable?.let { fw.appendLine(Log.getStackTraceString(it)) }
            }
        } catch (_: Exception) { }
        when (level) {
            "D" -> Log.d(TAG, msg, throwable)
            "I" -> Log.i(TAG, msg, throwable)
            "W" -> Log.w(TAG, msg, throwable)
            "E" -> Log.e(TAG, msg, throwable)
        }
    }

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)
}
