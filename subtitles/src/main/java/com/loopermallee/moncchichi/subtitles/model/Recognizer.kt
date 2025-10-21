package com.loopermallee.moncchichi.subtitles.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class Recognizer(private val context: Context) {
    private val listener = object: RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("Recognizer", "onReadyForSpeech(${params})")
            writableState.value = state.value.copy(
                listening = true
            )
        }

        override fun onBeginningOfSpeech() {
            Log.d("Recognizer", "onBeginningOfSpeech()")
        }

        override fun onRmsChanged(rmsdB: Float) {
//            Log.d("Recognizer", "onRmsChanged(${rmsdB})")
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            Log.d("Recognizer", "onBufferReceived(${buffer})")
        }

        override fun onEndOfSpeech() {
            Log.d("Recognizer", "onEndOfSpeech()")
        }

        override fun onError(error: Int) {
            Log.d("Recognizer", "onError(${error})")
            writableEvents.tryEmit(Event.Error)
        }

        override fun onResults(results: Bundle?) {
            Log.d("Recognizer", "onResults(${results})")
            if(state.value.started) {
                speechRecognizer.startListening(speechRecognizerIntent)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.let {
                it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let {
                    if(it.isNotEmpty()) {
                        Log.d("Recognizer", "onPartialResults(${it})")
                        coroutineScope.launch {
                            writableEvents.emit(
                                Event.Heard(it.toList())
                            )
                        }
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d("Recognizer", "onEvent(${eventType}, ${params})")
        }
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent

    //

    data class State(
        val started: Boolean = false,
        val listening: Boolean = false
    )

    sealed interface Event {
        object Error : Event
        data class Heard(
            val text: List<String>
        ) : Event
    }

    private lateinit var coroutineScope: CoroutineScope

    private val writableState = MutableStateFlow(State())
    val state = writableState.asStateFlow()
    private val writableEvents = MutableSharedFlow<Event>()
    val events = writableEvents.asSharedFlow()

    //

    fun create(callerCoroutineScope: CoroutineScope) {
        coroutineScope = callerCoroutineScope
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
            context.packageName
        )
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
        speechRecognizer.setRecognitionListener(listener)
    }

    fun destroy() {
        speechRecognizer.destroy()
    }

    //

    fun start() {
        if(!state.value.started) {
            writableState.value = state.value.copy(started = true, listening = false)
            speechRecognizer.startListening(speechRecognizerIntent)
        }
    }

    fun stop() {
        if(state.value.started) {
            speechRecognizer.stopListening()
            writableState.value = state.value.copy(started = false, listening = false)
        }
    }
}
