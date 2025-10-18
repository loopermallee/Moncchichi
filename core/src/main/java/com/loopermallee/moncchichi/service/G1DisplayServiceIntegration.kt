package com.loopermallee.moncchichi.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.loopermallee.moncchichi.ble.G1BleUartClient
import com.loopermallee.moncchichi.ble.G1ReplyParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Helper for integrating the BLE client and parser into G1DisplayService.
 */
class G1DisplayServiceIntegration(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    private val onVitalsUpdated: (G1ReplyParser.DeviceVitals) -> Unit
) {
    private var client: G1BleUartClient? = null

    fun connect(mac: String) {
        client?.close()
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
        client = G1BleUartClient(context, device, logger, scope)
        scope.launch {
            client!!.incoming.collect { bytes ->
                G1ReplyParser.parse(bytes, logger)
            }
        }
        scope.launch {
            G1ReplyParser.vitalsFlow.collect { vitals -> onVitalsUpdated(vitals) }
        }
        client!!.connect()
    }

    fun send(bytes: ByteArray) = client?.write(bytes)
    fun close() = client?.close()
}
