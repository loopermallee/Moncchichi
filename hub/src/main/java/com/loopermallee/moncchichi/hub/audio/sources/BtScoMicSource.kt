package com.loopermallee.moncchichi.hub.audio.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val SCO_SAMPLE_RATE = 16_000

class BtScoMicSource(
    context: Context,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshAvailability()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshAvailability()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refreshAvailability()
    }

    private val _availability = MutableStateFlow(isScoRouteAvailable())
    val availability: StateFlow<Boolean> = _availability.asStateFlow()

    private val _frames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<AudioFrame> = _frames.asSharedFlow()

    private val _metrics = MutableStateFlow(
        MicMetrics(
            source = MicSource.WEARABLE,
            sampleRateHz = SCO_SAMPLE_RATE,
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
        val available = isScoRouteAvailable() && hasAudioPermission()
        _availability.value = available
    }

    fun start() {
        if (readJob != null) return
        refreshAvailability()
        if (!_availability.value) {
            logger("[MIC] Wearable mic unavailable")
            return
        }
        try {
            audioManager.startBluetoothSco()
            audioManager.setBluetoothScoOn(true)
        } catch (t: Throwable) {
            logger("[MIC] Failed to start SCO: ${t.message}")
        }
        readJob = scope.launch {
            withContext(dispatcher) { startRecordingInternal() }
        }
    }

    fun stop() {
        readJob?.cancel(); readJob = null
        releaseRecorder()
        try {
            audioManager.stopBluetoothSco()
            audioManager.setBluetoothScoOn(false)
        } catch (t: Throwable) {
            logger("[MIC] Failed to stop SCO: ${t.message}")
        }
        window.clear()
        _metrics.value = MicMetrics(
            source = MicSource.WEARABLE,
            sampleRateHz = SCO_SAMPLE_RATE,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        )
    }

    private suspend fun startRecordingInternal() {
        val minBuffer = AudioRecord.getMinBufferSize(SCO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            logger("[MIC] Wearable mic unsupported sample rate")
            return
        }
        val bufferSize = maxOf(minBuffer, SCO_SAMPLE_RATE / 10)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SCO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            logger("[MIC] Wearable mic init failed: state=${audioRecord.state}")
            audioRecord.release()
            return
        }
        record = audioRecord
        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            logger("[MIC] Wearable mic start failed: ${t.message}")
            audioRecord.release()
            record = null
            return
        }
        val buffer = ShortArray(bufferSize)
        while (coroutineContext.isActive) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                logger("[MIC] Wearable mic read failed: ${t.message}")
                break
            }
            if (read <= 0) {
                continue
            }
            val pcm = buffer.copyOf(read)
            val frame = AudioFrame(
                pcm = pcm,
                sampleRateHz = SCO_SAMPLE_RATE,
                timestampNanos = System.nanoTime(),
            )
            _frames.emit(frame)
            val now = SystemClock.elapsedRealtime()
            window += now
            prune(now)
            _metrics.value = MicMetrics(
                source = MicSource.WEARABLE,
                sampleRateHz = SCO_SAMPLE_RATE,
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
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun isScoRouteAvailable(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun releaseRecorder() {
        record?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        record = null
    }
}
