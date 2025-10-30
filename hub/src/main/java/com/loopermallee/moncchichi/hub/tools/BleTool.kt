package com.loopermallee.moncchichi.hub.tools

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens

data class ScanResult(
    val id: String,
    val name: String?,
    val rssi: Int,
    val pairToken: String,
    val lens: Lens? = null,
    val timestampNanos: Long? = null,
)

interface BleTool {
    suspend fun scanDevices(onFound: (ScanResult) -> Unit)
    suspend fun stopScan()
    suspend fun connect(deviceId: String): Boolean
    suspend fun disconnect()
    suspend fun send(command: String): String
    suspend fun battery(): Int?
    suspend fun caseBattery(): Int?
    suspend fun firmware(): String?
    suspend fun macAddress(): String?
    suspend fun signal(): Int?
}
