package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import io.mockk.Runs
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class G1BleClientWarmupTest {

    @Test
    fun `bonded reconnect requests warmup exactly once per session`() = runTest {
        val context = mockk<Context> {
            every { registerReceiver(any(), any()) } returns null
            every { unregisterReceiver(any()) } just Runs
        }
        val device = mockk<BluetoothDevice> {
            every { address } returns "00:11:22:33:44:55"
            every { bondState } returns BluetoothDevice.BOND_BONDED
        }
        val logger = mockk<MoncchichiLogger>(relaxed = true)

        val connectionState = MutableStateFlow(G1BleUartClient.ConnectionState.DISCONNECTED)
        val rssi = MutableStateFlow<Int?>(null)
        val uartClient = mockk<G1BleUartClient> {
            every { this@mockk.connectionState } returns connectionState
            every { this@mockk.rssi } returns rssi
            every { connect() } just Runs
            every { close() } just Runs
            every { requestWarmupOnNextNotify() } just Runs
            coEvery { observeNotifications(any()) } coAnswers { }
        }

        val client = G1BleClient(
            context = context,
            device = device,
            scope = this,
            label = "test",
            logger = logger,
            uartClientFactory = { _, _, _, _ -> uartClient },
        )

        client.connect()
        advanceUntilIdle()

        verify(exactly = 1) { uartClient.requestWarmupOnNextNotify() }

        client.close()
        advanceUntilIdle()

        client.connect()
        advanceUntilIdle()

        verify(exactly = 2) { uartClient.requestWarmupOnNextNotify() }

        client.close()
        advanceUntilIdle()
    }
}
