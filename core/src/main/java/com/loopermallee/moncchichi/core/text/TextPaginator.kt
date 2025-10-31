package com.loopermallee.moncchichi.core.text

import android.graphics.Paint
import android.graphics.Typeface
import java.nio.charset.Charset
import kotlin.math.min

/**
 * Splits arbitrary UTF-8 text into the packet layout expected by the Even G1 glasses.
 * The glasses render up to five lines per screen where the first three lines are transported
 * in the first packet and the last two lines are transported in the second packet.
 */
class TextPaginator @JvmOverloads constructor(
    private val config: Config = Config(),
    private val measurer: TextMeasurer = AndroidTextMeasurer(config),
) {

    data class Config(
        val targetWidthPx: Float = 488f,
        val fontSizePx: Float = 21f,
        val linesPerScreen: List<Int> = listOf(3, 2),
        val charset: Charset = Charsets.UTF_8,
    ) {
        val safeLinesPerScreen: List<Int> =
            linesPerScreen.ifEmpty { listOf(1) }.map { it.coerceAtLeast(1) }
        val totalLinesPerScreen: Int = safeLinesPerScreen.sum().coerceAtLeast(1)
    }

    interface TextMeasurer {
        fun measure(text: String): Float
    }

    class AndroidTextMeasurer(config: Config) : TextMeasurer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSizePx
            typeface = Typeface.DEFAULT
        }

        override fun measure(text: String): Float {
            if (text.isEmpty()) return 0f
            return paint.measureText(text)
        }
    }

    data class Packet internal constructor(
        val screenIndex: Int,
        val partIndex: Int,
        val lines: List<String>,
        private val charset: Charset,
    ) {
        val text: String = lines.joinToString(separator = "\n")

        fun toByteArray(): ByteArray = text.toByteArray(charset)

        fun isBlank(): Boolean = lines.all { it.isBlank() }
    }

    data class PaginationResult internal constructor(
        val packets: List<Packet>,
    ) {
        val totalPages: Int = packets.size.coerceAtLeast(1)

        fun toByteArrays(chunkCapacity: Int): List<ByteArray> {
            if (packets.isEmpty()) {
                return listOf(ByteArray(0))
            }
            val capacity = chunkCapacity.coerceAtLeast(1)
            val frames = mutableListOf<ByteArray>()
            for (packet in packets) {
                val bytes = packet.toByteArray()
                if (bytes.isEmpty()) {
                    frames += ByteArray(0)
                    continue
                }
                var offset = 0
                while (offset < bytes.size) {
                    val end = min(bytes.size, offset + capacity)
                    frames += bytes.copyOfRange(offset, end)
                    offset = end
                }
            }
            return frames.ifEmpty { listOf(ByteArray(0)) }
        }
    }

    fun paginate(message: String): PaginationResult {
        val normalized = message.replace("\r\n", "\n").replace('\r', '\n')
        val lines = mutableListOf<String>()
        val paragraphs = normalized.split("\n", ignoreCase = false, limit = -1)
        for (paragraph in paragraphs) {
            val wrapped = wrapParagraph(paragraph)
            if (wrapped.isEmpty()) {
                lines += ""
            } else {
                lines += wrapped
            }
        }

        val packets = mutableListOf<Packet>()
        val queue: ArrayDeque<String> = ArrayDeque(lines)
        var screenIndex = 0
        val totalLinesPerScreen = config.totalLinesPerScreen
        if (queue.isEmpty()) {
            packets += buildPacketsForScreen(screenIndex, List(totalLinesPerScreen) { "" })
        } else {
            while (queue.isNotEmpty()) {
                val screenLines = MutableList(totalLinesPerScreen) { "" }
                var slot = 0
                while (slot < totalLinesPerScreen && queue.isNotEmpty()) {
                    screenLines[slot] = queue.removeFirst()
                    slot += 1
                }
                packets += buildPacketsForScreen(screenIndex, screenLines)
                screenIndex += 1
            }
        }

        return PaginationResult(packets)
    }

    private fun buildPacketsForScreen(screenIndex: Int, screenLines: List<String>): List<Packet> {
        val packets = mutableListOf<Packet>()
        var offset = 0
        config.safeLinesPerScreen.forEachIndexed { partIndex, count ->
            val slice = (0 until count).map { index ->
                screenLines.getOrElse(offset + index) { "" }
            }
            packets += Packet(screenIndex, partIndex, slice, config.charset)
            offset += count
        }
        return packets
    }

    private fun wrapParagraph(paragraph: String): List<String> {
        if (paragraph.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var lastWhitespace = -1

        fun recomputeLastWhitespace() {
            lastWhitespace = (current.length - 1 downTo 0).firstOrNull { index ->
                current[index].isWhitespace()
            } ?: -1
        }

        paragraph.forEach { ch ->
            current.append(ch)
            if (ch.isWhitespace()) {
                lastWhitespace = current.length - 1
            }
            val width = measurer.measure(current.toString())
            if (width > config.targetWidthPx && current.isNotEmpty()) {
                if (lastWhitespace >= 0) {
                    val breakIndex = lastWhitespace + 1
                    val line = current.substring(0, breakIndex).trimEnd()
                    result += line
                    val remainder = current.substring(breakIndex).trimStart()
                    current = StringBuilder(remainder)
                } else {
                    if (current.length > 1) {
                        val keep = current.substring(0, current.length - 1)
                        result += keep
                        current = StringBuilder(current.substring(current.length - 1))
                    } else {
                        result += current.toString()
                        current = StringBuilder()
                    }
                }
                recomputeLastWhitespace()
            }
        }

        val finalLine = current.toString().trimEnd()
        if (finalLine.isNotEmpty() || result.isEmpty()) {
            result += finalLine
        }
        return result
    }
}
