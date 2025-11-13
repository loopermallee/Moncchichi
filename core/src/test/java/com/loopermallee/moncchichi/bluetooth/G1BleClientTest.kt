package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.coEvery
import io.mockk.match
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelAndJoin
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
            stateFlow.value = stateFlow.value.copy(bonded = true)
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
    fun awaitReadyCompletesWhenMtuKnownEvenWithoutWarmupOk() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitReady(timeoutMs = 1_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTED)
            runCurrent()
            stateFlow.value = stateFlow.value.copy(bonded = true)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(attMtu = 256)
            runCurrent()

            assertEquals(G1BleClient.AwaitReadyResult.Ready, deferred.await())
        } finally {
            client.close()
        }
    }

    @Test
    fun awaitReadyRequiresBondedState() = runTest {
        val client = buildClient(this)
        try {
            val stateFlow = client.state as MutableStateFlow<G1BleClient.State>
            val deferred = async { client.awaitReady(timeoutMs = 1_000) }

            runCurrent()

            stateFlow.value = stateFlow.value.copy(status = G1BleClient.ConnectionState.CONNECTED)
            runCurrent()

            stateFlow.value = stateFlow.value.copy(attMtu = 256, warmupOk = true)
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
    fun sendCommandWaitsForNotificationsBeforeWriting() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val writeDeferred = async {
                harness.client.sendCommand(
                    payload = byteArrayOf(0x42),
                    ackTimeoutMs = 1_000L,
                    retries = 1,
                    retryDelayMs = 100L,
                    expectAck = false,
                )
            }

            runCurrent()
            assertFalse(writeDeferred.isCompleted)
            verify(exactly = 0) {
                harness.uartClient.write(match { payload -> payload.firstOrNull() == 0x42.toByte() })
            }

            harness.connectionStateFlow.value = G1BleUartClient.ConnectionState.CONNECTED
            harness.mtuFlow.value = 498
            runCurrent()

            advanceTimeBy(500)
            runCurrent()
            assertFalse(writeDeferred.isCompleted)
            verify(exactly = 0) {
                harness.uartClient.write(match { payload -> payload.firstOrNull() == 0x42.toByte() })
            }

            harness.notificationsArmedFlow.value = true
            runCurrent()

            assertTrue(writeDeferred.await())
            verify {
                harness.uartClient.write(match { payload -> payload.firstOrNull() == 0x42.toByte() })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun enqueueHeartbeatWaitsForNotifications() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val heartbeat = async {
                harness.client.enqueueHeartbeat(
                    sequence = 0x10,
                    payload = byteArrayOf(G1Protocols.CMD_KEEPALIVE.toByte(), 0x10),
                )
            }

            runCurrent()
            assertFalse(heartbeat.isCompleted)
            verify(exactly = 0) { harness.uartClient.write(any<ByteArray>()) }

            harness.connectionStateFlow.value = G1BleUartClient.ConnectionState.CONNECTED
            harness.mtuFlow.value = 498
            runCurrent()

            harness.notificationsArmedFlow.value = true
            runCurrent()

            assertTrue(heartbeat.await())
            verify {
                harness.uartClient.write(match { payload ->
                    payload.firstOrNull() == G1Protocols.CMD_KEEPALIVE.toByte()
                })
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
    fun warmupAckWithAsciiOkCompletesWarmup() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            harness.connectionStateFlow.value = G1BleUartClient.ConnectionState.CONNECTED
            harness.mtuFlow.value = 498
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            harness.notificationsArmedFlow.value = true
            advanceTimeBy(200)
            runCurrent()

            collector.emit("OK".toByteArray())
            runCurrent()

            assertTrue(harness.client.state.value.warmupOk)
            assertEquals(498, harness.client.state.value.attMtu)
            verify { harness.uartClient.write(match { payload -> payload.firstOrNull() == 0x4D.toByte() }) }
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
    fun keepAlivePromptEmittedForAckToken() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val prompt = async { harness.client.keepAlivePrompts.first() }

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()

            assertEquals(G1BleClient.KeepAlivePrompt.Source.Token, prompt.await().source)
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun keepAliveResponderSendsSequenceOnPrompt() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val prompt = async { harness.client.keepAlivePrompts.first() }

            collector.emit(byteArrayOf(0xF1.toByte(), 0xC9.toByte()))
            runCurrent()

            val promptValue = prompt.await()
            val result = async { harness.client.respondToKeepAlivePrompt(promptValue) }

            launch {
                delay(100)
                collector.emit(byteArrayOf(0xF1.toByte(), 0xC9.toByte()))
            }

            advanceTimeBy(200)
            runCurrent()

            val keepAliveResult = result.await()
            assertTrue(keepAliveResult.success)
            assertEquals(1, keepAliveResult.sequence)
            assertEquals(0, keepAliveResult.lockContentionCount)
            assertEquals(0, keepAliveResult.ackTimeoutCount)
            verify(exactly = 1) {
                harness.uartClient.write(match { payload ->
                    payload.size == 2 &&
                        payload[0] == 0xF1.toByte() &&
                        (payload[1].toInt() and 0xFF) == 0x01
                })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun keepAliveResponderRetriesOnTimeout() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val prompt = async { harness.client.keepAlivePrompts.first() }

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()

            val resultDeferred = async { harness.client.respondToKeepAlivePrompt(prompt.await()) }

            repeat(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS) { attempt ->
                advanceTimeBy(KEEP_ALIVE_ACK_TIMEOUT_MS)
                runCurrent()
                if (attempt < G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS - 1) {
                    advanceTimeBy(KEEP_ALIVE_RETRY_BACKOFF_MS * (attempt + 1))
                    runCurrent()
                }
            }

            val result = resultDeferred.await()
            assertFalse(result.success)
            assertEquals(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS, result.attemptCount)
            assertEquals(1, result.sequence)
            assertEquals(0, result.lockContentionCount)
            assertEquals(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS, result.ackTimeoutCount)
            verify(exactly = G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS) { harness.uartClient.write(any<ByteArray>()) }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun keepAliveResponderTracksLockContention() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val prompt = async { harness.client.keepAlivePrompts.first() }

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()

            val blockingCommand = launch {
                harness.client.sendCommand(
                    payload = byteArrayOf(0x7F.toByte()),
                    ackTimeoutMs = 5_000L,
                    retries = 1,
                    retryDelayMs = 100L,
                )
            }

            runCurrent()

            val resultDeferred = async { harness.client.respondToKeepAlivePrompt(prompt.await()) }

            repeat(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS) { attempt ->
                advanceTimeBy(KEEP_ALIVE_ACK_TIMEOUT_MS)
                runCurrent()
                if (attempt < G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS - 1) {
                    advanceTimeBy(KEEP_ALIVE_RETRY_BACKOFF_MS * (attempt + 1))
                    runCurrent()
                }
            }

            val result = resultDeferred.await()
            assertFalse(result.success)
            assertEquals(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS, result.attemptCount)
            assertEquals(G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS, result.lockContentionCount)
            assertEquals(0, result.ackTimeoutCount)

            advanceTimeBy(5_000L)
            runCurrent()
            blockingCommand.cancelAndJoin()
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun keepAliveResponderEmitsDebugLog() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any<ByteArray>()) } returns true

            harness.client.connect()
            runCurrent()

            val collector = harness.notificationCollectorSlot.captured
            val prompt = async { harness.client.keepAlivePrompts.first() }

            collector.emit("ACK:KEEPALIVE\r\n".toByteArray())
            runCurrent()

            val result = async { harness.client.respondToKeepAlivePrompt(prompt.await()) }

            launch {
                delay(100)
                collector.emit(byteArrayOf(0xF1.toByte(), 0xC9.toByte()))
            }

            advanceTimeBy(200)
            runCurrent()
            result.await()

            verify { harness.logger.i(any(), match { it.contains("[BLE][KEEPALIVE]") }) }
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
    fun manualConnectResetsReconnectCounter() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            harness.client.connect()
            runCurrent()

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0, BluetoothProfile.STATE_CONNECTED))
            runCurrent()

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0, BluetoothProfile.STATE_DISCONNECTED))
            runCurrent()

            harness.client.connect()
            runCurrent()

            clearMocks(harness.logger, answers = false)

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0, BluetoothProfile.STATE_DISCONNECTED))
            runCurrent()

            verify {
                harness.logger.i(any(), match { message ->
                    message.contains("[GATT] Reconnecting after") && message.contains("attempt=1")
                })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun authFailureDisconnectSkipsReconnect() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit

            harness.client.connect()
            runCurrent()

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0, BluetoothProfile.STATE_CONNECTED))
            runCurrent()

            clearMocks(harness.logger, answers = false)

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0x85, BluetoothProfile.STATE_DISCONNECTED))
            runCurrent()

            verify {
                harness.logger.w(any(), match { message ->
                    message.contains("auth failure")
                })
            }
            verify(exactly = 0) {
                harness.logger.i(any(), match { message ->
                    message.contains("[GATT] Reconnecting after")
                })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun helloWatchdogRetriesBeforeReconnect() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            every { harness.uartClient.connect() } returns Unit
            every { harness.uartClient.write(any()) } returns true

            harness.client.connect()
            runCurrent()

            harness.connectionEventsFlow.tryEmit(G1BleUartClient.ConnectionEvent(0, BluetoothProfile.STATE_CONNECTED))
            runCurrent()

            harness.notificationsArmedFlow.value = true
            harness.mtuFlow.value = 498
            runCurrent()

            clearMocks(harness.uartClient, harness.logger, answers = false)

            advanceTimeBy(12_000L)
            runCurrent()

            advanceTimeBy(G1Protocols.MTU_WARMUP_GRACE_MS)
            runCurrent()

            advanceTimeBy(750L)
            runCurrent()

            verify(atLeast = 1) { harness.uartClient.requestWarmupOnNextNotify() }
            verify {
                harness.logger.i(any(), match { it.contains("HELLO recovery retrying HELLO") })
            }
            verify {
                harness.logger.i(any(), match { message ->
                    message.contains("[GATT] Reconnecting after HELLO timeout")
                })
            }
        } finally {
            harness.client.close()
        }
    }

    @Test
    fun bondBroadcastClearsBondedState() = runTest {
        val harness = buildClientHarness(this)
        try {
            every { harness.device.bondState } returns BluetoothDevice.BOND_BONDED
            val receiverSlot: CapturingSlot<BroadcastReceiver> = slot()
            every { harness.context.registerReceiver(capture(receiverSlot), any()) } returns mockk()

            harness.client.connect()
            runCurrent()

            assertTrue(harness.client.state.value.bonded)

            val intent = mockk<Intent> {
                every { action } returns BluetoothDevice.ACTION_BOND_STATE_CHANGED
                every { getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns harness.device
                every { getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, any()) } returns BluetoothDevice.BOND_NONE
                every { getIntExtra("android.bluetooth.device.extra.REASON", any()) } returns UNBOND_REASON_REMOVED
            }

            receiverSlot.captured.onReceive(harness.context, intent)
            runCurrent()

            assertFalse(harness.client.state.value.bonded)
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
        val connectionEventsFlow: MutableSharedFlow<G1BleUartClient.ConnectionEvent>,
        val mtuFlow: MutableStateFlow<Int>,
        val notificationsArmedFlow: MutableStateFlow<Boolean>,
        val notificationCollectorSlot: CapturingSlot<FlowCollector<ByteArray>>,
        val logger: MoncchichiLogger,
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
        val connectionEvents = MutableSharedFlow<G1BleUartClient.ConnectionEvent>(extraBufferCapacity = 8)
        val notificationCollectorSlot = slot<FlowCollector<ByteArray>>()
        val uartClient = mockk<G1BleUartClient>(relaxed = true) {
            every { connectionState } returns connectionState
            every { rssi } returns rssi
            every { mtu } returns mtu
            every { notificationsArmed } returns notificationsArmed
            every { connectionEvents } returns connectionEvents
            every { write(any()) } returns true
        }
        coEvery { uartClient.armNotificationsWithRetry(any(), any()) } returns true
        coEvery { uartClient.armNotificationsWithRetry() } returns true
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
            connectionEventsFlow = connectionEvents,
            mtuFlow = mtu,
            notificationsArmedFlow = notificationsArmed,
            notificationCollectorSlot = notificationCollectorSlot,
            logger = logger,
        )
    }

    private companion object {
        private const val BOND_STATE_REMOVED = 9
        private const val UNBOND_REASON_REMOVED = 5
        private const val BOND_RETRY_DELAY_MS = 750L
        private const val KEEP_ALIVE_ACK_TIMEOUT_MS = 1_000L
        private const val KEEP_ALIVE_RETRY_BACKOFF_MS = 150L
    }
}
