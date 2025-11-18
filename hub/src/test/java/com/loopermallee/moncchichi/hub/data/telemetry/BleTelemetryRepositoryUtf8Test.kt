package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.G1Protocols
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.data.db.AssistantEntry
import com.loopermallee.moncchichi.hub.data.db.ConsoleLine
import com.loopermallee.moncchichi.hub.data.db.MemoryDao
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.db.TelemetrySnapshot
import com.loopermallee.moncchichi.hub.telemetry.BleTelemetryParser
import com.loopermallee.moncchichi.telemetry.BleTelemetryRepository as CoreBleTelemetryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class BleTelemetryRepositoryUtf8Test {

    @Test
    fun `core snapshot json persists case state`() = runTest {
        val records = mutableListOf<CoreBleTelemetryRepository.SnapshotRecord>()
        val repository = CoreBleTelemetryRepository(
            snapshotStore = object : CoreBleTelemetryRepository.TelemetrySnapshotStore {
                override suspend fun persist(record: CoreBleTelemetryRepository.SnapshotRecord) {
                    records += record
                }
            },
        )

        repository.recordTelemetry(
            Lens.LEFT,
            mapOf(
                "batteryPercent" to 75,
                "caseOpen" to false,
                "inCase" to true,
                "folded" to true,
            ),
        )
        repository.persistSnapshot()

        val record = records.single()
        val leftJson = JSONObject(requireNotNull(record.leftJson))
        assertEquals(false, leftJson.getBoolean("caseOpen"))
        assertEquals(true, leftJson.getBoolean("inCase"))
        assertEquals(true, leftJson.getBoolean("foldState"))
        assertEquals(true, leftJson.getBoolean("folded"))
        assertTrue(leftJson.has("lastVitalsTimestamp"))
        assertTrue(leftJson.getLong("lastVitalsTimestamp") > 0)
    }

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
        assertNotNull(snapshot.left.lastVitalsTimestamp)

        val caseStatus = repository.caseStatus.value
        assertEquals(50, caseStatus.batteryPercent)
    }

    @Test
    fun `case status aggregates case telemetry globally`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x0F, 0x46))
        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x0E, 0x01))
        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x08))

        val status = repository.caseStatus.value
        assertEquals(70, status.batteryPercent)
        assertEquals(true, status.charging)
        assertEquals(true, status.lidOpen)

        val updatedAt = status.updatedAt

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x0F, 0x46))

        val afterDuplicate = repository.caseStatus.value
        assertEquals(70, afterDuplicate.batteryPercent)
        assertEquals(updatedAt, afterDuplicate.updatedAt)
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
                busy = false,
                timestampMs = 10L,
                warmup = false,
                type = MoncchichiBleService.AckType.BINARY,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = 0x25,
                status = 0xCA,
                success = false,
                busy = false,
                timestampMs = 20L,
                warmup = false,
                type = MoncchichiBleService.AckType.BINARY,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = null,
                status = 0xC9,
                success = true,
                busy = false,
                timestampMs = 30L,
                warmup = true,
                type = MoncchichiBleService.AckType.TEXTUAL,
            )
        )
        repository.onAck(
            MoncchichiBleService.AckEvent(
                lens = Lens.LEFT,
                opcode = 0x2C,
                status = 0xC9,
                success = true,
                busy = false,
                timestampMs = 45L,
                warmup = false,
                type = MoncchichiBleService.AckType.BINARY,
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

    @Test
    fun `gesture single frame emits one event`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        val gesture = async(UnconfinedTestDispatcher(testScheduler)) { repository.gesture.first() }
        val console = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.first { it.contains("[GESTURE]") }
        }

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x01))

        val event = gesture.await()
        assertEquals(Lens.LEFT, event.lens)
        assertEquals(0x01, event.gesture.code)

        val consoleLine = console.await()
        assertTrue(consoleLine.contains("[GESTURE][L] single"))
    }

    @Test
    fun `sleep events follow predicate transitions`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)
        val events = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.sleepEvents.take(2).toList(mutableListOf())
        }

        val snapshotField = repository.javaClass.getDeclaredField("_snapshot").apply { isAccessible = true }
        val snapshotFlow = snapshotField.get(repository) as MutableStateFlow<BleTelemetryRepository.Snapshot>
        val previous = snapshotFlow.value
        val sleepySnapshot = BleTelemetryRepository.Snapshot(
            left = BleTelemetryRepository.LensTelemetry(
                inCase = true,
                caseOpen = true,
                foldState = true,
                charging = false,
                lastVitalsTimestamp = 0L,
            ),
            right = BleTelemetryRepository.LensTelemetry(
                inCase = true,
                caseOpen = true,
                foldState = true,
                charging = false,
                lastVitalsTimestamp = 0L,
            ),
            caseOpen = true,
            inCase = true,
            foldState = true,
            lastVitalsTimestamp = 0L,
        )
        snapshotFlow.value = sleepySnapshot
        val transitionMethod = repository.javaClass.getDeclaredMethod(
            "maybeEmitSleepTransitions",
            BleTelemetryRepository.Snapshot::class.java,
            BleTelemetryRepository.Snapshot::class.java,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val sleepyNow = G1Protocols.CE_IDLE_SLEEP_QUIET_WINDOW_MS + 5_000L
        transitionMethod.invoke(repository, previous, sleepySnapshot, sleepyNow)

        val awakeSnapshot = sleepySnapshot.copy(
            left = sleepySnapshot.left.copy(lastVitalsTimestamp = 10_000L),
            right = sleepySnapshot.right.copy(lastVitalsTimestamp = 10_000L),
            lastVitalsTimestamp = 10_000L,
        )
        snapshotFlow.value = awakeSnapshot
        transitionMethod.invoke(repository, sleepySnapshot, awakeSnapshot, sleepyNow)

        val emitted = events.await()
        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is BleTelemetryRepository.SleepEvent.SleepEntered &&
            (emitted[0] as BleTelemetryRepository.SleepEvent.SleepEntered).lens == null)
        assertTrue(emitted[1] is BleTelemetryRepository.SleepEvent.SleepExited &&
            (emitted[1] as BleTelemetryRepository.SleepEvent.SleepExited).lens == null)
    }

    @Test
    fun `gesture trailing bytes ignored`() = runTest {
        val logs = mutableListOf<String>()
        val repository = BleTelemetryRepository(
            MemoryRepository(FakeMemoryDao()),
            backgroundScope,
        ) { message -> logs += message }

        val gestures = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.gesture.take(1).toList(mutableListOf())
        }
        val console = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.first { it.contains("[GESTURE]") }
        }

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x01, 0x11, 0x01))

        val emittedGestures = gestures.await()
        assertEquals(1, emittedGestures.size)
        assertEquals(0x01, emittedGestures[0].gesture.code)

        val consoleLine = console.await()
        assertTrue(consoleLine.contains("[GESTURE][L] single"))
        assertTrue(logs.none { it.contains("unknown", ignoreCase = true) })
    }

    @Test
    fun `multiple gesture frames parsed from one buffer`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        val gestures = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.gesture.take(2).toList(mutableListOf())
        }
        val console = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.take(2).toList(mutableListOf())
        }

        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x02, 0xF5.toByte(), 0x03))

        val emitted = gestures.await()
        assertEquals(listOf(0x02, 0x03), emitted.map { it.gesture.code })

        val consoleLines = console.await()
        assertEquals(2, consoleLines.size)
        assertTrue(consoleLines[0].contains("double"))
        assertTrue(consoleLines[1].contains("triple"))
    }

    @Test
    fun `duplicate gesture within window dropped`() = runTest {
        var now = 0L
        val parser = BleTelemetryParser { now }
        val logs = mutableListOf<String>()
        val repository = BleTelemetryRepository(
            MemoryRepository(FakeMemoryDao()),
            backgroundScope,
            { message -> logs += message },
            parser,
        )

        now = 0L
        val firstGesture = async(UnconfinedTestDispatcher(testScheduler)) { repository.gesture.first() }
        val firstConsole = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.first { it.contains("[GESTURE]") }
        }
        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x01))
        val initialGesture = firstGesture.await()
        assertEquals(0x01, initialGesture.gesture.code)
        firstConsole.await()
        assertEquals(1, logs.count { it.contains("[GESTURE]") })

        now = 100L
        val duplicateGesture = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(50) { repository.gesture.first() }
        }
        val duplicateConsole = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(50) { repository.events.first { it.contains("[GESTURE]") } }
        }
        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0x01))
        testScheduler.advanceTimeBy(50)
        assertNull(duplicateGesture.await())
        assertNull(duplicateConsole.await())
        assertEquals(1, logs.count { it.contains("[GESTURE]") })
    }

    @Test
    fun `duplicate gesture after window emitted`() = runTest {
        var now = 0L
        val parser = BleTelemetryParser { now }
        val repository = BleTelemetryRepository(
            MemoryRepository(FakeMemoryDao()),
            backgroundScope,
            telemetryParser = parser,
        )

        now = 0L
        val firstGesture = async(UnconfinedTestDispatcher(testScheduler)) { repository.gesture.first() }
        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x01))
        firstGesture.await()

        now = 350L
        val secondGesture = async(UnconfinedTestDispatcher(testScheduler)) { repository.gesture.first() }
        val secondConsole = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.first { it.contains("[GESTURE]") }
        }
        repository.onFrame(Lens.RIGHT, byteArrayOf(0xF5.toByte(), 0x01))

        val event = secondGesture.await()
        assertEquals(0x01, event.gesture.code)
        val consoleLine = secondConsole.await()
        assertTrue(consoleLine.contains("[GESTURE][R] single"))
    }

    @Test
    fun `unknown gesture emits diagnostic`() = runTest {
        val repository = BleTelemetryRepository(MemoryRepository(FakeMemoryDao()), backgroundScope)

        val gesture = async(UnconfinedTestDispatcher(testScheduler)) { repository.gesture.first() }
        val console = async(UnconfinedTestDispatcher(testScheduler)) {
            repository.events.first { it.contains("[GESTURE]") }
        }

        repository.onFrame(Lens.LEFT, byteArrayOf(0xF5.toByte(), 0xFF.toByte()))

        val event = gesture.await()
        assertEquals(0xFF, event.gesture.code)
        val consoleLine = console.await()
        assertTrue(consoleLine.contains("unknown", ignoreCase = true))
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
