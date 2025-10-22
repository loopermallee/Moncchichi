package com.loopermallee.moncchichi.hub.router

enum class Route {
    DEVICE_STATUS,
    COMMAND_CONTROL,
    LIVE_FEED,
    SUBTITLES,
    AI_ASSISTANT,
    BLE_DEBUG,
    TRANSIT,
    UNKNOWN
}

class IntentRouter {
    fun route(text: String): Route {
        val t = text.lowercase()

        if (listOf("status", "battery", "device info", "connection").any { t.contains(it) }) {
            return Route.DEVICE_STATUS
        }

        if (
            listOf(
                "turn on",
                "turn off",
                "enable",
                "disable",
                "start",
                "stop",
                "brightness",
                "right lens",
                "left lens"
            ).any { t.contains(it) }
        ) {
            return Route.COMMAND_CONTROL
        }

        if (listOf("live", "stream", "vitals", "feed", "monitor").any { t.contains(it) }) {
            return Route.LIVE_FEED
        }

        if (listOf("subtitle", "transcribe", "caption", "speech to text").any { t.contains(it) }) {
            return Route.SUBTITLES
        }

        if (listOf("bus", "train", "arrival", "arrive", "station", "mrt", "transit").any { t.contains(it) }) {
            return Route.TRANSIT
        }

        if (t.startsWith("ble ") || t.startsWith("g1 ")) {
            return Route.BLE_DEBUG
        }

        if (listOf("what", "how", "why", "who", "where", "summarize", "explain").any { t.startsWith(it) }) {
            return Route.AI_ASSISTANT
        }

        return Route.UNKNOWN
    }
}
