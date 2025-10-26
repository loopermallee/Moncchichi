package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.hub.data.db.AssistantEntry
import com.loopermallee.moncchichi.hub.data.db.ConsoleLine
import com.loopermallee.moncchichi.hub.data.db.MemoryDao
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.db.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
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
