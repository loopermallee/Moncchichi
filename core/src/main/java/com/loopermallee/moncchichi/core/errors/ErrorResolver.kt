package com.loopermallee.moncchichi.core.errors

enum class ErrorAction { RETRY, RECONNECT_DEVICE, OPEN_MIC_PERMS, NONE }

data class UiError(
    val title: String,
    val detail: String,
    val action: ErrorAction = ErrorAction.NONE
)

object ErrorResolver {
    fun fromSpeech(code: Int): UiError = when (code) {
        1 -> UiError(
            title = "Mic permission denied",
            detail = "Please enable microphone access in Settings.",
            action = ErrorAction.OPEN_MIC_PERMS
        )
        3 -> UiError(
            title = "Audio timeout",
            detail = "Device mic not responding. Try reconnecting your glasses.",
            action = ErrorAction.RECONNECT_DEVICE
        )
        9 -> UiError(
            title = "Audio quality issue",
            detail = "Input too noisy or overflow detected. Move to a quieter area and retry.",
            action = ErrorAction.RETRY
        )
        else -> UiError(
            title = "Assistant error",
            detail = "Unexpected issue (code $code). Try again.",
            action = ErrorAction.RETRY
        )
    }
}
