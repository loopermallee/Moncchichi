package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.preference.PreferenceManager
import com.loopermallee.moncchichi.hub.tools.LlmTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "LlmToolImpl"
private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val DEFAULT_MODEL = "gpt-3.5-turbo"

class LlmToolImpl(private val context: Context) : LlmTool {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun answer(prompt: String, context: List<LlmTool.Message>): LlmTool.Reply {
        val key = prefs.getString("openai_api_key", null)?.takeIf { it.isNotBlank() }
        if (key.isNullOrBlank() || !isNetworkAvailable()) {
            return LlmFallback.respond(prompt, context)
        }

        return runCatching { queryOpenAi(prompt, context, key) }
            .onFailure { Log.w(TAG, "Falling back to offline reply", it) }
            .getOrElse { LlmFallback.respond(prompt, context) }
    }

    private suspend fun queryOpenAi(
        prompt: String,
        history: List<LlmTool.Message>,
        apiKey: String
    ): LlmTool.Reply = withContext(Dispatchers.IO) {
        val messagesJson = JSONArray().apply {
            history.forEach { message ->
                put(JSONObject().apply {
                    put("role", message.role.asApiRole())
                    put("content", message.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val payload = JSONObject().apply {
            put("model", prefs.getString("openai_model", DEFAULT_MODEL))
            put("messages", messagesJson)
            prefs.getString("openai_temperature", null)?.toDoubleOrNull()?.let { temp ->
                put("temperature", temp)
            }
        }

        val connection = (URL(OPENAI_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: $responseBody")
            }

            val text = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            LlmTool.Reply(text.ifBlank { prompt }, isOnline = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun LlmTool.Role.asApiRole(): String = when (this) {
        LlmTool.Role.SYSTEM -> "system"
        LlmTool.Role.USER -> "user"
        LlmTool.Role.ASSISTANT -> "assistant"
    }

    private fun isNetworkAvailable(): Boolean {
        val active = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    init {
        // Warm up connectivity callbacks for quicker offline detection.
        connectivity?.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {}
        )
    }
}
