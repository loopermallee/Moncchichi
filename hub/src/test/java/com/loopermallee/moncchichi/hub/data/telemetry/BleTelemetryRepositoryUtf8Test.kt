package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.data.db.AssistantEntry
import com.loopermallee.moncchichi.hub.data.db.ConsoleLine
import com.loopermallee.moncchichi.hub.data.db.MemoryDao
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.db.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class BleTelemetryRepositoryUtf8Test {

    @Test
    fun `ver banner emitted for each lens`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)
        val banner = "ver 1.2.3 DeviceID 42\n".encodeToByteArray()
        val collector = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.uartText.take(2).toList(mutableListOf())
        }

        repository.onFrame(Lens.LEFT, banner)
        repository.onFrame(Lens.RIGHT, banner)

        val lines = collector.await()
        assertEquals(2, lines.size)
        assertEquals(Lens.LEFT, lines[0].lens)
        assertEquals("ver 1.2.3 DeviceID 42", lines[0].text)
        assertEquals(Lens.RIGHT, lines[1].lens)
        assertEquals("ver 1.2.3 DeviceID 42", lines[1].text)

        val snapshot = repository.snapshot.value
        assertEquals("1.2.3", snapshot.left.firmwareVersion)
        assertEquals("DeviceID 42", snapshot.left.notes)
        assertEquals("1.2.3", snapshot.right.firmwareVersion)
        assertEquals("DeviceID 42", snapshot.right.notes)
    }

    @Test
    fun `f5 vitals update snapshot and emit diagnostics`() = runTest {
        val logs = mutableListOf<String>()
        val repository = BleTelemetryRepository(
            MemoryRepository(FakeMemoryDao()),
            backgroundScope,
        ) { message -> logs += message }

        val events = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.take(3).toList(mutableListOf())
        }

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x0A, 0x64))
        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x09, 0x00))
        val firmware = "v1.6.3".encodeToByteArray()
        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x11) + firmware)

        val snapshot = repository.snapshot.value
        assertEquals(100, snapshot.left.batteryPercent)
        assertEquals(false, snapshot.left.charging)
        assertEquals("v1.6.3", snapshot.left.firmwareVersion)

        val vitalsEvents = events.await()
        assertEquals(3, vitalsEvents.size)
        assertTrue(vitalsEvents[0].contains("[VITALS][L] battery=100%"))
        assertTrue(vitalsEvents[1].contains("[VITALS][L] not charging"))
        assertTrue(vitalsEvents[2].contains("[VITALS][L] fw=v1.6.3"))
        assertEquals(vitalsEvents, logs)
    }

    @Test
    fun `glasses state binary frame updates snapshot`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        repository.onFrame(Lens.LEFT, byteArrayOf(0x2B, 0xC9.toByte(), 0x07))

        val snapshot = repository.snapshot.value
        assertEquals(true, snapshot.left.silentMode)
        assertEquals(true, snapshot.left.wearing)
        assertEquals(true, snapshot.left.inCase)
    }

    @Test
    fun `battery payload parsed from ack encoded frame`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        repository.onFrame(Lens.LEFT, byteArrayOf(0x2C, 0xC9.toByte(), 0x01, 0x64, 0x32))

        val snapshot = repository.snapshot.value
        assertEquals(100, snapshot.left.batteryPercent)
        assertEquals(50, snapshot.left.caseBatteryPercent)
    }

    @Test
    fun `ack events update counters`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = 0x25,
                status = 0xC9,
                success = true,
                timestampMs = 10L,
                warmup = false,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = 0x25,
                status = 0xCA,
                success = false,
                timestampMs = 20L,
                warmup = false,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = null,
                status = 0xC9,
                success = true,
                timestampMs = 30L,
                warmup = true,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = 0x2C,
                status = 0xC9,
                success = true,
                timestampMs = 45L,
                warmup = false,
            )
        )

        val snapshot = repository.snapshot.value.left
        assertEquals(45L, snapshot.lastAckAt)
        assertEquals(3, snapshot.ackSuccessCount)
        assertEquals(0, snapshot.ackFailureCount)
        assertEquals(1, snapshot.ackWarmupCount)
    }

    @Test
    fun `gesture events include lens context`() = runTest {
        val logs = mutableListOf<String>()
        val repository = BleTelemetryRepository(
            MemoryRepository(FakeMemoryDao()),
            backgroundScope,
        ) { message -> logs += message }

        val gestures = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.gesture.take(2).toList(mutableListOf())
        }

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x01))
        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x02))

        val events = gestures.await()
        assertEquals(Lens.LEFT, events[0].lens)
        assertEquals(0x01, events[0].gesture.code)
        assertEquals(Lens.RIGHT, events[1].lens)
        assertEquals(0x02, events[1].gesture.code)

        val telemetrySnapshots = repository.deviceTelemetryFlow.first { snapshots ->
            snapshots.any { it.lastGesture != null }
        }
        val leftSnapshot = telemetrySnapshots.first { it.lens == Lens.LEFT }
        val rightSnapshot = telemetrySnapshots.first { it.lens == Lens.RIGHT }
        assertEquals(0x01, leftSnapshot.lastGesture?.gesture?.code)
        assertEquals(0x02, rightSnapshot.lastGesture?.gesture?.code)

        assertTrue(logs.any { it.contains("[GESTURE][L] single") })
        assertTrue(logs.any { it.contains("[GESTURE][R] double") })
    }
}

private class FakeMemoryDao : MemoryDao {
    private val console = mutableListOf<ConsoleLine>()
    private val assistant = mutableListOf<AssistantEntry>()
    private val telemetry = mutableListOf<TelemetrySnapshot>()

    override suspend fun addConsole(line: ConsoleLine) {
        console += line
    }

    override suspend fun addAssistant(entry: AssistantEntry) {
        assistant += entry
    }

    override suspend fun addTelemetry(snapshot: TelemetrySnapshot) {
        telemetry += snapshot
    }

    override suspend fun lastConsole(n: Int): List<ConsoleLine> =
        console.sortedByDescending { it.ts }.take(n)

    override suspend fun lastAssistant(n: Int): List<AssistantEntry> =
        assistant.sortedByDescending { it.ts }.take(n)

    override suspend fun recentTelemetrySnapshots(n: Int): List<TelemetrySnapshot> =
        telemetry.sortedByDescending { it.recordedAt }.take(n)

    override suspend fun clearConsole() {
        console.clear()
    }
}
