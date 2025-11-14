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
        assertEquals(true, snapshot.left.foldState)
        assertEquals(true, snapshot.caseOpen)
        assertEquals(false, snapshot.inCase)
        assertEquals(true, snapshot.foldState)
    }

    @Test
    fun sleepingRequiresAllConditionsAndFreshVitals() = runTest {
        val repository = BleTelemetryRepository()
        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf(
                "caseOpen" to false,
                "inCase" to true,
                "foldState" to true,
            ),
        )

        val snapshot = repository.snapshot.value
        val lastVitals = snapshot.left.lastVitalsTimestamp ?: error("missing vitals timestamp")
        val freshNow = lastVitals + 1_000

        assertEquals(true, repository.isSleeping(MoncchichiBleService.Lens.LEFT, freshNow))
        assertEquals(false, repository.isAwake(MoncchichiBleService.Lens.LEFT, freshNow))

        val staleNow = lastVitals + 4_000
        assertEquals(false, repository.isSleeping(MoncchichiBleService.Lens.LEFT, staleNow))
        assertEquals(true, repository.isAwake(MoncchichiBleService.Lens.LEFT, staleNow))
    }
}
