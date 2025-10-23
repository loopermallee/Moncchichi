package com.loopermallee.moncchichi.core.utils

import java.util.Locale

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
        val recent = lines.takeLast(40)
        val notes = mutableListOf<String>()

        var bleStatus = ChannelStatus(HealthState.UNKNOWN)
        var networkStatus = ChannelStatus(HealthState.UNKNOWN)
        var apiStatus = ChannelStatus(HealthState.UNKNOWN)
        var llmStatus = ChannelStatus(HealthState.UNKNOWN)

        if (recent.any { it.contains("BLE", ignoreCase = true) && it.contains("Connected", ignoreCase = true) }) {
            bleStatus = ChannelStatus(HealthState.GOOD, detail = "Link established")
        }
        when {
            recent.any { it.contains("Keepalive failed", ignoreCase = true) } -> {
                bleStatus = ChannelStatus(HealthState.DOWN, "Keepalive failed recently")
                notes += "BLE connection lost recently."
            }
            recent.any { it.contains("Write failed", ignoreCase = true) } -> {
                bleStatus = ChannelStatus(HealthState.DEGRADED, "Command write failed")
                notes += "A BLE command failed to send."
            }
            recent.none { it.contains("BLE", ignoreCase = true) } -> {
                notes += "No BLE data detected recently."
                if (bleStatus.state == HealthState.UNKNOWN) {
                    bleStatus = ChannelStatus(HealthState.UNKNOWN, "No telemetry in logs")
                }
            }
        }
        if (recent.any { it.contains("battery=0", ignoreCase = true) || it.contains("stub", ignoreCase = true) }) {
            notes += "Battery telemetry appears stubbed."
        }
        if (recent.any { it.contains("Disconnected", ignoreCase = true) }) {
            bleStatus = ChannelStatus(HealthState.DEGRADED, "Device disconnected")
            notes += "Device disconnected."
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
            recent.any { it.contains("OFFLINE FALLBACK ENABLED", ignoreCase = true) } -> {
                llmStatus = ChannelStatus(HealthState.DEGRADED, "Offline fallback active")
                notes += "Assistant is running in offline fallback mode."
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
            notes = notes.distinct()
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
        val labelBase = networkLabel.ifBlank { "Network" }
        val normalizedLabel = labelBase.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val networkDescriptor = when (network.state) {
            HealthState.GOOD -> network.detail ?: "Good"
            HealthState.DEGRADED -> network.detail ?: "Check"
            HealthState.DOWN -> network.detail ?: "Offline"
            HealthState.UNKNOWN -> network.detail ?: "Unknown"
        }
        val networkText = buildString {
            append("📶 ")
            append(listOf(normalizedLabel, networkDescriptor).distinct().joinToString(" "))
        }
        val apiText = "⚙️ API ${statusWord(api, "OK")}"
        val llmText = "🧠 LLM ${statusWord(llm, "Ready")}"

        return listOfNotNull(batteryText, caseText, networkText, apiText, llmText)
            .joinToString("  ")
    }

    private fun statusWord(status: ChannelStatus, whenGood: String): String = when (status.state) {
        HealthState.GOOD -> status.detail ?: whenGood
        HealthState.DEGRADED -> status.detail ?: "Check"
        HealthState.DOWN -> status.detail ?: "Offline"
        HealthState.UNKNOWN -> status.detail ?: "Unknown"
    }

    fun summarize(lines: List<String>): List<String> = analyze(lines).notes
}
