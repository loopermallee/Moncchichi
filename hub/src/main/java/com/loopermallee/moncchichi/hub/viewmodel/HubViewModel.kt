package com.loopermallee.moncchichi.hub.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.hub.data.db.AssistantMessage
import com.loopermallee.moncchichi.hub.data.db.AssistantRole
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.handlers.AiAssistHandler
import com.loopermallee.moncchichi.hub.handlers.BleCommandHandler
import com.loopermallee.moncchichi.hub.handlers.BleDebugHandler
import com.loopermallee.moncchichi.hub.handlers.DeviceStatusHandler
import com.loopermallee.moncchichi.hub.handlers.LiveFeedHandler
import com.loopermallee.moncchichi.hub.handlers.SubtitleHandler
import com.loopermallee.moncchichi.hub.handlers.TransitHandler
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.router.Route
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.SpeechTool
import com.loopermallee.moncchichi.hub.tools.TtsTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val MAX_HISTORY = 60

class HubViewModel(
    private val router: IntentRouter,
    private val ble: BleTool,
    private val speech: SpeechTool,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val memory: MemoryRepository,
    private val perms: PermissionTool,
    private val tts: TtsTool,
    private val prefs: SharedPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        val voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        _state.update { it.copy(assistant = it.assistant.copy(voiceEnabled = voiceEnabled)) }
    }

    init {
        refreshPermissionsState()
        viewModelScope.launch {
            val recent = memory.lastConsoleLines(100)
            if (recent.isNotEmpty()) {
                _state.update { it.copy(consoleLines = recent.reversed()) }
            }
        }
        viewModelScope.launch {
            val history = memory.assistantHistory(MAX_HISTORY)
            if (history.isNotEmpty()) {
                val lastAssistant = history.lastOrNull { it.role == AssistantRole.ASSISTANT }
                _state.update {
                    it.copy(
                        assistant = it.assistant.copy(
                            history = history,
                            lastResponse = lastAssistant?.text
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (state.value.device.isConnected) {
                    val result = runCatching { ble.send("PING") }
                    result.onSuccess { resp ->
                        hubAddLog("[BLE] ❤️ Keepalive → $resp")
                    }
                    result.onFailure { err ->
                        hubAddLog("[ERROR] Keepalive failed: ${err.message ?: "unknown"}")
                    }
                }
            }
        }
    }

    fun post(event: AppEvent) = viewModelScope.launch {
        when (event) {
            is AppEvent.StartScan -> startScanFlow()
            is AppEvent.StopScan -> ble.stopScan()
            is AppEvent.Connect -> connectFlow(event.deviceId)
            is AppEvent.Disconnect -> disconnectFlow()
            is AppEvent.SendBleCommand -> commandFlow(event.command)
            is AppEvent.UserSaid -> handleTranscript(event.transcript)
            is AppEvent.AssistantAsk -> assistantAsk(event.text)
            AppEvent.AssistantStartListening -> startListeningFlow()
            AppEvent.AssistantStopListening -> stopListeningFlow()
            is AppEvent.AssistantVoiceToggle -> setVoiceEnabled(event.enabled)
            AppEvent.RequestRequiredPermissions -> {
                perms.requestAll()
                refreshPermissionsState()
            }
        }
    }

    fun refreshPermissionsState() {
        _state.update { it.copy(permissionsOk = perms.areAllGranted()) }
    }

    private suspend fun startScanFlow() = hubLog("Scanning…") {
        var connected = false
        ble.scanDevices { d ->
            _state.update {
                it.copy(
                    device = it.device.copy(name = d.name, id = d.id, rssi = d.rssi, isConnected = it.device.isConnected)
                )
            }
            hubAddLog("[SCAN] ${d.name ?: "Unnamed"} (${d.id}) rssi=${d.rssi}")
            if (!connected) {
                connected = true
                viewModelScope.launch { ble.stopScan() }
                viewModelScope.launch { connectFlow(d.id) }
            }
        }
    }

    private suspend fun connectFlow(deviceId: String) = hubLog("Connecting…") {
        val ok = ble.connect(deviceId)
        val battery = if (ok) ble.battery() else null
        _state.update {
            val current = it.device
            it.copy(device = current.copy(isConnected = ok, id = deviceId, battery = battery))
        }
        if (ok) {
            recordAssistantReply("Connected to $deviceId", offline = false, speak = false)
        }
        hubAddLog(if (ok) "Connected to $deviceId" else "Connection failed")
    }

    private suspend fun disconnectFlow() = hubLog("Disconnecting…") {
        ble.disconnect()
        _state.update { it.copy(device = DeviceInfo()) }
        recordAssistantReply("Disconnected", offline = false, speak = false)
        hubAddLog("Disconnected")
    }

    private suspend fun commandFlow(command: String) = hubLog("Cmd: $command") {
        val resp = ble.send(command)
        val summary = "[BLE] → $command | ← $resp"
        hubAddLog(summary)
        recordAssistantReply(summary, offline = false, speak = false)
    }

    private suspend fun startListeningFlow() {
        _state.update {
            it.copy(assistant = it.assistant.copy(isListening = true, partialTranscript = null))
        }
        speech.startListening(
            onPartial = { partial ->
                viewModelScope.launch {
                    _state.update {
                        it.copy(assistant = it.assistant.copy(partialTranscript = partial))
                    }
                }
            },
            onFinal = { final ->
                viewModelScope.launch {
                    _state.update {
                        it.copy(assistant = it.assistant.copy(isListening = false, partialTranscript = null))
                    }
                    if (final.isNotBlank()) {
                        post(AppEvent.UserSaid(final))
                    }
                }
            }
        )
    }

    private suspend fun stopListeningFlow() {
        speech.stopListening()
        _state.update {
            it.copy(assistant = it.assistant.copy(isListening = false, partialTranscript = null))
        }
    }

    private suspend fun handleTranscript(text: String) {
        appendAssistantMessage(AssistantRole.USER, text)
        _state.update { it.copy(assistant = it.assistant.copy(isListening = false)) }
        routeAndHandle(text)
    }

    private suspend fun assistantAsk(text: String) {
        _state.update { it.copy(assistant = it.assistant.copy(isBusy = true)) }
        appendAssistantMessage(AssistantRole.USER, text)
        routeAndHandle(text)
        _state.update { it.copy(assistant = it.assistant.copy(isBusy = false)) }
    }

    private suspend fun routeAndHandle(text: String) {
        val respond: (String, Boolean, Boolean) -> Unit = { response, offline, speak ->
            if (response.isNotBlank()) {
                recordAssistantReply(response, offline, speak)
            }
        }
        when (router.route(text)) {
            Route.DEVICE_STATUS -> DeviceStatusHandler.run(
                ble,
                display,
                memory,
                state.value.device.isConnected,
                { respond(it, false, false) },
                ::hubAddLog
            )
            Route.COMMAND_CONTROL -> BleCommandHandler.run(text, ble, display, { respond(it, false, false) }, ::hubAddLog)
            Route.LIVE_FEED -> LiveFeedHandler.run(ble, display, memory, { respond(it, false, false) }, ::hubAddLog)
            Route.SUBTITLES -> SubtitleHandler.run(text, display, memory, { respond(it, false, false) }, ::hubAddLog)
            Route.AI_ASSISTANT -> AiAssistHandler.run(
                text,
                buildContext(),
                llm,
                display,
                onAssistant = { reply -> respond(reply.text, !reply.isOnline, true) },
                log = ::hubAddLog
            )
            Route.TRANSIT -> TransitHandler.run(text, display, { respond(it, false, true) }, ::hubAddLog)
            Route.BLE_DEBUG -> BleDebugHandler.run(text, ble, { respond(it, false, false) }, ::hubAddLog)
            Route.UNKNOWN -> {
                val msg = "Not sure. Try 'battery status' or 'turn off right lens'."
                display.showLines(listOf(msg))
                respond(msg, false, false)
                hubAddLog("[Router] UNKNOWN → $text")
            }
        }
    }

    private suspend fun hubLog(header: String, block: suspend () -> Unit) {
        hubAddLog(header)
        block()
    }

    private fun hubAddLog(line: String) {
        val stamped = "[${timestamp()}] $line"
        _state.update { st ->
            val newLines = (st.consoleLines + stamped).takeLast(400)
            st.copy(consoleLines = newLines)
        }
        viewModelScope.launch { memory.addConsoleLine(stamped) }
    }

    fun logSystemEvent(message: String) {
        hubAddLog(message)
    }

    private fun appendAssistantMessage(role: AssistantRole, text: String, persist: Boolean = true) {
        val message = AssistantMessage(role, text, System.currentTimeMillis())
        _state.update { st ->
            val history = (st.assistant.history + message).takeLast(MAX_HISTORY)
            val assistantPane = st.assistant.copy(
                history = history,
                lastTranscript = if (role == AssistantRole.USER) text else st.assistant.lastTranscript,
                lastResponse = if (role == AssistantRole.ASSISTANT) text else st.assistant.lastResponse
            )
            st.copy(assistant = assistantPane)
        }
        if (persist) {
            viewModelScope.launch { memory.addAssistantMessage(role, text) }
        }
    }

    private fun recordAssistantReply(text: String, offline: Boolean, speak: Boolean) {
        appendAssistantMessage(AssistantRole.ASSISTANT, text)
        _state.update {
            it.copy(assistant = it.assistant.copy(isOffline = offline))
        }
        if (speak && tts.isReady() && state.value.assistant.voiceEnabled) {
            speakReply(text)
        }
    }

    private fun speakReply(text: String) {
        tts.speak(text)
        _state.update { it.copy(assistant = it.assistant.copy(isSpeaking = true)) }
        val duration = min(6_000L, max(2_000L, text.length * 40L))
        viewModelScope.launch {
            delay(duration)
            _state.update { it.copy(assistant = it.assistant.copy(isSpeaking = false)) }
        }
    }

    private fun buildContext(): List<LlmTool.Message> {
        return state.value.assistant.history.takeLast(12).map { entry ->
            LlmTool.Message(
                role = when (entry.role) {
                    AssistantRole.SYSTEM -> LlmTool.Role.SYSTEM
                    AssistantRole.USER -> LlmTool.Role.USER
                    AssistantRole.ASSISTANT -> LlmTool.Role.ASSISTANT
                },
                content = entry.text
            )
        }
    }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun setVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
        _state.update { it.copy(assistant = it.assistant.copy(voiceEnabled = enabled)) }
        if (!enabled) {
            tts.stop()
            _state.update { it.copy(assistant = it.assistant.copy(isSpeaking = false)) }
        }
    }

    companion object {
        private const val KEY_VOICE_ENABLED = "assistant_voice_enabled"
    }
}
