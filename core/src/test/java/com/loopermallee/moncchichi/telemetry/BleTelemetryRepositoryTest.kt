package com.loopermallee.moncchichi.telemetry

import com.loopermallee.moncchichi.bluetooth.G1MessageParser
import com.loopermallee.moncchichi.bluetooth.G1Protocols
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
    fun lastVitalsTimestampOnlyUpdatesFromPayload() = runTest {
        val repository = BleTelemetryRepository()

        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf("batteryPercent" to 50),
        )

        val afterBattery = repository.snapshot.value
        val initialVitals = afterBattery.left.lastVitalsTimestamp ?: error("missing initial vitals timestamp")
        assertEquals(initialVitals, afterBattery.lastVitalsTimestamp)
        assertEquals(initialVitals, afterBattery.right.lastVitalsTimestamp)

        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf("lastVitalsTimestamp" to 2_000L),
        )

        val snapshot = repository.snapshot.value
        assertEquals(2_000L, snapshot.left.lastVitalsTimestamp)
        assertEquals(true, (snapshot.lastVitalsTimestamp ?: 0L) >= snapshot.left.lastVitalsTimestamp!!)
        assertEquals(snapshot.lastVitalsTimestamp, snapshot.right.lastVitalsTimestamp)

        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf("rssi" to -42),
        )

        val finalSnapshot = repository.snapshot.value
        val refreshedVitals = finalSnapshot.left.lastVitalsTimestamp ?: error("missing refreshed vitals timestamp")
        assertEquals(finalSnapshot.lastVitalsTimestamp, refreshedVitals)
        assertEquals(finalSnapshot.lastVitalsTimestamp, finalSnapshot.right.lastVitalsTimestamp)
        assertEquals(true, refreshedVitals >= 2_000L)
    }

    @Test
    fun sleepingRequiresAllConditionsAndFreshVitals() = runTest {
        val repository = BleTelemetryRepository()
        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf(
                "caseOpen" to true,
                "inCase" to true,
                "foldState" to true,
                "charging" to false,
            ),
        )

        val firstSnapshot = repository.snapshot.value
        assertEquals(null, firstSnapshot.left.lastVitalsTimestamp)

        val vitalsTimestamp = 1_000L
        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf(
                "caseOpen" to true,
                "inCase" to true,
                "foldState" to true,
                "charging" to false,
                "lastVitalsTimestamp" to vitalsTimestamp,
            ),
        )

        val snapshot = repository.snapshot.value
        val lastVitals = snapshot.left.lastVitalsTimestamp ?: error("missing vitals timestamp")
        val awakeNow = lastVitals + (G1Protocols.CE_IDLE_SLEEP_QUIET_WINDOW_MS / 2)
        assertEquals(false, repository.isSleeping(MoncchichiBleService.Lens.LEFT, awakeNow))
        assertEquals(true, repository.isAwake(MoncchichiBleService.Lens.LEFT, awakeNow))

        val sleepyNow = lastVitals + G1Protocols.CE_IDLE_SLEEP_QUIET_WINDOW_MS + 1_000
        assertEquals(true, repository.isSleeping(MoncchichiBleService.Lens.LEFT, sleepyNow))
        assertEquals(false, repository.isAwake(MoncchichiBleService.Lens.LEFT, sleepyNow))
    }
}
