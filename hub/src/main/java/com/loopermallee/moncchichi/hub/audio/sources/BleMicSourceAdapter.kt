package com.loopermallee.moncchichi.hub.audio.sources

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.audio.AudioFrame
import com.loopermallee.moncchichi.hub.audio.MicMetrics
import com.loopermallee.moncchichi.hub.audio.MicSource
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.tryLock
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class BleMicSourceAdapter(
    private val telemetryRepository: BleTelemetryRepository,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {

    private val mutex = Mutex()
    private var collectorJob: Job? = null
    private var metricsJob: Job? = null
    private val timestamps = ArrayDeque<Long>()
    private var lastSequence: Int? = null
    private var gapCount: Int = 0
    private var totalFrames: Int = 0
    private var lastGapAtMs: Long? = null
    private var lastLens: MoncchichiBleService.Lens? = null

    private val _frames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<AudioFrame> = _frames.asSharedFlow()

    private val _metrics = MutableStateFlow(
        MicMetrics(
            source = MicSource.GLASSES,
            sampleRateHz = 16_000,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        ),
    )
    val metrics: StateFlow<MicMetrics> = _metrics.asStateFlow()

    val availability: StateFlow<Boolean> = telemetryRepository.micAvailability

    fun start() {
        if (collectorJob != null) return
        reset()
        collectorJob = scope.launch {
            telemetryRepository.micPackets.collect { event ->
                val payload = event.payload
                val pcm = payload.toPcmShorts()
                val frame = AudioFrame(
                    pcm = pcm,
                    sampleRateHz = 16_000,
                    timestampNanos = TimeUnit.MILLISECONDS.toNanos(event.timestampMs),
                )
                mutex.withLock {
                    timestamps += event.timestampMs
                    pruneTimestamps(event.timestampMs)
                    event.sequence?.let { sequence ->
                        lastSequence?.let { previous ->
                            val delta = (sequence - previous) and 0xFF
                            if (delta > 1) {
                                gapCount += delta - 1
                                lastGapAtMs = event.timestampMs
                                logger("[MIC] BLE gap +${delta - 1} (seq=$sequence)")
                            }
                        }
                        lastSequence = sequence
                    }
                    totalFrames += 1
                    lastLens = event.lens
                }
                _frames.emit(frame)
            }
        }
        metricsJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                publishMetrics()
            }
        }
    }

    fun stop() {
        collectorJob?.cancel(); collectorJob = null
        metricsJob?.cancel(); metricsJob = null
        reset()
    }

    private suspend fun publishMetrics() {
        val snapshot = telemetryRepository.snapshot.value
        val now = System.currentTimeMillis()
        val stats = mutex.withLock {
            pruneTimestamps(now)
            val framesPerSecond = timestamps.size
            val lastGap = lastGapAtMs?.let { gapAt ->
                if (gapCount == 0) {
                    0
                } else {
                    (now - gapAt).coerceAtLeast(0).toInt()
                }
            } ?: 0
            val lens = lastLens
            val rssi = when (lens) {
                MoncchichiBleService.Lens.LEFT -> snapshot.left.rssi
                MoncchichiBleService.Lens.RIGHT -> snapshot.right.rssi
                null -> snapshot.right.rssi ?: snapshot.left.rssi
            }
            val total = totalFrames + gapCount
            val loss = if (total == 0) {
                null
            } else {
                (gapCount * 100f) / total.toFloat()
            }
            MicMetrics(
                source = MicSource.GLASSES,
                sampleRateHz = 16_000,
                framesPerSec = framesPerSecond,
                gapCount = gapCount,
                lastGapMs = lastGap,
                rssiAvg = rssi,
                packetLossPct = loss,
            )
        }
        _metrics.value = stats
    }

    private fun reset() {
        val locked = mutex.tryLock(owner = null)
        if (locked) {
            try {
                clearState()
            } finally {
                mutex.unlock()
            }
        } else {
            scope.launch { mutex.withLock { clearState() } }
        }
    }

    private fun clearState() {
        timestamps.clear()
        lastSequence = null
        gapCount = 0
        totalFrames = 0
        lastGapAtMs = null
        lastLens = null
        _metrics.value = MicMetrics(
            source = MicSource.GLASSES,
            sampleRateHz = 16_000,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        )
    }

    private fun pruneTimestamps(nowMs: Long) {
        while (timestamps.isNotEmpty()) {
            val oldest = timestamps.first()
            if (nowMs - oldest > 1_000L) {
                timestamps.removeFirst()
            } else {
                break
            }
        }
    }

    private fun ByteArray.toPcmShorts(): ShortArray {
        if (isEmpty()) return ShortArray(0)
        val frameCount = size / 2
        val buffer = ShortArray(frameCount)
        val limit = frameCount * 2
        val shortBuffer = ByteBuffer.wrap(this, 0, limit)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        shortBuffer.get(buffer)
        return buffer
    }
}
