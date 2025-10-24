package com.loopermallee.moncchichi.hub.console

import java.util.Locale

/**
 * Parses hub console logs and produces human-friendly diagnostics for the assistant UI.
 */
object ConsoleInterpreter {

    enum class HealthState { GOOD, DEGRADED, DOWN, UNKNOWN }

    data class ChannelStatus(
        val state: HealthState,
        val detail: String? = null,
    ) {
        fun readable(label: String): String = when (state) {
            HealthState.GOOD -> "$label looks healthy."
            HealthState.DEGRADED -> detail?.let { "$label degraded – $it" } ?: "$label degraded."
            HealthState.DOWN -> detail?.let { "$label unavailable – $it" } ?: "$label unavailable."
            HealthState.UNKNOWN -> detail?.let { "$label status unknown – $it" } ?: "$label status unknown."
        }
    }

    data class Insights(
        val ble: ChannelStatus,
        val network: ChannelStatus,
        val api: ChannelStatus,
        val llm: ChannelStatus,
        val notes: List<String>,
    )

    fun analyze(lines: List<String>): Insights {
        val recent = lines.takeLast(60)
        val notes = mutableListOf<String>()

        var bleStatus = ChannelStatus(HealthState.UNKNOWN)
        var networkStatus = ChannelStatus(HealthState.UNKNOWN)
        var apiStatus = ChannelStatus(HealthState.UNKNOWN)
        var llmStatus = ChannelStatus(HealthState.UNKNOWN)

        recent.lastOrNull { it.contains("firmware=", ignoreCase = true) }?.let { line ->
            val summary = line.substringAfter("firmware=").trim()
            if (summary.isNotEmpty()) {
                notes += "Firmware $summary"
            }
        }
        recent.lastOrNull {
            it.contains("battery=", ignoreCase = true) && it.contains("case=", ignoreCase = true)
        }?.let { line ->
            val detail = line.substringAfter("[DIAG]", "").trim()
            if (detail.isNotEmpty()) {
                notes += "Battery update • $detail"
            }
        }

        val hasBleAck = recent.any { it.contains("ACK", ignoreCase = true) && it.contains("C9", ignoreCase = true) }
        val hasHeartbeat = recent.any { it.contains("Keepalive", ignoreCase = true) && it.contains("❤️") }
        val hasReconnect = recent.any { it.contains("Reconnecting", ignoreCase = true) }
        val ackTimeout = recent.any { it.contains("ACK timeout", ignoreCase = true) }
        val writeFailure = recent.any { it.contains("write", ignoreCase = true) && it.contains("failed", ignoreCase = true) }
        val connectOk = recent.any { it.contains("Connected", ignoreCase = true) && it.contains("BLE", ignoreCase = true) }

        when {
            ackTimeout -> {
                bleStatus = ChannelStatus(HealthState.DEGRADED, "ACK timeout detected")
                notes += "BLE retries triggered due to missing ACKs."
            }
            writeFailure -> {
                bleStatus = ChannelStatus(HealthState.DEGRADED, "Command write failed")
                notes += "At least one BLE command failed to send."
            }
            hasReconnect -> {
                bleStatus = ChannelStatus(HealthState.DEGRADED, "Reconnecting to glasses")
                notes += "BLE link recently reconnected."
            }
            connectOk || hasBleAck || hasHeartbeat -> {
                bleStatus = ChannelStatus(HealthState.GOOD, "Link established")
            }
            recent.none { it.contains("BLE", ignoreCase = true) } -> {
                bleStatus = ChannelStatus(HealthState.UNKNOWN, "No recent BLE logs")
                notes += "No BLE traffic observed in the latest console entries."
            }
        }

        when {
            recent.any { it.contains("No network connectivity", ignoreCase = true) } -> {
                networkStatus = ChannelStatus(HealthState.DOWN, "Network unreachable")
                notes += "Network unavailable."
            }
            recent.any { it.contains("timeout", ignoreCase = true) && it.contains("LLM", ignoreCase = true) } -> {
                networkStatus = ChannelStatus(HealthState.DEGRADED, "LLM timeout")
                llmStatus = ChannelStatus(HealthState.DEGRADED, "Timed out waiting for LLM")
                notes += "LLM service timeout detected."
            }
            recent.any { it.contains("Connected", ignoreCase = true) && it.contains("network", ignoreCase = true) } -> {
                networkStatus = ChannelStatus(HealthState.GOOD, "Connection confirmed")
            }
        }

        when {
            recent.any { it.contains("Unauthorized", ignoreCase = true) || it.contains("401", ignoreCase = true) } -> {
                apiStatus = ChannelStatus(HealthState.DOWN, "Invalid API key")
                notes += "Invalid API key detected."
            }
            recent.any { it.contains("429", ignoreCase = true) } -> {
                apiStatus = ChannelStatus(HealthState.DEGRADED, "Rate limited")
                notes += "OpenAI rate limit encountered."
            }
        }

        if (llmStatus.state == HealthState.UNKNOWN && apiStatus.state == HealthState.UNKNOWN) {
            llmStatus = ChannelStatus(HealthState.GOOD, detail = "Operational")
            apiStatus = ChannelStatus(HealthState.GOOD, detail = "Requests succeeding")
        }
        if (networkStatus.state == HealthState.UNKNOWN) {
            networkStatus = ChannelStatus(HealthState.UNKNOWN, detail = "No network log signals")
        }
        if (bleStatus.state == HealthState.UNKNOWN) {
            bleStatus = ChannelStatus(HealthState.UNKNOWN, detail = "No recent BLE entries")
        }

        return Insights(
            ble = bleStatus,
            network = networkStatus,
            api = apiStatus,
            llm = llmStatus,
            notes = notes.distinct(),
        )
    }

    fun quickSummary(
        glassesBattery: Int?,
        caseBattery: Int?,
        networkLabel: String,
        network: ChannelStatus,
        api: ChannelStatus,
        llm: ChannelStatus,
    ): String {
        val batteryText = "🔋 Glasses ${glassesBattery?.let { "$it %" } ?: "—"}"
        val caseText = caseBattery?.let { "💼 Case $it %" }
        val normalizedLabel = networkLabel.ifBlank { "Network" }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val networkDescriptor = when (network.state) {
            HealthState.GOOD -> network.detail ?: "Good"
            HealthState.DEGRADED -> network.detail ?: "Check"
            HealthState.DOWN -> network.detail ?: "Offline"
            HealthState.UNKNOWN -> network.detail ?: "Unknown"
        }
        val networkText = "📶 $normalizedLabel $networkDescriptor"
        val apiText = "⚙️ API ${statusWord(api, "OK")}".trim()
        val llmText = "🧠 LLM ${statusWord(llm, "Ready")}".trim()

        return listOfNotNull(batteryText, caseText, networkText, apiText, llmText)
            .joinToString(separator = "  ")
    }

    private fun statusWord(status: ChannelStatus, whenGood: String): String = when (status.state) {
        HealthState.GOOD -> status.detail ?: whenGood
        HealthState.DEGRADED -> status.detail ?: "Check"
        HealthState.DOWN -> status.detail ?: "Offline"
        HealthState.UNKNOWN -> status.detail ?: "Unknown"
    }

    fun summarize(lines: List<String>): List<String> = analyze(lines).notes
}
