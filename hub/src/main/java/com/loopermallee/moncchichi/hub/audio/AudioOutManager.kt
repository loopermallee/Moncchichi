package com.loopermallee.moncchichi.hub.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository

/** Audio output targets supported by the hub. */
enum class AudioSink {
    GLASSES,
    WEARABLE,
    PHONE,
}

/**
 * Coordinates audio output routing for the hub. The manager keeps the active sink in sync with the
 * stored user preference and exposes it as a [StateFlow] so UI elements react instantly to changes.
 *
 * Actual routing into Android's audio stack will arrive in a future patch, but the class ensures the
 * configuration surface is already functional and persistence-ready.
 */
class AudioOutManager(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _sink = MutableStateFlow(SettingsRepository.getAudioSink())
    val sink: StateFlow<AudioSink> = _sink.asStateFlow()

    init {
        scope.launch {
            SettingsRepository.audioSinkFlow.collect { preferred ->
                val previous = _sink.value
                if (previous != preferred) {
                    _sink.value = preferred
                    crossfade(previous, preferred)
                    routeTo(preferred)
                }
            }
        }
    }

    fun setSink(newSink: AudioSink) {
        if (_sink.value == newSink) return
        _sink.value = newSink
        SettingsRepository.setAudioSink(newSink)
        routeTo(newSink)
    }

    private fun routeTo(@Suppress("UNUSED_PARAMETER") sink: AudioSink) {
        // TODO: Integrate with Android AudioManager / TTS routing in a follow-up patch.
    }

    suspend fun crossfade(@Suppress("UNUSED_PARAMETER") old: AudioSink, @Suppress("UNUSED_PARAMETER") new: AudioSink, @Suppress("UNUSED_PARAMETER") durationMs: Long = 100) {
        // Placeholder for smooth transition between sinks.
    }
}
