package com.loopermallee.moncchichi.hub.audio

import android.content.Context
import android.util.Log
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Lightweight representation of a microphone audio frame. */
data class AudioFrame(
    val pcm: ShortArray,
    val sampleRateHz: Int = 16_000,
    val timestampNanos: Long,
)

/** Minimal metrics container so existing diagnostics screens compile. */
data class MicMetrics(
    val source: MicSource,
    val sampleRateHz: Int,
    val framesPerSec: Int,
    val gapCount: Int,
    val lastGapMs: Int,
    val rssiAvg: Int?,
    val packetLossPct: Float?,
)

/**
 * Compile-time stub for the unified mic stream manager. It keeps the public API surface that the
 * rest of the hub references, but the methods are currently no-ops so the build succeeds while the
 * full implementation is completed in a future patch.
 */
class MicStreamManager {

    private val _metrics = MutableStateFlow(
        MicMetrics(
            source = MicSource.GLASSES,
            sampleRateHz = 0,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        ),
    )

    private val _availability = MutableStateFlow(
        MicSource.values().associateWith { false },
    )

    val metrics: Flow<MicMetrics> = _metrics.asStateFlow()

    val availability: Flow<Map<MicSource, Boolean>> = _availability.asStateFlow()

    fun startCapture(@Suppress("UNUSED_PARAMETER") preferred: MicSource): Flow<AudioFrame> = emptyFlow()

    fun stopCapture() {}

    fun restart() {
        Log.d("MicStreamManager", "[Watchdog] Restarting BLE mic capture")
    }

    fun isAlive(): Boolean = true

    companion object {
        fun create(
            @Suppress("UNUSED_PARAMETER") context: Context,
            @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
            @Suppress("UNUSED_PARAMETER") telemetryRepository: BleTelemetryRepository,
        ): MicStreamManager = MicStreamManager()
    }
}
