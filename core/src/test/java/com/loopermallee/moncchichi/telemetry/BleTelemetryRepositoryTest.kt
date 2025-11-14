package com.loopermallee.moncchichi.telemetry

import com.loopermallee.moncchichi.bluetooth.G1MessageParser
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BleTelemetryRepositoryTest {

    @Test
    fun recordTelemetryUpdatePropagatesCaseState() = runTest {
        val repository = BleTelemetryRepository()
        val update = G1MessageParser.TelemetryUpdate(
            caseOpen = true,
            inCase = false,
            foldState = true,
        )

        repository.recordTelemetry(MoncchichiBleService.Lens.LEFT, update)

        val snapshot = repository.snapshot.value
        assertEquals(true, snapshot.left.caseOpen)
        assertEquals(false, snapshot.left.inCase)
        assertEquals(true, snapshot.left.folded)
        assertEquals(true, snapshot.caseOpen)
        assertEquals(false, snapshot.inCase)
        assertEquals(true, snapshot.folded)
    }
}
