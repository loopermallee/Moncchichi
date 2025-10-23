package com.loopermallee.moncchichi.hub.assistant

import com.loopermallee.moncchichi.core.utils.ConsoleInterpreter
import com.loopermallee.moncchichi.hub.data.diagnostics.DiagnosticRepository
import com.loopermallee.moncchichi.hub.viewmodel.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object OfflineAssistant {

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

    suspend fun generateResponse(
        prompt: String,
        state: AppState,
        diagnostics: DiagnosticRepository,
        pendingQueries: Int,
    ): String = withContext(Dispatchers.Default) {
        val snapshot = diagnostics.snapshot(state)
        val topic = DiagnosticTopic.fromPrompt(prompt)
        val insight = snapshot.insights

        buildString {
            append("⚡ I'm offline right now but I'm still tracking things locally.\n")
            if (prompt.isNotBlank()) {
                append("→ I saved your question: \"")
                append(prompt.trim())
                append("\"\n")
            } else {
                append("→ I'll respond fully when I'm back online.\n")
            }
            if (pendingQueries > 0) {
                append("→ ${pendingQueries} pending request${if (pendingQueries > 1) "s" else ""} queued for replay.\n")
            }
            append('\n')
            append("🔍 Here's what I can see right now:\n")

            fun appendBullet(label: String, value: String) {
                append("• ")
                append(label)
                append(':')
                append(' ')
                append(value)
                append('\n')
            }

            val device = snapshot.device
            val networkLabel = when (topic) {
                DiagnosticTopic.INTERNET, DiagnosticTopic.CONNECTION -> "Network"
                else -> "Network"
            }
            appendBullet(networkLabel, networkSummary(snapshot.network, insight.network))
            appendBullet("Bluetooth", insight.ble.readable("Bluetooth link"))
            appendBullet("API key", insight.api.readable("API key"))
            appendBullet("LLM", insight.llm.readable("Assistant"))

            when (topic) {
                DiagnosticTopic.BATTERY, DiagnosticTopic.STATUS, DiagnosticTopic.GENERAL -> {
                    val glasses = device.glassesBattery?.let { "$it%" } ?: "unknown"
                    val case = device.caseBattery?.let { "$it%" } ?: "unknown"
                    appendBullet("Glasses battery", glasses)
                    appendBullet("Case battery", case)
                    snapshot.phoneBattery?.let { appendBullet("Phone battery", "$it%") }
                }
                DiagnosticTopic.FIRMWARE -> {
                    appendBullet("Firmware", device.name?.let { "Awaiting firmware data for $it" } ?: "No firmware info yet")
                }
                else -> Unit
            }

            val notes = insight.notes
            if (notes.isNotEmpty()) {
                append('\n')
                append("🗒 Recent console notes:\n")
                notes.forEach { append("• $it\n") }
            }

            if (topic == DiagnosticTopic.API_KEY && insight.api.state != ConsoleInterpreter.HealthState.GOOD) {
                append('\n')
                append("Tip: open Settings → Edit Key to update your OpenAI key.")
            } else if (topic == DiagnosticTopic.CONNECTION && snapshot.network.isConnected.not()) {
                append('\n')
                append("Tip: toggle Wi‑Fi or cellular data, then tap Retry once we're back online.")
            }
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
}
