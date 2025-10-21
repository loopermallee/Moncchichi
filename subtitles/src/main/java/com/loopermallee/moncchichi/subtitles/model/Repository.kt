package com.loopermallee.moncchichi.subtitles.model

import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.loopermallee.moncchichi.client.G1ServiceClient
import com.loopermallee.moncchichi.client.G1ServiceCommon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val recognizer: Recognizer
) {
    class DisplayService internal constructor(
        val connectionState: StateFlow<G1ConnectionState>,
        private val rssiFlow: StateFlow<Int?>,
    ) {
        fun getRssiFlow(): StateFlow<Int?> = rssiFlow
    }
    data class State(
        val hubInstalled: Boolean = true,
        val glasses: G1ServiceCommon.Glasses? = null,
        val started: Boolean = false,
        val listening: Boolean = false
    )

    sealed interface Event {
        object RecognitionError : Event
        data class SpeechRecognized(val text: List<String>) : Event
    }

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()
    private val writableEvents = MutableSharedFlow<Event>()
    val events = writableEvents.asSharedFlow()
    private val connectionStateFlow = MutableStateFlow(G1ConnectionState.DISCONNECTED)
    private val rssiFlow = MutableStateFlow<Int?>(null)
    val displayService = DisplayService(connectionStateFlow.asStateFlow(), rssiFlow.asStateFlow())

    init {
        writableState.value = State(
            hubInstalled = try {
                applicationContext.packageManager.getPackageInfo("com.loopermallee.moncchichi", 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        )
    }

    //

    private lateinit var viewModelScope: CoroutineScope
    private var recognizerStateJob: Job? = null
    private var recognizerEventJob: Job? = null

    //

    private lateinit var service: G1ServiceClient

    fun initializeSpeechRecognizer(coroutineScope: CoroutineScope) {
        recognizer.create(coroutineScope)
        recognizerEventJob = coroutineScope.launch {
            recognizer.events.collect {
                writableEvents.emit(when {
                    it == Recognizer.Event.Error -> Event.RecognitionError
                    it is Recognizer.Event.Heard -> Event.SpeechRecognized(it.text)
                    else -> Event.RecognitionError
                })
            }
        }
        recognizerStateJob = coroutineScope.launch {
            recognizer.state.collect {
                writableState.value = state.value.copy(
                    started = it.started,
                    listening = it.listening
                )
            }
        }
    }

    fun destroySpeechRecognizer() {
        recognizerStateJob?.cancel()
        recognizerStateJob = null
        recognizerEventJob?.cancel()
        recognizerEventJob = null
        recognizer.destroy()
    }

    fun startRecognition(coroutineScope: CoroutineScope) {
        viewModelScope = coroutineScope
        viewModelScope.launch {
            recognizer.start()
        }
    }

    fun stopRecognition() {
        viewModelScope.launch {
            stopDisplaying()
        }
        recognizer.stop()
    }

    fun bindService(coroutineScope: CoroutineScope): Boolean {
        service = G1ServiceClient.open(applicationContext) ?: run {
            connectionStateFlow.value = G1ConnectionState.DISCONNECTED
            return false
        }
        connectionStateFlow.value = G1ConnectionState.CONNECTING
        coroutineScope.launch {
            service.state.collect {
                val glasses = it?.glasses ?: emptyList()
                connectionStateFlow.value = resolveConnectionState(glasses)
                writableState.value = state.value.copy(
                    glasses = glasses.firstOrNull { glass -> glass.status == G1ServiceCommon.GlassesStatus.CONNECTED }
                )
            }
        }
        return true
    }

    fun unbindService() {
        connectionStateFlow.value = G1ConnectionState.DISCONNECTED
        rssiFlow.value = null
        service.close()
    }

    private fun resolveConnectionState(glasses: List<G1ServiceCommon.Glasses>): G1ConnectionState {
        val previous = connectionStateFlow.value
        val anyConnected = glasses.any { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
        if (anyConnected) {
            return G1ConnectionState.CONNECTED
        }
        val anyDisconnecting = glasses.any { it.status == G1ServiceCommon.GlassesStatus.DISCONNECTING || it.status == G1ServiceCommon.GlassesStatus.ERROR }
        if (anyDisconnecting) {
            return G1ConnectionState.RECONNECTING
        }
        val anyConnecting = glasses.any { it.status == G1ServiceCommon.GlassesStatus.CONNECTING }
        if (anyConnecting) {
            return G1ConnectionState.CONNECTING
        }
        return if (previous == G1ConnectionState.CONNECTED || previous == G1ConnectionState.RECONNECTING) {
            G1ConnectionState.RECONNECTING
        } else {
            G1ConnectionState.DISCONNECTED
        }
    }

    suspend fun displayText(text: List<String>): Boolean {
        val connectedGlasses = state.value.glasses
        val glassesId = connectedGlasses?.id
        if (glassesId == null) {
            Log.w(TAG, "displayText: no connected glasses available")
            return false
        }
        return service.displayFormattedPage(
            glassesId,
            G1ServiceCommon.FormattedPage(
                lines = text.map { G1ServiceCommon.FormattedLine(text = it, justify = G1ServiceCommon.JustifyLine.LEFT) },
                justify = G1ServiceCommon.JustifyPage.BOTTOM
            ),
        )
    }

    suspend fun stopDisplaying(): Boolean {
        val connectedGlasses = state.value.glasses
        val glassesId = connectedGlasses?.id
        if (glassesId == null) {
            Log.w(TAG, "stopDisplaying: no connected glasses available")
            return false
        }
        return service.stopDisplaying(glassesId)
    }

    private companion object {
        private const val TAG = "SubtitlesRepository"
    }
}