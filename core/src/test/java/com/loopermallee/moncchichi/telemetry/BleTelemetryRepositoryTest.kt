package com.loopermallee.moncchichi.telemetry

import com.loopermallee.moncchichi.bluetooth.G1MessageParser
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import kotlinx.coroutines.flow.MutableStateFlow
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
        val snapshotField = repository.javaClass.getDeclaredField("_snapshot").apply { isAccessible = true }
        val snapshotFlow = snapshotField.get(repository) as MutableStateFlow<BleTelemetryRepository.Snapshot>

        val quietSnapshot = BleTelemetryRepository.Snapshot(
            left = BleTelemetryRepository.LensSnapshot(
                caseOpen = true,
                inCase = true,
                foldState = true,
                charging = false,
                lastVitalsTimestamp = 0L,
            ),
            right = BleTelemetryRepository.LensSnapshot(
                caseOpen = true,
                inCase = true,
                foldState = true,
                charging = false,
                lastVitalsTimestamp = 0L,
            ),
            caseOpen = true,
            inCase = true,
            foldState = true,
            charging = false,
            lastVitalsTimestamp = 0L,
            sleepPhase = BleTelemetryRepository.SleepPhase.QUIET,
            quietPhaseStartedAt = 0L,
        )

        snapshotFlow.value = quietSnapshot

        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf("caseOpen" to true),
        )

        val sleepySnapshot = repository.snapshot.value
        assertEquals(BleTelemetryRepository.SleepPhase.SLEEP_CONFIRMED, sleepySnapshot.sleepPhase)
        assertEquals(BleTelemetryRepository.SleepEvent.SleepEntered(null), repository.sleepEvents.value)

        repository.recordTelemetry(
            MoncchichiBleService.Lens.LEFT,
            mapOf("caseOpen" to false),
        )

        val awakeSnapshot = repository.snapshot.value
        assertEquals(BleTelemetryRepository.SleepPhase.ACTIVE, awakeSnapshot.sleepPhase)
        assertEquals(BleTelemetryRepository.SleepEvent.SleepExited(null), repository.sleepEvents.value)
    }
}
