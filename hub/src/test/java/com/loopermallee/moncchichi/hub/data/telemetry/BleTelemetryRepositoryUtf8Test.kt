package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
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
        val repository = BleTelemetryRepository()
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
    }
}
