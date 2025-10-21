package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import com.loopermallee.moncchichi.hub.tools.LlmTool

class LlmToolImpl(@Suppress("UNUSED_PARAMETER") context: Context) : LlmTool {
    override suspend fun answer(prompt: String): String {
        return "Stubbed response for: $prompt"
    }
}
