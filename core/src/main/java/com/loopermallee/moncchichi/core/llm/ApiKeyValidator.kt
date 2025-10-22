package com.loopermallee.moncchichi.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ApiKeyValidator {
    private val client = OkHttpClient()

    private val KEY_PATTERN = Regex(
        pattern = """^sk-(proj-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{20,})[A-Za-z0-9_-]*$"""
    )

    suspend fun validate(key: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            if (!KEY_PATTERN.matches(key.trim())) {
                return@withContext false to "Invalid format"
            }
            val requestBuilder = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $key")
            val request = requestBuilder.build()
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> true to null
                        401 -> false to "Invalid or expired key"
                        403 -> false to "Forbidden – check project permissions"
                        else -> false to "Server error ${response.code}"
                    }
                }
            } catch (e: Exception) {
                false to "Connection error: ${e.message ?: "unknown"}"
            }
        }
}
