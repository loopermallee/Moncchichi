package com.loopermallee.moncchichi.hub.diagnostics

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository.CaseStatus
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository.DeviceTelemetrySnapshot
import java.util.ArrayDeque
import java.util.EnumMap
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
    caseStatusFlow: Flow<CaseStatus>,
    private val emitConsole: (tag: String, MoncchichiBleService.Lens?, String, Long) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val latestTelemetry = MutableStateFlow(emptyList<DeviceTelemetrySnapshot>())
    private val latestCaseStatus = MutableStateFlow(CaseStatus())
    private val caseToggleHistory = ArrayDeque<Long>()
    private var lastCaseState: Boolean? = null
    private val lastUptime = mutableMapOf<MoncchichiBleService.Lens, Long?>()
    private val uptimeRegressionActive = mutableMapOf<MoncchichiBleService.Lens, Boolean>()
    private var voltageDriftStart: Long? = null
    private var voltageWarned = false
    private var casePercentWarnedGlobal = false
    private val casePercentWarnedByLens = EnumMap<MoncchichiBleService.Lens, Boolean>(MoncchichiBleService.Lens::class.java)
    private var caseFlapWarned = false

    init {
        scope.launch {
            telemetry.collect { snapshots ->
                latestTelemetry.value = snapshots
            }
        }
        scope.launch {
            caseStatusFlow.collect { status ->
                latestCaseStatus.value = status
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
        casePercentWarnedGlobal = false
        casePercentWarnedByLens.clear()
        caseFlapWarned = false
        caseToggleHistory.clear()
        lastCaseState = null
        lastUptime.clear()
        uptimeRegressionActive.clear()
        latestCaseStatus.value = CaseStatus()
    }

    private fun validate(snapshots: List<DeviceTelemetrySnapshot>) {
        if (snapshots.isEmpty()) return
        val now = clock()
        val lensSnapshots = snapshots.associateBy { it.lens }
        val caseStatus = latestCaseStatus.value

        checkVoltageDrift(now, lensSnapshots)
        checkCasePercent(now, caseStatus, snapshots)
        checkCaseFlapping(now, caseStatus)
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

    private fun checkCasePercent(
        now: Long,
        status: CaseStatus,
        snapshots: List<DeviceTelemetrySnapshot>,
    ) {
        val percent = status.batteryPercent
        val invalidPercent = percent != null && (percent < 0 || percent > 100)
        if (invalidPercent) {
            if (!casePercentWarnedGlobal) {
                emitConsole("WARN", null, "[CASE] invalid percent", now)
                casePercentWarnedGlobal = true
            }
        } else {
            casePercentWarnedGlobal = false
        }
        for (snapshot in snapshots) {
            val lensPercent = snapshot.caseBatteryPercent
            val invalidLens = lensPercent != null && (lensPercent < 0 || lensPercent > 100)
            val warned = casePercentWarnedByLens[snapshot.lens] == true
            if (invalidLens) {
                if (!warned) {
                    emitConsole("WARN", snapshot.lens, "[CASE] invalid percent", now)
                    casePercentWarnedByLens[snapshot.lens] = true
                }
            } else {
                casePercentWarnedByLens.remove(snapshot.lens)
            }
        }
    }

    private fun checkCaseFlapping(
        now: Long,
        status: CaseStatus,
    ) {
        val state = status.lidOpen
        if (state == null) {
            caseToggleHistory.clear()
            lastCaseState = null
            caseFlapWarned = false
            return
        }
        val last = lastCaseState
        if (last != null && last != state) {
            caseToggleHistory.addLast(now)
            while (caseToggleHistory.size > 10) {
                caseToggleHistory.removeFirst()
            }
        }
        lastCaseState = state
        while (caseToggleHistory.isNotEmpty() && now - caseToggleHistory.first() > FLAP_WINDOW_MS) {
            caseToggleHistory.removeFirst()
        }
        if (caseToggleHistory.size >= FLAP_THRESHOLD) {
            if (!caseFlapWarned) {
                emitConsole("WARN", null, "[CASE] unstable lid state", now)
                caseFlapWarned = true
            }
        } else {
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
        private const val FLAP_WINDOW_MS = 5_000L
        private const val FLAP_THRESHOLD = 2
    }
}
