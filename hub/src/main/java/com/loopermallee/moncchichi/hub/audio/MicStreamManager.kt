package com.loopermallee.moncchichi.hub.audio

import android.content.Context
import android.util.Log
import com.loopermallee.moncchichi.hub.audio.sources.BleMicSourceAdapter
import com.loopermallee.moncchichi.hub.audio.sources.BtScoMicSource
import com.loopermallee.moncchichi.hub.audio.sources.PhoneMicSource
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AudioFrame(
    val pcm: ShortArray,
    val sampleRateHz: Int = 16_000,
    val timestampNanos: Long,
)

data class MicMetrics(
    val source: MicSource,
    val sampleRateHz: Int,
    val framesPerSec: Int,
    val gapCount: Int,
    val lastGapMs: Int,
    val rssiAvg: Int?,
    val packetLossPct: Float?,
)

interface MicStreamManager {
    fun startCapture(preferred: MicSource): Flow<AudioFrame>
    fun stopCapture()
    val availability: Flow<Map<MicSource, Boolean>>
    val metrics: Flow<MicMetrics>

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            telemetryRepository: BleTelemetryRepository,
            logger: (String) -> Unit = { message -> Log.d("MicStreamManager", message) },
        ): MicStreamManager {
            val bleSource = BleMicSourceAdapter(telemetryRepository, scope, logger)
            val scoSource = BtScoMicSource(context, scope, logger)
            val phoneSource = PhoneMicSource(context, scope, logger)
            return DefaultMicStreamManager(scope, bleSource, scoSource, phoneSource, logger)
        }
    }
}

private class DefaultMicStreamManager(
    private val scope: CoroutineScope,
    private val bleSource: BleMicSourceAdapter,
    private val scoSource: BtScoMicSource,
    private val phoneSource: PhoneMicSource,
    private val logger: (String) -> Unit,
) : MicStreamManager {

    private val started = AtomicBoolean(false)
    private val defaultSampleRate = 16_000
    private val frames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _metrics = MutableStateFlow(
        MicMetrics(
            source = MicSource.GLASSES,
            sampleRateHz = defaultSampleRate,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        ),
    )

    private val preferredOverride = MutableStateFlow<MicSource?>(null)

    private val storedPreference: StateFlow<MicSource> = SettingsRepository.micSourceFlow
        .stateIn(scope, SharingStarted.Eagerly, SettingsRepository.getMicSource())

    private val preferredSource: StateFlow<MicSource> = combine(storedPreference, preferredOverride) { stored, override ->
        override ?: stored
    }.stateIn(scope, SharingStarted.Eagerly, SettingsRepository.getMicSource())

    private val availabilityState: StateFlow<Map<MicSource, Boolean>> = combine(
        bleSource.availability,
        scoSource.availability,
        phoneSource.availability,
    ) { glasses, wearable, phone ->
        mapOf(
            MicSource.GLASSES to glasses,
            MicSource.WEARABLE to wearable,
            MicSource.PHONE to phone,
        )
    }
        .onStart { emit(mapOf(MicSource.GLASSES to false, MicSource.WEARABLE to false, MicSource.PHONE to false)) }
        .stateIn(scope, SharingStarted.Eagerly, mapOf(
            MicSource.GLASSES to false,
            MicSource.WEARABLE to false,
            MicSource.PHONE to phoneSource.availability.value,
        ))

    override val availability: Flow<Map<MicSource, Boolean>> = availabilityState
    override val metrics: Flow<MicMetrics> = _metrics

    private var routingJob: Job? = null
    private var frameJob: Job? = null
    private var metricJob: Job? = null
    private var activeSource: MicSource? = null

    override fun startCapture(preferred: MicSource): Flow<AudioFrame> {
        preferredOverride.value = preferred
        if (started.compareAndSet(false, true)) {
            startRouting()
        }
        return frames
    }

    override fun stopCapture() {
        if (!started.compareAndSet(true, false)) {
            preferredOverride.value = null
            return
        }
        routingJob?.cancel(); routingJob = null
        frameJob?.cancel(); frameJob = null
        metricJob?.cancel(); metricJob = null
        when (activeSource) {
            MicSource.GLASSES -> bleSource.stop()
            MicSource.WEARABLE -> scoSource.stop()
            MicSource.PHONE -> phoneSource.stop()
            null -> Unit
        }
        activeSource = null
        _metrics.value = MicMetrics(
            source = MicSource.PHONE,
            sampleRateHz = 0,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        )
        preferredOverride.value = null
    }

    private fun startRouting() {
        routingJob = scope.launch {
            combine(preferredSource, availabilityState) { preferred, availabilityMap ->
                resolveSource(preferred, availabilityMap)
            }
                .distinctUntilChanged()
                .collect { next ->
                    switchSource(next)
                }
        }
    }

    private fun resolveSource(preferred: MicSource, availabilityMap: Map<MicSource, Boolean>): MicSource? {
        val fallbackOrder = listOf(MicSource.GLASSES, MicSource.WEARABLE, MicSource.PHONE)
        val priority = buildList {
            add(preferred)
            fallbackOrder.filterNot { it == preferred }.forEach { add(it) }
        }
        return priority.firstOrNull { availabilityMap[it] == true }
    }

    private fun switchSource(target: MicSource?) {
        if (activeSource == target) return
        frameJob?.cancel(); frameJob = null
        metricJob?.cancel(); metricJob = null
        when (activeSource) {
            MicSource.GLASSES -> bleSource.stop()
            MicSource.WEARABLE -> scoSource.stop()
            MicSource.PHONE -> phoneSource.stop()
            null -> Unit
        }
        activeSource = target
        if (target == null) {
            _metrics.value = MicMetrics(
                source = MicSource.PHONE,
                sampleRateHz = 0,
                framesPerSec = 0,
                gapCount = 0,
                lastGapMs = 0,
                rssiAvg = null,
                packetLossPct = null,
            )
            logger("[MIC] No microphone source available")
            return
        }
        val (framesFlow, metricsFlow) = when (target) {
            MicSource.GLASSES -> {
                bleSource.start()
                bleSource.frames to bleSource.metrics
            }
            MicSource.WEARABLE -> {
                scoSource.start()
                scoSource.frames to scoSource.metrics
            }
            MicSource.PHONE -> {
                phoneSource.start()
                phoneSource.frames to phoneSource.metrics
            }
        }
        frameJob = scope.launch {
            framesFlow.collect { frame -> frames.emit(frame) }
        }
        metricJob = scope.launch {
            metricsFlow.collect { stats -> _metrics.value = stats }
        }
        logger("[MIC] Switched to ${target.name}")
    }
}
