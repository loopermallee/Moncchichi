package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.DisplayTool

object TransitHandler {
    suspend fun run(
        text: String,
        display: DisplayTool,
        onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val response = "Transit lookup coming soon for: ${text.trim()}"
        display.showLines(listOf("Transit", response))
        onAssistant(response)
        log("[Transit] $response")
    }
}
