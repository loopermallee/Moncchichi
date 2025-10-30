package com.loopermallee.moncchichi.core

import java.util.Locale

object BleNameParser {
    enum class Lens {
        LEFT,
        RIGHT,
        UNKNOWN,
    }

    private val tokenDelimiter = Regex("[_\\-\\s]+")
    private val nonAlphaNumeric = Regex("[^A-Z0-9]")
    private val sideTokens = setOf("L", "R", "LEFT", "RIGHT")

    fun derivePairToken(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        val withoutPrefix = if (trimmed.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)) {
            trimmed.substring(DEVICE_NAME_PREFIX.length)
        } else {
            trimmed
        }
        val upper = withoutPrefix.uppercase(Locale.US)
        val normalizedTokens = tokenDelimiter
            .split(upper)
            .map { nonAlphaNumeric.replace(it, "") }
            .filter { it.isNotEmpty() }
        if (normalizedTokens.isEmpty()) {
            return upper.replace(nonAlphaNumeric, "")
        }
        val filtered = normalizedTokens.filterNot { sideTokens.contains(it) }
        val candidate = when {
            filtered.size >= 2 && filtered[0].startsWith("G") && filtered[1].firstOrNull()?.isDigit() == true ->
                "${filtered[0]}_${filtered[1]}"
            else -> filtered.firstOrNull { token ->
                token.firstOrNull()?.let { it.isDigit() || it.isLetter() } == true
            } ?: filtered.firstOrNull()
        }
        return (candidate ?: normalizedTokens.first()).uppercase(Locale.US)
    }

    fun inferLensSide(name: String): Lens {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Lens.UNKNOWN
        }
        val upper = trimmed.uppercase(Locale.US)
        if (LEFT_PATTERN.containsMatchIn(upper)) {
            return Lens.LEFT
        }
        if (RIGHT_PATTERN.containsMatchIn(upper)) {
            return Lens.RIGHT
        }
        return Lens.UNKNOWN
    }

    private const val SIDE_BOUNDARY_CHARS = "_\\-\\s()\\[\\]"
    private val LEFT_PATTERN = Regex("(?i)(?:^|[${SIDE_BOUNDARY_CHARS}])L(?:EFT)?(?=$|[${SIDE_BOUNDARY_CHARS}])")
    private val RIGHT_PATTERN = Regex("(?i)(?:^|[${SIDE_BOUNDARY_CHARS}])R(?:IGHT)?(?=$|[${SIDE_BOUNDARY_CHARS}])")
}
