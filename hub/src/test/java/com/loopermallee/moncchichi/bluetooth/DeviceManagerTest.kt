package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.ble.G1BleUartClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class DeviceManagerTest {

    @Test
    fun awaitTerminalConnectionState_emitsConnectedAfterInitialDisconnect() = runTest {
        val states = MutableStateFlow(G1BleUartClient.ConnectionState.DISCONNECTED)

        val result = async { awaitTerminalConnectionState(states) }

        states.value = G1BleUartClient.ConnectionState.CONNECTING
        states.value = G1BleUartClient.ConnectionState.CONNECTED

        assertEquals(G1BleUartClient.ConnectionState.CONNECTED, result.await())
    }

    @Test
    fun awaitTerminalConnectionState_emitsDisconnectAfterFailure() = runTest {
        val states = MutableStateFlow(G1BleUartClient.ConnectionState.DISCONNECTED)

        val result = async { awaitTerminalConnectionState(states) }

        states.value = G1BleUartClient.ConnectionState.CONNECTING
        states.value = G1BleUartClient.ConnectionState.DISCONNECTED

        assertEquals(G1BleUartClient.ConnectionState.DISCONNECTED, result.await())
    }
}
