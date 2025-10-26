package com.loopermallee.moncchichi.hub.assistant

import com.loopermallee.moncchichi.hub.console.ConsoleInterpreter
import com.loopermallee.moncchichi.hub.data.diagnostics.DiagnosticRepository
import com.loopermallee.moncchichi.hub.viewmodel.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object OfflineAssistant {

    private var offlineIntroShown = false

    private enum class DiagnosticTopic {
        CONNECTION,
        INTERNET,
        BLUETOOTH,
        BATTERY,
        FIRMWARE,
        API_KEY,
        STATUS,
        GENERAL;

        companion object {
            fun fromPrompt(prompt: String): DiagnosticTopic {
                val normalized = prompt.lowercase(Locale.getDefault())
                return when {
                    normalized.containsAny("internet", "wifi", "network", "web") -> INTERNET
                    normalized.containsAny("bluetooth", "ble", "pair") -> BLUETOOTH
                    normalized.containsAny("battery", "charge", "power") -> BATTERY
                    normalized.containsAny("firmware", "version", "update") -> FIRMWARE
                    normalized.containsAny("api key", "apikey", "token") -> API_KEY
                    normalized.containsAny("status", "diagnostic", "state", "health") -> STATUS
                    normalized.containsAny("connect", "connection", "offline", "online") -> CONNECTION
                    else -> GENERAL
                }
            }

            private fun String.containsAny(vararg keywords: String): Boolean =
                keywords.any { contains(it, ignoreCase = true) }
        }
    }

    private val directResponseTopics = setOf(
        DiagnosticTopic.BATTERY,
        DiagnosticTopic.STATUS,
        DiagnosticTopic.GENERAL,
        DiagnosticTopic.CONNECTION,
    )

    suspend fun generateResponse(
        prompt: String,
        state: AppState,
        diagnostics: DiagnosticRepository,
        pendingQueries: Int,
    ): String = withContext(Dispatchers.Default) {
        val snapshot = diagnostics.snapshot(state)
        val topic = DiagnosticTopic.fromPrompt(prompt)
        val insight = snapshot.insights

        if (!state.assistant.isOffline) {
            offlineIntroShown = false
        }

        val skipIntro = topic in directResponseTopics
        val savedSentence = prompt
            .takeIf { it.isNotBlank() }
            ?.trim()
            ?.replace("\n", " ")
            ?.takeIf { it.isNotEmpty() }
            ?.let { "I saved \u201c$it\u201d so I can give you a full answer once I'm back online." }

        val queueSentence = when (pendingQueries) {
            0 -> null
            1 -> "I have one request queued to send as soon as we're connected again."
            else -> "I have $pendingQueries requests queued to send as soon as we're connected again."
        }

        val friendlyIntro = "While I’m offline, here’s what I can tell you…"
        val shouldShowSyncReminder = !skipIntro && !offlineIntroShown

        val batterySentence = joinClauses(
            prefix = "Your ",
            parts = buildList {
                snapshot.device.glassesBattery?.let { add("glasses battery is at $it%") }
                snapshot.device.caseBattery?.let { add("case battery shows $it%") }
            }
        )
        val networkSentence = networkSummary(snapshot.network, insight.network)
        val bluetoothSentence = friendlyStatus("Bluetooth link", insight.ble)
        val apiSentence = friendlyStatus("API key", insight.api)
        val assistantSentence = friendlyStatus("Assistant service", insight.llm)

        val deviceSentences = when (topic) {
            DiagnosticTopic.BATTERY, DiagnosticTopic.STATUS, DiagnosticTopic.GENERAL -> buildList {
                snapshot.device.firmwareVersion?.let { add("Firmware version reads $it.") }
                snapshot.device.signalRssi?.let { add("Signal strength is ${it} dBm.") }
                snapshot.device.connectionState?.let { add("Device state reports $it.") }
                snapshot.phoneBattery?.let { add("Your phone battery is at $it%.") }
                if (snapshot.isPowerSaver) add("Phone power saver mode is on.")
            }
            DiagnosticTopic.FIRMWARE -> listOf(
                snapshot.device.firmwareVersion?.let { "Current firmware version is $it." }
                    ?: "I don't have firmware details yet."
            )
            else -> emptyList()
        }

        val telemetryHeadline = if (topic in directResponseTopics) {
            val parts = buildList {
                snapshot.device.glassesBattery?.let { add("Battery ${it}%") }
                snapshot.device.caseBattery?.let { add("Case ${it}%") }
                snapshot.device.firmwareVersion?.let { add("Firmware ${it}") }
                snapshot.device.signalRssi?.let { add("Signal ${it} dBm") }
                snapshot.device.connectionState?.let { add("State ${it}") }
            }
            if (parts.isNotEmpty()) "Assistant 🟣 (Device Only): ${parts.joinToString(", ")}" else null
        } else null

        val notesSentence = combineNotes(insight.notes)

        val tipSentence = when {
            topic == DiagnosticTopic.API_KEY && insight.api.state != ConsoleInterpreter.HealthState.GOOD ->
                "💡 Update your OpenAI key from Settings → Edit Key."
            topic == DiagnosticTopic.CONNECTION && !snapshot.network.isConnected ->
                "💡 Toggle Wi‑Fi or cellular data, then tap Retry once we're back online."
            topic == DiagnosticTopic.INTERNET && insight.network.state != ConsoleInterpreter.HealthState.GOOD ->
                "💡 Check your router or move closer to the access point."
            else -> null
        }

        if (shouldShowSyncReminder) {
            offlineIntroShown = true
        }

        val bodyLines = buildList {
            add(friendlyIntro)
            if (shouldShowSyncReminder) {
                add("I'll sync any detailed answers as soon as I'm back online.")
            }
            batterySentence?.let(::add)
            add(networkSentence)
            add(bluetoothSentence)
            add(apiSentence)
            add(assistantSentence)
            savedSentence?.let(::add)
            queueSentence?.let(::add)
            addAll(deviceSentences)
            notesSentence?.let(::add)
            tipSentence?.let(::add)
        }.filter { it.isNotBlank() }

        buildString {
            if (telemetryHeadline != null) {
                append(telemetryHeadline)
                if (bodyLines.isNotEmpty()) {
                    append('\n')
                }
            }
            append(bodyLines.joinToString(separator = "\n"))
        }.trimEnd()

    }

    private fun networkSummary(
        report: DiagnosticRepository.NetworkReport,
        channel: ConsoleInterpreter.ChannelStatus,
    ): String {
        val label = if (report.isConnected) {
            report.transports.joinToString(" + ").ifBlank { "network" }
        } else {
            "network"
        }
        val detail = trimDetail(channel.detail)
        return when {
            !report.isConnected -> "It looks like your $label connection is offline right now."
            channel.state == ConsoleInterpreter.HealthState.GOOD -> {
                val extras = networkExtras(report)
                "Your $label connection looks okay from the logs$extras."
            }
            channel.state == ConsoleInterpreter.HealthState.DEGRADED -> detail?.let {
                "Your $label connection might be spotty — $it."
            } ?: "Your $label connection might be spotty."
            channel.state == ConsoleInterpreter.HealthState.DOWN -> detail?.let {
                "Your $label connection appears offline — $it."
            } ?: "Your $label connection appears offline."
            else -> detail?.let {
                "I'm not getting clear info about your $label connection — $it."
            } ?: "I'm not getting clear info about your $label connection yet."
        }
    }

    private fun friendlyStatus(label: String, status: ConsoleInterpreter.ChannelStatus): String {
        val normalized = label.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
        }
        val detail = trimDetail(status.detail)
        return when (status.state) {
            ConsoleInterpreter.HealthState.GOOD -> detail?.let {
                "$normalized looks good — $it."
            } ?: "$normalized looks good from what I can see."
            ConsoleInterpreter.HealthState.DEGRADED -> detail?.let {
                "$normalized might be having trouble — $it."
            } ?: "$normalized might be having trouble."
            ConsoleInterpreter.HealthState.DOWN -> detail?.let {
                "$normalized appears offline — $it."
            } ?: "$normalized appears offline."
            ConsoleInterpreter.HealthState.UNKNOWN -> detail?.let {
                "I'm not getting clear info about $normalized — $it."
            } ?: "I'm not getting clear info about $normalized yet."
        }
    }

    private fun joinClauses(prefix: String, parts: List<String>): String? {
        if (parts.isEmpty()) return null
        val body = when (parts.size) {
            1 -> parts.first()
            2 -> parts.joinToString(" and ")
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        }
        val sentence = prefix + body
        return if (sentence.endsWith('.') || sentence.endsWith('!') || sentence.endsWith('?')) {
            sentence
        } else {
            "$sentence."
        }
    }

    private fun combineNotes(notes: List<String>): String? {
        if (notes.isEmpty()) return null
        val cleaned = notes.map { it.trim().trimEnd('.', '!', '?') }
        return "From the logs: ${cleaned.joinToString("; ")}."
    }

    private fun networkExtras(report: DiagnosticRepository.NetworkReport): String {
        val flags = buildList {
            if (!report.hasValidatedInternet) add("no internet access yet")
            if (report.isMetered) add("metered")
        }
        if (flags.isEmpty()) return ""
        val descriptor = when (flags.size) {
            1 -> flags.first()
            2 -> "${flags[0]} and ${flags[1]}"
            else -> flags.dropLast(1).joinToString(", ") + ", and ${flags.last()}"
        }
        return " ($descriptor)"
    }

    private fun trimDetail(detail: String?): String? = detail?.trim()?.trimEnd('.', '!', '?')

    fun resetSession() {
        offlineIntroShown = false
    }
}
