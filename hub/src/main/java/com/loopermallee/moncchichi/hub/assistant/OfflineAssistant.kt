package com.loopermallee.moncchichi.hub.assistant

import com.loopermallee.moncchichi.core.utils.ConsoleInterpreter
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

        val summary = ConsoleInterpreter.quickSummary(
            snapshot.device.glassesBattery,
            snapshot.device.caseBattery,
            snapshot.network.label,
            insight.network,
            insight.api,
            insight.llm,
        )

        val skipIntro = topic in directResponseTopics
        val savedLine = prompt.takeIf { it.isNotBlank() }?.trim()?.let { "💾 Saved: \"$it\"" }
        val queueLine = if (pendingQueries > 0) {
            val suffix = if (pendingQueries > 1) "s" else ""
            "🗂 $pendingQueries queued request$suffix"
        } else {
            null
        }

        val detailLines = mutableListOf<String>()
        detailLines += "📡 ${insight.ble.readable("Bluetooth link")}"
        if (!snapshot.network.isConnected || insight.network.state != ConsoleInterpreter.HealthState.GOOD) {
            detailLines += "🌐 ${networkSummary(snapshot.network, insight.network)}"
        }
        if (insight.api.state != ConsoleInterpreter.HealthState.GOOD) {
            detailLines += "⚙️ ${insight.api.readable("API key")}"
        }
        if (insight.llm.state != ConsoleInterpreter.HealthState.GOOD) {
            detailLines += "🧠 ${insight.llm.readable("Assistant")}"
        }

        val deviceLines = when (topic) {
            DiagnosticTopic.BATTERY, DiagnosticTopic.STATUS, DiagnosticTopic.GENERAL -> buildList {
                add("🔋 Glasses: ${snapshot.device.glassesBattery?.let { "$it %" } ?: "unknown"}")
                add("💼 Case: ${snapshot.device.caseBattery?.let { "$it %" } ?: "unknown"}")
                add("🛠 Firmware: ${snapshot.device.firmwareVersion ?: "unknown"}")
                add("📶 Signal: ${snapshot.device.signalRssi?.let { "${it} dBm" } ?: "unknown"}")
                snapshot.phoneBattery?.let { add("📱 Phone: $it %") }
                if (snapshot.isPowerSaver) add("⚡ Phone power saver is ON")
            }
            DiagnosticTopic.FIRMWARE -> listOf(
                "🛠 Firmware: ${snapshot.device.firmwareVersion ?: "No firmware info yet"}"
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

        val notesLine = insight.notes.takeIf { it.isNotEmpty() }?.joinToString(" • ")?.let { "🗒 $it" }

        val tipLine = when {
            topic == DiagnosticTopic.API_KEY && insight.api.state != ConsoleInterpreter.HealthState.GOOD ->
                "💡 Update your OpenAI key from Settings → Edit Key."
            topic == DiagnosticTopic.CONNECTION && !snapshot.network.isConnected ->
                "💡 Toggle Wi‑Fi or cellular data, then tap Retry once we're back online."
            topic == DiagnosticTopic.INTERNET && insight.network.state != ConsoleInterpreter.HealthState.GOOD ->
                "💡 Check your router or move closer to the access point."
            else -> null
        }

        buildString {
            telemetryHeadline?.let { append(it).append('\n') }
            append(summary)
            if (!skipIntro && !offlineIntroShown) {
                append("\n⚡ Offline fallback is active – I'll sync replies once I'm online.")
                offlineIntroShown = true
            }
            savedLine?.let { append("\n").append(it) }
            queueLine?.let { append("\n").append(it) }
            deviceLines.forEach { append("\n").append(it) }
            detailLines.distinct().forEach { append("\n").append(it) }
            notesLine?.let { append("\n").append(it) }
            tipLine?.let { append("\n").append(it) }
        }.trimEnd()
    }

    private fun networkSummary(
        report: DiagnosticRepository.NetworkReport,
        channel: ConsoleInterpreter.ChannelStatus,
    ): String {
        return if (!report.isConnected) {
            channel.readable("Network")
        } else {
            buildString {
                append(report.label)
                if (channel.state == ConsoleInterpreter.HealthState.DEGRADED) {
                    append(" – ")
                    append(channel.detail ?: "degraded")
                }
                if (channel.state == ConsoleInterpreter.HealthState.DOWN) {
                    append(" – offline per logs")
                }
            }
        }
    }

    fun resetSession() {
        offlineIntroShown = false
    }
}
