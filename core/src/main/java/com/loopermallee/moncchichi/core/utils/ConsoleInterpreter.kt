package com.loopermallee.moncchichi.core.utils

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

    fun summarize(lines: List<String>): List<String> = analyze(lines).notes
}
