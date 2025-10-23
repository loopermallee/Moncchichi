package com.loopermallee.moncchichi.hub.util

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

object LogFormatter {
    enum class Type { APP, BLE, AI, SYS, ERROR }

    @Suppress("UNUSED_PARAMETER")
    fun format(ctx: Context, raw: String): Spannable {
        val color = when {
            raw.contains("Connected to") -> Color.parseColor("#9BE37B")
            raw.contains("[ERR]") || raw.contains("FAILED") || raw.contains("Bluetooth off") -> Color.parseColor("#FF6B6B")
            raw.contains("[BLE]") -> Color.parseColor("#A691F2")
            raw.contains("[DIAG]") -> Color.parseColor("#A691F2")
            raw.contains("[AI]") -> Color.parseColor("#6F5AE6")
            raw.contains("[SYS]") -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#DDDDDD")
        }
        return SpannableString(raw).apply {
            setSpan(
                ForegroundColorSpan(color),
                0,
                raw.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
