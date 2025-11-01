package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
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

    @Test
    fun awaitReadyRecoversWhenWarmupArrivesAfterMtuTimeout() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val ready = async { harness.client.awaitReady(timeoutMs = 10_000) }
            runCurrent()

            harness.connectionStateFlow.value = G1BleUartClient.ConnectionState.CONNECTED
            harness.mtuFlow.value = 498
            harness.notificationsArmedFlow.value = true
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            launch {
                delay(6_000)
                collector.emit("> OK\r\n".toByteArray())
            }

            advanceTimeBy(5_000)
            runCurrent()

            assertFalse(ready.isCompleted)

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Ready, ready.await())
            assertEquals(498, harness.client.state.value.attMtu)
            assertTrue(harness.client.state.value.warmupOk)
            verify(exactly = 1) {
                harness.uartClient.write(match { payload -> payload.firstOrNull() == 0x4D.toByte() })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun warmupAckWithMtuOpcodeSetsWarmupOk() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured

            collector.emit(byteArrayOf(0x4D, 0xC9.toByte()))
            runCurrent()

            assertTrue(harness.client.state.value.warmupOk)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun warmupKeepaliveDoesNotCompleteWarmup() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()

            assertFalse(harness.client.state.value.warmupOk)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun warmupOkPromptStillCompletesWarmup() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured

            collector.emit("> OK\r\n".toByteArray())
            runCurrent()

            assertTrue(harness.client.state.value.warmupOk)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun sendCommandIgnoresMismatchedAckUntilMatchingOpcodeArrives() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured

            val command = async {
                harness.client.sendCommand(
                    payload = byteArrayOf(0x25),
                    ackTimeoutMs = 10_000,
                    retries = 1,
                    retryDelayMs = 0,
                )
            }

            runCurrent()

            collector.emit(byteArrayOf(0xF1.toByte(), 0xCA.toByte()))
            runCurrent()
            assertFalse(command.isCompleted)

            collector.emit(byteArrayOf(0xF1.toByte(), 0xC9.toByte()))
            runCurrent()
            assertFalse(command.isCompleted)

            collector.emit(byteArrayOf(0x25, 0xC9.toByte()))
            runCurrent()

            assertTrue(command.await())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun sendCommandIgnoresKeepaliveWhileAwaitingMtuAck() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val command = async {
                harness.client.sendCommand(
                    payload = byteArrayOf(0x4D),
                    ackTimeoutMs = 10_000,
                    retries = 1,
                    retryDelayMs = 0,
                )
            }

            runCurrent()

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()
            assertFalse(command.isCompleted)

            collector.emit(byteArrayOf(0x4D, 0xC9.toByte()))
            runCurrent()

            assertTrue(command.await())
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun bondRemovalSchedulesRetry() = runTest {
        val harness = buildClientHarness(this)
        try {
            val receiverSlot: CapturingSlot<BroadcastReceiver> = slot()
            every { harness.context.registerReceiver(capture(receiverSlot), any()) } returns null

            var createBondCount = 0
            every { harness.device.createBond() } answers {
                createBondCount += 1
                true
            }

            harness.client.connect()
            runCurrent()

            assertEquals(1, createBondCount)

            val intent = mockk<Intent> {
                every { action } returns BluetoothDevice.ACTION_BOND_STATE_CHANGED
                every { getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns harness.device
                every { getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, any()) } returns BOND_STATE_REMOVED
                every { getIntExtra("android.bluetooth.device.extra.REASON", any()) } returns UNBOND_REASON_REMOVED
            }

            receiverSlot.captured.onReceive(harness.context, intent)
            runCurrent()

            advanceTimeBy(BOND_RETRY_DELAY_MS)
            runCurrent()

            assertEquals(2, createBondCount)
            verify(exactly = 1) { harness.uartClient.refresh() }
        } finally {
            harness.client.close()
        }
    }

    private fun buildClient(scope: CoroutineScope): G1BleClient = buildClientHarness(scope).client

    private data class ClientHarness(
        val client: G1BleClient,
        val context: Context,
        val device: BluetoothDevice,
        val uartClient: G1BleUartClient,
        val connectionStateFlow: MutableStateFlow<G1BleUartClient.ConnectionState>,
        val mtuFlow: MutableStateFlow<Int>,
        val notificationsArmedFlow: MutableStateFlow<Boolean>,
        val notificationCollectorSlot: CapturingSlot<FlowCollector<ByteArray>>,
    )

    private fun buildClientHarness(scope: CoroutineScope): ClientHarness {
        val context = mockk<Context>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true) {
            every { bondState } returns BluetoothDevice.BOND_NONE
            every { address } returns "AA:BB:CC:DD:EE:FF"
        }
        val logger = mockk<MoncchichiLogger>(relaxed = true)
        val connectionState = MutableStateFlow(G1BleUartClient.ConnectionState.DISCONNECTED)
        val rssi = MutableStateFlow<Int?>(null)
        val mtu = MutableStateFlow(23)
        val notificationsArmed = MutableStateFlow(false)
        val notificationCollectorSlot = slot<FlowCollector<ByteArray>>()
        val uartClient = mockk<G1BleUartClient>(relaxed = true) {
            every { connectionState } returns connectionState
            every { rssi } returns rssi
            every { mtu } returns mtu
            every { notificationsArmed } returns notificationsArmed
            every { write(any()) } returns true
        }
        coEvery { uartClient.observeNotifications(capture(notificationCollectorSlot)) } coAnswers { }
        val client = G1BleClient(
            context = context,
            device = device,
            scope = scope,
            label = "[test]",
            logger = logger,
        ) { _, _, _, _ -> uartClient }
        return ClientHarness(
            client = client,
            context = context,
            device = device,
            uartClient = uartClient,
            connectionStateFlow = connectionState,
            mtuFlow = mtu,
            notificationsArmedFlow = notificationsArmed,
            notificationCollectorSlot = notificationCollectorSlot,
        )
    }

    private companion object {
        private const val BOND_STATE_REMOVED = 9
        private const val UNBOND_REASON_REMOVED = 5
        private const val BOND_RETRY_DELAY_MS = 750L
    }
}
