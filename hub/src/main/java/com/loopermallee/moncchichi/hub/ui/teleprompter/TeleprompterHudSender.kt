package com.loopermallee.moncchichi.hub.ui.teleprompter

import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.model.Repository
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val MAX_PAYLOAD_BYTES = 40
private const val HUD_BURST_DELAY_MS = 40L
private val whitespaceRegex = Regex("\\s+")

class TeleprompterHudSender(
    private val repository: Repository,
    private val memory: MemoryRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class HudTarget(val id: String, val side: LensSide)

    data class HudSendResult(val success: Boolean, val timestamp: Long)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var lastLine: String? = null
    private var failureLogged = false

    suspend fun send(line: String, targets: List<HudTarget>): HudSendResult? {
        if (targets.isEmpty()) return null
        val normalized = normalize(line)
        if (normalized.isEmpty()) return null
        if (normalized == lastLine) return null
        lastLine = normalized

        val timestamp = clock()
        val timestampLabel = timeFormat.format(Date(timestamp))
        val logPreview = preview(normalized)
        targets.forEach { target ->
            logConsole("[PROMPT][${sideLabel(target.side)}] $logPreview @ $timestampLabel", timestampLabel)
        }

        var allSuccess = true
        targets.forEachIndexed { index, target ->
            val success = runCatching { repository.displayTextPage(target.id, listOf(normalized)) }
                .getOrDefault(false)
            if (!success) {
                allSuccess = false
            }
            if (index != targets.lastIndex) {
                delay(HUD_BURST_DELAY_MS)
            }
        }

        if (!allSuccess) {
            if (!failureLogged) {
                logConsole("[PROMPT] send failed", timestampLabel)
                failureLogged = true
            }
            return HudSendResult(success = false, timestamp = timestamp)
        }

        failureLogged = false
        return HudSendResult(success = true, timestamp = timestamp)
    }

    private suspend fun logConsole(message: String, timestampLabel: String) {
        memory.addConsoleLine("[$timestampLabel] $message")
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val ascii = buildString(trimmed.length) {
            trimmed.forEach { ch ->
                append(
                    if (ch.code in 32..126) {
                        ch
                    } else {
                        ' '
                    }
                )
            }
        }
        val compact = whitespaceRegex.replace(ascii, " ").trim()
        if (compact.isEmpty()) return ""
        return limitToBytes(compact, MAX_PAYLOAD_BYTES)
    }

    private fun preview(text: String): String {
        return if (text.length <= 60) text else text.take(57) + "…"
    }

    private fun limitToBytes(text: String, maxBytes: Int): String {
        var used = 0
        val builder = StringBuilder()
        text.forEach { ch ->
            val encoded = ch.toString().toByteArray(Charsets.UTF_8)
            if (used + encoded.size > maxBytes) {
                return builder.toString().trimEnd()
            }
            builder.append(ch)
            used += encoded.size
        }
        return builder.toString().trimEnd()
    }

    private fun sideLabel(side: LensSide): String = when (side) {
        LensSide.LEFT -> "L"
        LensSide.RIGHT -> "R"
        LensSide.UNKNOWN -> "?"
    }
}
