package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class G1BleClientTest {

    @Test
    fun awaitConnectedReturnsTrueWhenSequenceConnects() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitConnected(timeoutMs = 5_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTING)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTED)
            runCurrent()

            assertTrue(deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitConnectedReturnsFalseWhenSequenceDisconnects() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitConnected(timeoutMs = 5_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTING)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.DISCONNECTED)
            runCurrent()

            assertFalse(deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitReadyEmitsAfterMtuAckAndWarmup() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitReady(timeoutMs = 5_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTED)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(attMtu = 256)
            runCurrent()

            assertFalse(deferred.isCompleted)

            stateFlow.value = stateFlow.value.copy(warmupOk = true)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Ready, deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitReadyReturnsDisconnectWhenLinkDrops() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitReady(timeoutMs = 5_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.DISCONNECTED)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Disconnected, deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitReadyTimesOutWhenReadyNeverArrives() = runTest {
        val client = buildClient(this)
        try {
            val deferred = async { client.awaitReady(timeoutMs = 1_000) }

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Timeout, deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitReadyDoesNotCompleteWithoutWarmupOk() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitReady(timeoutMs = 1_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTED)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(attMtu = 256)
            runCurrent()

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Timeout, deferred.await())
        } finally {
            client.close()
        }
    }

    private fun buildClient(scope: CoroutineScope): G1BleClient {
        val context = mockk<Context>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true) {
            every { bondState } returns BluetoothDevice.BOND_NONE
        }
        val logger = mockk<MoncchichiLogger>(relaxed = true)
        val uartClient = mockk<G1BleUartClient>(relaxed = true) {
            every { connectionState } returns MutableStateFlow(G1BleUartClient.ConnectionState.DISCONNECTED)
            every { rssi } returns MutableStateFlow<Int?>(null)
            every { mtu } returns MutableStateFlow(23)
            every { notificationsArmed } returns MutableStateFlow(false)
        }
        return G1BleClient(
            context = context,
            device = device,
            scope = scope,
            label = "[test]",
            logger = logger,
        ) { _, _, _, _ -> uartClient }
    }
}
