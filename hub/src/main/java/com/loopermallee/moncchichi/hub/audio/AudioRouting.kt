package com.loopermallee.moncchichi.hub.audio

/** Mic routing falls back from GLASSES (default) to WEARABLE then PHONE. */
enum class MicSource {
    GLASSES,
    WEARABLE,
    PHONE
}

/** Audio output falls back from GLASSES (default) to WEARABLE then PHONE. */
enum class AudioSink {
    GLASSES,
    WEARABLE,
    PHONE
}
