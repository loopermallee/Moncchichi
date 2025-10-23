package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import kotlinx.coroutines.delay
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.ScanResult

class BleToolImpl(@Suppress("UNUSED_PARAMETER") context: Context) : BleTool {
    private var connectedId: String? = null

    override suspend fun scanDevices(onFound: (ScanResult) -> Unit) {
        delay(250)
        val result = ScanResult(id = "demo-device", name = "Moncchichi G1", rssi = -55)
        onFound(result)
    }

    override suspend fun stopScan() {
        // No-op for placeholder implementation
    }

    override suspend fun connect(deviceId: String): Boolean {
        connectedId = deviceId
        return true
    }

    override suspend fun disconnect() {
        connectedId = null
    }

    override suspend fun send(command: String): String {
        return if (connectedId != null) {
            "ACK:$command"
        } else {
            "NOT_CONNECTED"
        }
    }

    override suspend fun battery(): Int? {
        return if (connectedId != null) 87 else null
    }

    override suspend fun caseBattery(): Int? {
        return if (connectedId != null) 62 else null
    }

    override suspend fun firmware(): String? {
        return if (connectedId != null) "v1.2.0" else null
    }

    override suspend fun macAddress(): String? {
        return connectedId?.uppercase()
    }

    override suspend fun signal(): Int? {
        return if (connectedId != null) -55 else null
    }
}
