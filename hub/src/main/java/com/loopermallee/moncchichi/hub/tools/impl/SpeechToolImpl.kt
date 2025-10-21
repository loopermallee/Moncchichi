package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import com.loopermallee.moncchichi.hub.tools.SpeechTool

class SpeechToolImpl(@Suppress("UNUSED_PARAMETER") context: Context) : SpeechTool {
    override suspend fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        onPartial("Listening stub…")
        onFinal("Voice input not implemented")
    }

    override suspend fun stopListening() {
        // Placeholder implementation
    }
}
