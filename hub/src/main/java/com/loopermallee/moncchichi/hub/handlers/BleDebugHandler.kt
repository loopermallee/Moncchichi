package com.loopermallee.moncchichi.hub.handlers

import com.loopermallee.moncchichi.hub.tools.BleTool

object BleDebugHandler {
    suspend fun run(
        text: String,
        ble: BleTool,
        _onAssistant: (String) -> Unit,
        log: (String) -> Unit
    ) {
        val cmd = text.removePrefix("ble ").removePrefix("g1 ").trim()
        val resp = ble.send(cmd)
        val summary = "[BLE DEBUG] '$cmd' → $resp"
        log(summary)
    }
}
