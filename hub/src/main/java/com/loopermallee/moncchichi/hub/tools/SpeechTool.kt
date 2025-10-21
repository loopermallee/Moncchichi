package com.loopermallee.moncchichi.hub.tools

interface SpeechTool {
    suspend fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit)
    suspend fun stopListening()
}
