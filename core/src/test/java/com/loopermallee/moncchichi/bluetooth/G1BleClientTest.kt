package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G1BleClientTest {

    @Test
    fun awaitConnectedReturnsTrueWhenSequenceConnects() = runTest {
        val client = createClient()
        val state = client.mutableStateFlow()

        val result = async { client.awaitConnected(timeoutMs = 5_000) }
        advanceUntilIdle()

        state.value = state.value.copy(status = G1BleClient.ConnectionState.CONNECTING)
        advanceUntilIdle()
        state.value = state.value.copy(status = G1BleClient.ConnectionState.CONNECTED)

        assertTrue(result.await())
    }

    @Test
    fun awaitConnectedReturnsFalseWhenSequenceDisconnects() = runTest {
        val client = createClient()
        val state = client.mutableStateFlow()

        val result = async { client.awaitConnected(timeoutMs = 5_000) }
        advanceUntilIdle()

        state.value = state.value.copy(status = G1BleClient.ConnectionState.CONNECTING)
        advanceUntilIdle()
        state.value = state.value.copy(status = G1BleClient.ConnectionState.DISCONNECTED)

        assertFalse(result.await())
    }

    private fun TestScope.createClient(): G1BleClient {
        val context = mockk<Context>(relaxed = true)
        val device = mockk<BluetoothDevice>()
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        val logger = mockk<MoncchichiLogger>(relaxed = true)
        val uartClient = mockk<G1BleUartClient>(relaxed = true)

        return G1BleClient(context, device, this, label = "test", logger = logger) { _, _, _, _ ->
            uartClient
        }
    }

    private fun G1BleClient.mutableStateFlow(): MutableStateFlow<G1BleClient.State> {
        val field = G1BleClient::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as MutableStateFlow<G1BleClient.State>
    }
}
