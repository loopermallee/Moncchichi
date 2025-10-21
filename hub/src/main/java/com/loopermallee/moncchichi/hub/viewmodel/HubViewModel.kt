package com.loopermallee.moncchichi.hub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.router.Route
import com.loopermallee.moncchichi.hub.handlers.AiAssistHandler
import com.loopermallee.moncchichi.hub.handlers.BleDebugHandler
import com.loopermallee.moncchichi.hub.handlers.CommandControlHandler
import com.loopermallee.moncchichi.hub.handlers.DeviceStatusHandler
import com.loopermallee.moncchichi.hub.handlers.LiveFeedHandler
import com.loopermallee.moncchichi.hub.handlers.SubtitleHandler
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.SpeechTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HubViewModel(
    private val router: IntentRouter,
    private val ble: BleTool,
    @Suppress("unused") private val speech: SpeechTool,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val memory: MemoryRepository,
    private val perms: PermissionTool
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        refreshPermissionsState()
        viewModelScope.launch {
            val recent = memory.lastConsoleLines(100)
            if (recent.isNotEmpty()) {
                _state.update { it.copy(consoleLines = recent.reversed()) }
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
            is AppEvent.RequestRequiredPermissions -> {
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
            updateAssistantResponse("Connected to $deviceId")
        }
        hubAddLog(if (ok) "Connected to $deviceId" else "Connection failed")
    }

    private suspend fun disconnectFlow() = hubLog("Disconnecting…") {
        ble.disconnect()
        _state.update { it.copy(device = DeviceInfo()) }
        updateAssistantResponse("Disconnected")
        hubAddLog("Disconnected")
    }

    private suspend fun commandFlow(command: String) = hubLog("Cmd: $command") {
        val resp = ble.send(command)
        val summary = "[BLE] → $command | ← $resp"
        hubAddLog(summary)
        updateAssistantResponse(summary)
        viewModelScope.launch { memory.addAssistantResponse(summary) }
    }

    private suspend fun handleTranscript(text: String) {
        _state.update { it.copy(assistant = it.assistant.copy(lastTranscript = text, isListening = false)) }
        routeAndHandle(text)
    }

    private suspend fun assistantAsk(text: String) {
        _state.update { it.copy(assistant = it.assistant.copy(isBusy = true, lastTranscript = text)) }
        routeAndHandle(text)
        _state.update { it.copy(assistant = it.assistant.copy(isBusy = false)) }
    }

    private suspend fun routeAndHandle(text: String) {
        val update: (String) -> Unit = { response ->
            if (response.isNotBlank()) {
                updateAssistantResponse(response)
                viewModelScope.launch { memory.addAssistantResponse(response) }
            }
        }
        when (router.route(text)) {
            Route.DEVICE_STATUS -> DeviceStatusHandler.run(ble, display, memory, state.value.device.isConnected, update, ::hubAddLog)
            Route.COMMAND_CONTROL -> CommandControlHandler.run(text, ble, display, memory, update, ::hubAddLog)
            Route.LIVE_FEED -> LiveFeedHandler.run(ble, display, memory, update, ::hubAddLog)
            Route.SUBTITLES -> SubtitleHandler.run(text, display, memory, update, ::hubAddLog)
            Route.AI_ASSISTANT -> AiAssistHandler.run(text, llm, display, memory, update, ::hubAddLog)
            Route.BLE_DEBUG -> BleDebugHandler.run(text, ble, update, ::hubAddLog)
            Route.UNKNOWN -> {
                val msg = "Not sure. Try 'battery status' or 'turn off right lens'."
                display.showLines(listOf(msg))
                update(msg)
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

    private fun updateAssistantResponse(text: String) {
        _state.update { it.copy(assistant = it.assistant.copy(lastResponse = text)) }
    }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
