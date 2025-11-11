package com.loopermallee.moncchichi.bluetooth.heartbeat

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class HeartbeatSupervisor(
    parentScope: CoroutineScope,
    private val log: (String) -> Unit,
    private val emitConsole: (String, MoncchichiBleService.Lens?, String, Long) -> Unit,
    private val sendHeartbeat: suspend (MoncchichiBleService.Lens) -> MoncchichiBleService.HeartbeatResult?,
    private val isLensConnected: (MoncchichiBleService.Lens) -> Boolean,
    private val onHeartbeatSuccess: (
        MoncchichiBleService.Lens,
        Int,
        Long,
        Long?,
        MoncchichiBleService.AckType,
        Long,
    ) -> Unit,
    private val onHeartbeatMiss: (MoncchichiBleService.Lens, Long, Int) -> Unit,
    private val rebondThreshold: Int,
    private val baseIntervalMs: Long,
    private val jitterMs: Long,
    private val idlePollMs: Long,
) {
    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private var job: Job? = null
    private val lidOpen = AtomicReference<Boolean?>(null)
    private val states = EnumMap<MoncchichiBleService.Lens, LensHeartbeatState>(MoncchichiBleService.Lens::class.java).apply {
        MoncchichiBleService.Lens.values().forEach { lens -> put(lens, LensHeartbeatState()) }
    }

    fun updateLensConnection(lens: MoncchichiBleService.Lens, connected: Boolean) {
        val state = states.getValue(lens)
        if (state.connected == connected) return
        state.connected = connected
        if (!connected) {
            state.nextDueAt = null
            state.missCount = 0
            state.lastSuccessAt = null
        } else {
            state.nextDueAt = 0L
        }
        restartLoop()
    }

    fun updateCaseState(value: Boolean?) {
        val previous = lidOpen.getAndSet(value)
        if (previous == value) return
        if (value == true) {
            states.values.forEach { state ->
                if (state.connected) {
                    state.nextDueAt = 0L
                }
            }
        }
        restartLoop()
    }

    fun updateInCaseState(lens: MoncchichiBleService.Lens, value: Boolean?) {
        val state = states.getValue(lens)
        if (state.inCase == value) return
        state.inCase = value
        if (value != true && state.connected) {
            state.nextDueAt = 0L
        }
        restartLoop()
    }

    fun onAck(lens: MoncchichiBleService.Lens?, timestamp: Long, type: MoncchichiBleService.AckType) {
        if (lens == null) return
        val state = states[lens] ?: return
        state.lastAckAt = timestamp
        state.lastAckType = type
    }

    private fun ensureLoop() {
        if (states.values.none { it.connected }) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    private fun restartLoop() {
        job?.cancel()
        job = null
        ensureLoop()
    }

    fun shutdown() {
        job?.cancel()
        job = null
        lidOpen.set(null)
        states.values.forEach { state ->
            state.connected = false
            state.inCase = null
            state.nextDueAt = null
            state.lastSuccessAt = null
            state.lastAckAt = null
            state.lastAckType = null
            state.missCount = 0
        }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            var nextDelay = idlePollMs
            var dispatched = false
            states.forEach { (lens, state) ->
                if (!state.connected || !isLensConnected(lens)) {
                    return@forEach
                }
                val gate = gatingReason(lens, state)
                if (gate != null) {
                    val dueAt = state.nextDueAt
                    if (dueAt != null && now >= dueAt) {
                        logSkip(lens, gate, now)
                        state.nextDueAt = null
                        state.missCount = 0
                    }
                    return@forEach
                }
                val dueAt = state.nextDueAt ?: now
                if (now >= dueAt) {
                    dispatched = true
                    val result = sendHeartbeat(lens)
                    val eventTimestamp = result?.timestamp ?: now
                    state.nextDueAt = computeNextDue(eventTimestamp)
                    if (result == null) {
                        return@forEach
                    }
                    if (result.success) {
                        state.missCount = 0
                        val ackType = result.ackType ?: MoncchichiBleService.AckType.BINARY
                        val elapsed = state.lastSuccessAt?.let { eventTimestamp - it }?.takeIf { it >= 0 } ?: baseIntervalMs
                        state.lastSuccessAt = eventTimestamp
                        onHeartbeatSuccess(lens, result.sequence, eventTimestamp, result.latencyMs, ackType, elapsed)
                    } else {
                        state.missCount = (state.missCount + 1).coerceAtMost(rebondThreshold)
                        onHeartbeatMiss(lens, eventTimestamp, state.missCount)
                    }
                } else {
                    val remaining = (dueAt - now).coerceAtLeast(idlePollMs)
                    if (remaining < nextDelay) {
                        nextDelay = remaining
                    }
                }
            }
            if (!dispatched) {
                delay(nextDelay)
            }
        }
    }

    private fun gatingReason(
        lens: MoncchichiBleService.Lens,
        state: LensHeartbeatState,
    ): GateReason? {
        val lid = lidOpen.get()
        if (lid == false) return GateReason.LidClosed
        if (state.inCase == true) return GateReason.InCase
        return null
    }

    private fun logSkip(lens: MoncchichiBleService.Lens, reason: GateReason, timestamp: Long) {
        val message = when (reason) {
            GateReason.LidClosed -> "skipped (lid closed)"
            GateReason.InCase -> "skipped (in case)"
        }
        emitConsole("PING", lens, message, timestamp)
        log("[BLE][PING][${lens.shortLabel}] $message")
    }

    private fun computeNextDue(base: Long): Long {
        val jitterOffset = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs + 1) else 0L
        val interval = (baseIntervalMs + jitterOffset).coerceAtLeast(idlePollMs)
        return base + interval
    }

    private data class LensHeartbeatState(
        var connected: Boolean = false,
        var inCase: Boolean? = null,
        var nextDueAt: Long? = null,
        var lastSuccessAt: Long? = null,
        var lastAckAt: Long? = null,
        var lastAckType: MoncchichiBleService.AckType? = null,
        var missCount: Int = 0,
    )

    private enum class GateReason { LidClosed, InCase }
}
