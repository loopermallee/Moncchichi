package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.preference.PreferenceManager
import com.loopermallee.moncchichi.core.llm.ModelCatalog
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
import java.util.Locale

private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val DEFAULT_SYSTEM_PROMPT = """
You are Moncchichi Assistant supporting people wearing G1 smart glasses.
Keep replies conversational, empathetic, and easy to act on.
Reference device telemetry or network state when it helps the person understand what to do next.
Be concise and avoid overloading them with numbers unless they ask for specifics.
If something looks wrong, acknowledge it kindly and suggest the next gentle step.
""".trimIndent()
private val DEFAULT_MODEL = ModelCatalog.defaultModel()

class LlmToolImpl(private val context: Context) : LlmTool {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun answer(prompt: String, context: List<LlmTool.Message>): LlmTool.Reply {
        val key = prefs.getString("openai_api_key", null)?.takeIf { it.isNotBlank() }
        if (key.isNullOrBlank()) {
            return LlmTool.Reply(
                text = "⚠️ API key missing – please add it in Settings.",
                isOnline = false,
                errorMessage = "OpenAI API key missing"
            )
        }
        val activeMode = if (isNetworkAvailable()) "online" else "offline"
        if (activeMode != "online") {
            return LlmFallback.respond(prompt, context).copy(errorMessage = "No network connectivity")
        }

        return runCatching { queryOpenAi(prompt, context, key, activeMode) }
            .getOrElse { throwable ->
                LlmFallback.respond(prompt, context).copy(errorMessage = readableError(throwable))
            }
    }

    private suspend fun queryOpenAi(
        prompt: String,
        history: List<LlmTool.Message>,
        apiKey: String,
        activeMode: String,
    ): LlmTool.Reply = withContext(Dispatchers.IO) {
        val messagesJson = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", buildSystemPrompt(activeMode, history))
            })
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

            LlmTool.Reply(text.ifBlank { prompt }, isOnline = true, errorMessage = null)
        } finally {
            connection.disconnect()
        }
    }

    private fun readableError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("401") -> "Invalid API key"
            message.contains("429") -> "Rate limit reached"
            message.contains("403") -> "API access forbidden"
            message.contains("400") -> "Bad request – check model or parameters"
            message.contains("404") -> "Requested model unavailable"
            error is java.net.SocketTimeoutException -> "Request timed out"
            error is java.net.UnknownHostException -> "No network connectivity"
            else -> "Assistant request failed"
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

    private fun buildSystemPrompt(activeMode: String, history: List<LlmTool.Message>): String {
        val modeLabel = activeMode.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
        val snippets = history
            .filter { it.role != LlmTool.Role.SYSTEM }
            .takeLast(4)
            .joinToString(separator = "\n") { message ->
                val roleLabel = when (message.role) {
                    LlmTool.Role.USER -> "User"
                    LlmTool.Role.ASSISTANT -> "Assistant"
                    LlmTool.Role.SYSTEM -> "System"
                }
                val cleaned = message.content
                    .replace("\n", " ")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { text -> if (text.length > 180) text.take(177) + "…" else text }
                    ?: "(no content)"
                "• $roleLabel: $cleaned"
            }
            .ifBlank { "• No prior context provided." }

        return buildString {
            append(DEFAULT_SYSTEM_PROMPT)
            append('\n')
            append('\n')
            append("Active assistant mode: $modeLabel.")
            append('\n')
            append("Recent context:")
            append('\n')
            append(snippets)
        }.trim()
    }

    init {
        // Warm up connectivity callbacks for quicker offline detection.
        connectivity?.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {}
        )
    }
}
