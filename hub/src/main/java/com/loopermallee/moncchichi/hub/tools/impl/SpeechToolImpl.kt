package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.loopermallee.moncchichi.hub.tools.SpeechTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SpeechToolImpl(context: Context) : SpeechTool {

    private val appContext = context.applicationContext
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
    private var partialCallback: ((String) -> Unit)? = null
    private var finalCallback: ((String) -> Unit)? = null
    private var errorCallback: ((Int) -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            partialCallback?.invoke("…listening…")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            errorCallback?.invoke(error)
        }

        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            finalCallback?.invoke(text)
        }

        override fun onPartialResults(partialResults: Bundle) {
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                partialCallback?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        recognizer.setRecognitionListener(listener)
    }

    override suspend fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onFinal("Speech recognition unavailable")
            return
        }
        partialCallback = onPartial
        finalCallback = onFinal
        errorCallback = onError

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        withContext(Dispatchers.Main) {
            recognizer.startListening(intent)
        }
    }

    override suspend fun stopListening() {
        withContext(Dispatchers.Main) {
            recognizer.stopListening()
        }
    }
}
