package com.loopermallee.moncchichi.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ApiKeyValidator(private val client: OkHttpClient) {
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        if (key.isBlank() || !key.startsWith("sk-")) {
            return@withContext false
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $key")
            .build()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }
}
