package com.loopermallee.moncchichi.hub.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Audio output targets supported by the hub. */
enum class AudioSink {
    GLASSES,
    WEARABLE,
    PHONE,
}

/**
 * Minimal compile-time stub for audio output routing. It exposes the API surface used elsewhere in
 * the app but defers the actual implementation to a future patch.
 */
class AudioOutManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {

    private val _currentSink = MutableStateFlow(AudioSink.GLASSES)
    val currentSink: StateFlow<AudioSink> = _currentSink.asStateFlow()

    fun setSink(sink: AudioSink) {
        _currentSink.value = sink
    }

    fun routeTts(@Suppress("UNUSED_PARAMETER") text: String) {
        // No-op for now.
    }

    fun release() {
        // No-op for now.
    }
}
