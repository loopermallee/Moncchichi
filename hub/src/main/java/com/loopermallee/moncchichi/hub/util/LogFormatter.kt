package com.loopermallee.moncchichi.hub.util

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.hub.R

object LogFormatter {
    enum class Type { APP, BLE, AI, SYS, ERROR }

    fun format(ctx: Context, raw: String): Spannable {
        val color = when {
            raw.contains("[ERROR]") -> R.color.red_500
            raw.contains("[BLE]") -> R.color.teal_700
            raw.contains("[AI]") -> R.color.purple_500
            raw.contains("[SYS]") -> R.color.orange_700
            else -> R.color.gray_800
        }
        return SpannableString(raw).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(ctx, color)),
                0,
                raw.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
