package com.loopermallee.moncchichi.hub.audio.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.hub.audio.AudioFrame
import com.loopermallee.moncchichi.hub.audio.MicMetrics
import com.loopermallee.moncchichi.hub.audio.MicSource
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

class PhoneMicSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _availability = MutableStateFlow(hasAudioPermission())
    val availability: StateFlow<Boolean> = _availability.asStateFlow()

    private val _frames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<AudioFrame> = _frames.asSharedFlow()

    private val _metrics = MutableStateFlow(
        MicMetrics(
            source = MicSource.PHONE,
            sampleRateHz = SAMPLE_RATE,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        ),
    )
    val metrics: StateFlow<MicMetrics> = _metrics.asStateFlow()

    private var record: AudioRecord? = null
    private var readJob: Job? = null
    private val window = ArrayDeque<Long>()

    fun refreshAvailability() {
        _availability.value = hasAudioPermission()
    }

    fun start() {
        if (readJob != null) return
        if (!hasAudioPermission()) {
            logger("[MIC] Phone mic unavailable - RECORD_AUDIO denied")
            _availability.value = false
            return
        }
        _availability.value = true
        readJob = scope.launch {
            withContext(dispatcher) { startRecordingInternal() }
        }
    }

    fun stop() {
        readJob?.cancel(); readJob = null
        releaseRecorder()
        window.clear()
        _metrics.value = MicMetrics(
            source = MicSource.PHONE,
            sampleRateHz = SAMPLE_RATE,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        )
    }

    private suspend fun startRecordingInternal() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            logger("[MIC] Phone mic unsupported sample rate")
            return
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            bufferSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            logger("[MIC] Phone mic init failed: state=${audioRecord.state}")
            audioRecord.release()
            return
        }
        record = audioRecord
        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            logger("[MIC] Phone mic start failed: ${t.message}")
            audioRecord.release()
            record = null
            return
        }
        val buffer = ShortArray(bufferSize)
        while (isActive) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                logger("[MIC] Phone mic read failed: ${t.message}")
                break
            }
            if (read <= 0) {
                continue
            }
            val pcm = buffer.copyOf(read)
            val frame = AudioFrame(
                pcm = pcm,
                sampleRateHz = SAMPLE_RATE,
                timestampNanos = System.nanoTime(),
            )
            _frames.emit(frame)
            val now = SystemClock.elapsedRealtime()
            window += now
            prune(now)
            _metrics.value = MicMetrics(
                source = MicSource.PHONE,
                sampleRateHz = SAMPLE_RATE,
                framesPerSec = window.size,
                gapCount = 0,
                lastGapMs = 0,
                rssiAvg = null,
                packetLossPct = null,
            )
        }
        releaseRecorder()
    }

    private fun prune(now: Long) {
        while (window.isNotEmpty()) {
            val first = window.first()
            if (now - first > 1_000L) {
                window.removeFirst()
            } else {
                break
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun releaseRecorder() {
        record?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        record = null
    }
}
