package com.loopermallee.moncchichi.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Tracks microphone audio packets (0xF1) and exposes rolling statistics.
 */
class MicStreamManager(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class State(
        val rxRate: Double = 0.0,
        val gaps: Int = 0,
        val lastSequence: Int? = null,
    )

    private val timestamps = LongArray(capacity)
    private val sequences = IntArray(capacity)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var head = 0
    private var size = 0
    private var totalGaps = 0
    private var lastSeq: Int? = null

    @Synchronized
    fun reset() {
        head = 0
        size = 0
        totalGaps = 0
        lastSeq = null
        timestamps.fill(0L)
        sequences.fill(0)
        _state.value = State()
    }

    /**
     * Records a packet sequence value. Returns the number of missing packets detected.
     */
    fun record(sequence: Int?, timestampMs: Long = clock()): Int {
        if (sequence == null) {
            return 0
        }
        val (gapsAdded, state) = updateInternal(sequence and 0xFF, timestampMs)
        _state.value = state
        return gapsAdded
    }

    @Synchronized
    private fun updateInternal(sequence: Int, timestampMs: Long): Pair<Int, State> {
        val previousSeq = lastSeq
        val delta = previousSeq?.let { ((sequence - it) and 0xFF) } ?: 0
        val missing = when {
            previousSeq == null -> 0
            delta <= 0 -> 0
            delta > 1 -> delta - 1
            else -> 0
        }
        if (missing > 0) {
            totalGaps += missing
        }

        timestamps[head] = timestampMs
        sequences[head] = sequence
        head = (head + 1) % capacity
        if (size < capacity) {
            size += 1
        }
        lastSeq = sequence

        val (recentCount, earliestTs) = countRecent(timestampMs)
        val durationMs = if (recentCount > 1 && earliestTs != null) {
            max(timestampMs - earliestTs, 1L)
        } else {
            windowMs
        }
        val rate = if (recentCount == 0) {
            0.0
        } else {
            (recentCount * 1000.0) / durationMs.toDouble()
        }
        val nextState = State(rxRate = rate, gaps = totalGaps, lastSequence = sequence)
        return missing to nextState
    }

    private fun countRecent(now: Long): Pair<Int, Long?> {
        if (size == 0) {
            return 0 to null
        }
        var count = 0
        var earliest: Long? = null
        var offset = 1
        while (offset <= size) {
            val index = (head - offset + capacity) % capacity
            val ts = timestamps[index]
            if (ts <= 0L) {
                break
            }
            if (now - ts > windowMs) {
                break
            }
            earliest = ts
            count += 1
            offset += 1
        }
        return count to earliest
    }

    companion object {
        private const val DEFAULT_CAPACITY: Int = 256
        private const val DEFAULT_WINDOW_MS: Long = 1_000L
    }
}
