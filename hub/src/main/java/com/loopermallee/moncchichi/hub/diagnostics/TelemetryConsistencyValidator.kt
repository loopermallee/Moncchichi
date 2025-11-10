package com.loopermallee.moncchichi.hub.diagnostics

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository.DeviceTelemetrySnapshot
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Periodically inspects aggregated telemetry for basic consistency checks.
 */
class TelemetryConsistencyValidator(
    scope: CoroutineScope,
    telemetry: Flow<List<DeviceTelemetrySnapshot>>,
    private val emitConsole: (tag: String, MoncchichiBleService.Lens?, String, Long) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val latestTelemetry = MutableStateFlow(emptyList<DeviceTelemetrySnapshot>())
    private val caseToggleHistory = mutableMapOf<MoncchichiBleService.Lens, ArrayDeque<Long>>()
    private val lastCaseState = mutableMapOf<MoncchichiBleService.Lens, Boolean?>()
    private val lastUptime = mutableMapOf<MoncchichiBleService.Lens, Long?>()
    private val uptimeRegressionActive = mutableMapOf<MoncchichiBleService.Lens, Boolean>()
    private var voltageDriftStart: Long? = null
    private var voltageWarned = false
    private var casePercentWarned = false
    private var caseFlapWarned = false

    init {
        scope.launch {
            telemetry.collect { snapshots ->
                latestTelemetry.value = snapshots
            }
        }
        scope.launch {
            while (isActive) {
                validate(latestTelemetry.value)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun reset() {
        voltageDriftStart = null
        voltageWarned = false
        casePercentWarned = false
        caseFlapWarned = false
        caseToggleHistory.values.forEach { it.clear() }
        lastCaseState.clear()
        lastUptime.clear()
        uptimeRegressionActive.clear()
    }

    private fun validate(snapshots: List<DeviceTelemetrySnapshot>) {
        if (snapshots.isEmpty()) return
        val now = clock()
        val lensSnapshots = snapshots.associateBy { it.lens }

        checkVoltageDrift(now, lensSnapshots)
        checkCasePercent(now, snapshots)
        checkCaseFlapping(now, lensSnapshots)
        checkUptime(now, snapshots)
    }

    private fun checkVoltageDrift(
        now: Long,
        lensSnapshots: Map<MoncchichiBleService.Lens, DeviceTelemetrySnapshot>,
    ) {
        val leftVoltage = lensSnapshots[MoncchichiBleService.Lens.LEFT]?.batteryVoltageMv
        val rightVoltage = lensSnapshots[MoncchichiBleService.Lens.RIGHT]?.batteryVoltageMv
        if (leftVoltage != null && rightVoltage != null) {
            val diff = abs(leftVoltage - rightVoltage)
            if (diff > VOLTAGE_DRIFT_THRESHOLD_MV) {
                if (voltageDriftStart == null) {
                    voltageDriftStart = now
                }
                val start = voltageDriftStart ?: now
                if (!voltageWarned && now - start > DRIFT_DURATION_THRESHOLD_MS) {
                    emitConsole("WARN", null, "[VITALS] voltage drift > ${VOLTAGE_DRIFT_THRESHOLD_MV} mV", now)
                    voltageWarned = true
                }
            } else {
                voltageDriftStart = null
                voltageWarned = false
            }
        } else {
            voltageDriftStart = null
            voltageWarned = false
        }
    }

    private fun checkCasePercent(now: Long, snapshots: List<DeviceTelemetrySnapshot>) {
        val hasTimestamps = snapshots.any { it.timestamp > 0L }
        if (!hasTimestamps) return
        val invalidPercent = snapshots.any { snapshot ->
            val percent = snapshot.caseBatteryPercent
            percent == null || percent < 0 || percent > 100
        }
        if (invalidPercent) {
            if (!casePercentWarned) {
                emitConsole("WARN", null, "[CASE] invalid percent", now)
                casePercentWarned = true
            }
        } else {
            casePercentWarned = false
        }
    }

    private fun checkCaseFlapping(
        now: Long,
        lensSnapshots: Map<MoncchichiBleService.Lens, DeviceTelemetrySnapshot>,
    ) {
        var totalTransitions = 0
        for (lens in MoncchichiBleService.Lens.values()) {
            val snapshot = lensSnapshots[lens]
            val state = snapshot?.caseOpen
            val history = caseToggleHistory.getOrPut(lens) { ArrayDeque() }
            val lastState = lastCaseState[lens]
            if (state == null) {
                history.clear()
                lastCaseState.remove(lens)
            } else {
                if (lastState != null && lastState != state) {
                    history.addLast(now)
                }
                lastCaseState[lens] = state
            }
            while (history.isNotEmpty() && now - history.first() > FLAP_WINDOW_MS) {
                history.removeFirst()
            }
            totalTransitions += history.size
        }
        if (totalTransitions > FLAP_THRESHOLD) {
            if (!caseFlapWarned) {
                emitConsole("WARN", null, "[CASE] unstable lid state", now)
                caseFlapWarned = true
            }
        } else if (totalTransitions == 0) {
            caseFlapWarned = false
        }
    }

    private fun checkUptime(now: Long, snapshots: List<DeviceTelemetrySnapshot>) {
        for (snapshot in snapshots) {
            val lens = snapshot.lens
            val uptime = snapshot.uptimeSeconds
            val last = lastUptime[lens]
            if (uptime != null && last != null && uptime < last) {
                val alreadyWarned = uptimeRegressionActive[lens] == true
                if (!alreadyWarned) {
                    emitConsole("WARN", lens, "[VITALS] uptime reset", now)
                    uptimeRegressionActive[lens] = true
                }
            } else if (uptime != null && last != null && uptime > last) {
                uptimeRegressionActive.remove(lens)
            }
            if (uptime != null) {
                lastUptime[lens] = uptime
            }
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 10_000L
        private const val VOLTAGE_DRIFT_THRESHOLD_MV = 200
        private const val DRIFT_DURATION_THRESHOLD_MS = 30_000L
        private const val FLAP_WINDOW_MS = 30_000L
        private const val FLAP_THRESHOLD = 3
    }
}
