package com.loopermallee.moncchichi.hub.tools

interface SpeechTool {
    suspend fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit
    )
    suspend fun stopListening()
}
