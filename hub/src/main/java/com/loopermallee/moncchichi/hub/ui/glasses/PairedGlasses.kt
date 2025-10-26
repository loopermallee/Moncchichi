package com.loopermallee.moncchichi.hub.ui.glasses

import com.loopermallee.moncchichi.client.G1ServiceCommon.Glasses
import com.loopermallee.moncchichi.client.G1ServiceCommon.GlassesStatus
import java.util.Locale
import kotlin.math.min

enum class LensSide { LEFT, RIGHT, UNKNOWN }

data class PairedGlasses(
    val pairId: String,
    val pairName: String,
    val left: Glasses?,
    val right: Glasses?,
    val leftSide: LensSide,
    val rightSide: LensSide,
)

val PairedGlasses.lensRecords: List<Glasses>
    get() = listOfNotNull(left, right)

val PairedGlasses.lensIds: List<String>
    get() = lensRecords.mapNotNull { it.id?.takeIf(String::isNotBlank) }

val PairedGlasses.connectedLensIds: List<String>
    get() = lensRecords
        .filter { it.status == GlassesStatus.CONNECTED }
        .mapNotNull { it.id?.takeIf(String::isNotBlank) }

val PairedGlasses.isAnyConnected: Boolean
    get() = lensRecords.any { it.status == GlassesStatus.CONNECTED }

val PairedGlasses.isFullyConnected: Boolean
    get() = lensRecords.isNotEmpty() && lensRecords.all { it.status == GlassesStatus.CONNECTED }

val PairedGlasses.isAnyInProgress: Boolean
    get() = lensRecords.any {
        when (it.status) {
            GlassesStatus.CONNECTING,
            GlassesStatus.DISCONNECTING,
            GlassesStatus.UNINITIALIZED -> true
            else -> false
        }
    }

val PairedGlasses.hasError: Boolean
    get() = lensRecords.any { it.status == GlassesStatus.ERROR }

fun List<Glasses>.toPairedGlasses(): List<PairedGlasses> {
    if (isEmpty()) return emptyList()

    val groups = linkedMapOf<String, MutableList<Glasses>>()
    for (glasses in this) {
        val key = glasses.pairGroupingKey()
        groups.getOrPut(key) { mutableListOf() }.add(glasses)
    }

    val usedIds = mutableSetOf<String>()
    val result = mutableListOf<PairedGlasses>()

    for (group in groups.values) {
        val pair = group.toPairedGlassesInternal()
        val uniqueId = ensureUniquePairId(pair.pairId, usedIds)
        result += if (uniqueId == pair.pairId) pair else pair.copy(pairId = uniqueId)
    }

    return result
}

private data class LensCandidate(
    val glasses: Glasses,
    val side: LensSide,
)

private fun List<Glasses>.toPairedGlassesInternal(): PairedGlasses {
    val candidates = map { LensCandidate(it, it.detectSide()) }
    val leftCandidate = candidates.firstOrNull { it.side == LensSide.LEFT }
    val rightCandidate = candidates.firstOrNull { it.side == LensSide.RIGHT }

    val remaining = candidates
        .filterNot { it == leftCandidate || it == rightCandidate }
        .toMutableList()

    var left = leftCandidate
    var right = rightCandidate

    if (left == null && remaining.isNotEmpty()) {
        left = remaining.removeAt(0)
    }
    if (right == null && remaining.isNotEmpty()) {
        right = remaining.removeAt(0)
    }

    val pairName = computePairName()
    val rawPairId = computePairId(pairName)

    return PairedGlasses(
        pairId = rawPairId,
        pairName = pairName,
        left = left?.glasses,
        right = right?.glasses,
        leftSide = left?.side ?: LensSide.LEFT,
        rightSide = right?.side ?: LensSide.RIGHT,
    )
}

private fun ensureUniquePairId(candidate: String, used: MutableSet<String>): String {
    val base = candidate.ifBlank { "pair" }
    var value = base
    var suffix = 2
    while (!used.add(value)) {
        value = "$base-$suffix"
        suffix++
    }
    return value
}

private fun Glasses.detectSide(): LensSide {
    val lowerId = id?.lowercase(Locale.US)
    val lowerName = name?.lowercase(Locale.US)
    return when {
        lowerId?.startsWith("left") == true || lowerName?.startsWith("left") == true -> LensSide.LEFT
        lowerId?.startsWith("right") == true || lowerName?.startsWith("right") == true -> LensSide.RIGHT
        else -> LensSide.UNKNOWN
    }
}

private fun List<Glasses>.computePairName(): String {
    if (isEmpty()) return "G1 Headset"
    val nameCandidate = asSequence()
        .mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .map(::stripLensSidePrefix)
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() }

    val idCandidate = if (nameCandidate.isNullOrEmpty()) {
        asSequence()
            .mapNotNull { it.id?.takeIf(String::isNotBlank) }
            .map(::stripLensSidePrefix)
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
    } else {
        null
    }

    val base = nameCandidate ?: idCandidate ?: "G1 Headset"
    val words = base
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }

    if (words.isEmpty()) return "G1 Headset"

    return words.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}

private val whitespaceRegex = Regex("\\s+")
private val nonIdCharactersRegex = Regex("[^a-z0-9_-]+")
private val repeatedHyphenRegex = Regex("-+")

private fun List<Glasses>.computePairId(pairName: String): String {
    val fromId = asSequence()
        .mapNotNull { it.id?.takeIf(String::isNotBlank) }
        .map(::sanitizePairId)
        .firstOrNull { it.isNotEmpty() }

    if (!fromId.isNullOrEmpty()) return fromId

    val fromName = asSequence()
        .mapNotNull { it.name?.takeIf(String::isNotBlank) }
        .map(::sanitizePairId)
        .firstOrNull { it.isNotEmpty() }

    if (!fromName.isNullOrEmpty()) return fromName

    return sanitizePairId(pairName)
}

private fun sanitizePairId(value: String): String {
    val stripped = stripLensSidePrefix(value).trim()
    if (stripped.isEmpty()) return ""

    val normalized = stripped.lowercase(Locale.US)
    val withSpaces = whitespaceRegex.replace(normalized, "-")
    val collapsed = nonIdCharactersRegex.replace(withSpaces, "-")
    val compacted = repeatedHyphenRegex.replace(collapsed, "-")
    return compacted.trim('-')
}

private val sidePrefixRegex = Regex("^(left|right)([-_\\s]+)?", RegexOption.IGNORE_CASE)

internal fun stripLensSidePrefix(value: String): String {
    val trimmed = value.trim()
    val match = sidePrefixRegex.find(trimmed)
    if (match != null && match.range.first == 0) {
        val endIndex = min(trimmed.length, match.range.last + 1)
        val remainder = trimmed.substring(endIndex)
        return remainder.trimStart('-', '_', ' ', '\t')
    }
    return trimmed
}

private fun Glasses.pairGroupingKey(): String {
    val candidates = listOfNotNull(
        id?.takeIf(String::isNotBlank)?.let(::stripLensSidePrefix)?.lowercase(Locale.US),
        name?.takeIf(String::isNotBlank)?.let(::stripLensSidePrefix)?.lowercase(Locale.US),
        id?.takeIf(String::isNotBlank)?.lowercase(Locale.US),
        name?.takeIf(String::isNotBlank)?.lowercase(Locale.US),
    )
    return candidates.firstOrNull { it.isNotEmpty() } ?: hashCode().toString()
}
