package com.loopermallee.moncchichi.hub.tools

interface LlmTool {
    suspend fun answer(prompt: String): String
}
