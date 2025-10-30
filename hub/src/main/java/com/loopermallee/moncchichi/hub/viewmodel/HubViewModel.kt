package com.loopermallee.moncchichi.hub.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.core.errors.ErrorAction
import com.loopermallee.moncchichi.core.errors.UiError
import com.loopermallee.moncchichi.core.llm.ModelCatalog
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.AssistantConnState
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnState
import com.loopermallee.moncchichi.core.model.ChatMessage
import com.loopermallee.moncchichi.core.model.MessageOrigin
import com.loopermallee.moncchichi.core.model.MessageSource
import com.loopermallee.moncchichi.hub.assistant.OfflineAssistant
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.diagnostics.DiagnosticRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
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
import com.loopermallee.moncchichi.hub.tools.ScanResult
import com.loopermallee.moncchichi.hub.tools.TtsTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.text.RegexOption

private const val MAX_HISTORY = 60
private const val MAX_OFFLINE_QUEUE = 10
private const val SCAN_PAIR_TIMEOUT_SECONDS = 10

class HubViewModel(
    private val router: IntentRouter,
    private val ble: BleTool,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val memory: MemoryRepository,
    private val perms: PermissionTool,
    private val tts: TtsTool,
    private val prefs: SharedPreferences,
    private val diagnostics: DiagnosticRepository,
    private val telemetry: BleTelemetryRepository,
) : ViewModel() {
    private var lastTelemetryDigest: String? = null
    private var scanCountdownJob: Job? = null
    private var activeScanContext: PairScanContext? = null

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _assistantConn = MutableStateFlow(
        AssistantConnInfo(AssistantConnState.OFFLINE, model = null)
    )
    val assistantConn: StateFlow<AssistantConnInfo> = _assistantConn.asStateFlow()

    private val _deviceConn = MutableStateFlow(DeviceConnInfo(DeviceConnState.DISCONNECTED))
    val deviceConn: StateFlow<DeviceConnInfo> = _deviceConn.asStateFlow()

    private val _uiError = MutableStateFlow<UiError?>(null)
    val uiError: StateFlow<UiError?> = _uiError.asStateFlow()

    private val offlineQueue = ArrayDeque<String>()
    private var offlineAnnouncementShown = false

    init {
        val voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        _state.update { it.copy(assistant = it.assistant.copy(voiceEnabled = voiceEnabled)) }
        refreshAssistantStatus()
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
            val history = memory.chatHistory(MAX_HISTORY)
            if (history.isNotEmpty()) {
                val lastAssistant = history.lastOrNull { it.source == MessageSource.ASSISTANT }
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
            telemetry.uartText.collect { line ->
                val lensTag = if (line.lens == Lens.LEFT) "L" else "R"
                val msg = line.text
                val category = when {
                    msg.startsWith("+i", true) -> "SYSTEM"
                    msg.startsWith("ver", true) || msg.contains("DeviceID", true) -> "TELEMETRY"
                    msg.equals("OK", true) -> "COMMAND"
                    msg.startsWith("ERR", true) -> "ERROR"
                    else -> "BLE"
                }
                hubAddLog("[BLE][$category][$lensTag] $msg")
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
                        hubAddLog("[BLE] ⚠️ Keepalive failed: ${err.message ?: "unknown"}")
                    }
                }
            }
        }
    }

    fun post(event: AppEvent) = viewModelScope.launch {
        when (event) {
            is AppEvent.StartScan -> startScanFlow()
            is AppEvent.StopScan -> {
                scanCountdownJob?.cancel()
                scanCountdownJob = null
                activeScanContext = null
                ble.stopScan()
                hideScanBanner()
            }
            is AppEvent.Connect -> connectFlow(event.deviceId)
            is AppEvent.Disconnect -> disconnectFlow()
            is AppEvent.SendBleCommand -> commandFlow(event.command)
            is AppEvent.UserSaid -> handleTranscript(event.transcript)
            is AppEvent.AssistantAsk -> assistantAsk(event.text)
            AppEvent.RequestRequiredPermissions -> {
                perms.requestAll()
                refreshPermissionsState()
            }
            AppEvent.ClearConsole -> clearConsole()
        }
    }

    fun refreshPermissionsState() {
        _state.update { it.copy(permissionsOk = perms.areAllGranted()) }
    }

    private suspend fun clearConsole() {
        memory.clearConsole()
        _state.update { it.copy(consoleLines = emptyList()) }
    }

    private suspend fun startScanFlow() = hubLog("Scanning…") {
        scanCountdownJob?.cancel()
        scanCountdownJob = null
        hideScanBanner()
        val context = PairScanContext()
        activeScanContext = context
        context.remainingSeconds = SCAN_PAIR_TIMEOUT_SECONDS
        publishScanState(context, forceStage = ScanStage.Searching)

        val countdownJob = viewModelScope.launch {
            val jobRef = coroutineContext[Job]
            var remaining = SCAN_PAIR_TIMEOUT_SECONDS
            while (isActive && remaining >= 0) {
                context.remainingSeconds = remaining
                publishScanState(context)
                delay(1_000)
                remaining--
            }
            if (!context.isComplete) {
                context.remainingSeconds = 0
                publishScanState(context, timedOut = true)
                runCatching { ble.stopScan() }
                activeScanContext = null
            }
            if (scanCountdownJob == jobRef) {
                scanCountdownJob = null
            }
        }
        scanCountdownJob = countdownJob

        ble.scanDevices { d ->
            _state.update {
                it.copy(
                    device = it.device.copy(
                        name = d.name,
                        id = d.id,
                        isConnected = it.device.isConnected
                    )
                )
            }
            updateDeviceStatus(state.value.device)

            val metadata = mapScanResult(d)
            val accepted = context.registerDetection(metadata)
            if (accepted) {
                publishScanState(context)
            }

            val stageLabel = if (accepted) {
                state.value.scan.title.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val logMessage = buildString {
                append("[SCAN] ${d.name ?: "Unnamed"} (${d.id}) rssi=${d.rssi}")
                if (!metadata.displayName.isNullOrBlank()) {
                    append(" • ${metadata.displayName}")
                }
                stageLabel?.let { append(" • $it") }
            }
            hubAddLog(logMessage)

            if (accepted && !context.connectionLaunched) {
                context.connectionLaunched = true
                viewModelScope.launch { connectFlow(d.id) }
            }

            if (accepted && context.isComplete) {
                scanCountdownJob?.cancel()
                scanCountdownJob = null
                publishScanState(context, forceStage = ScanStage.BothDetected)
                viewModelScope.launch { ble.stopScan() }
            }
        }
    }

    private suspend fun connectFlow(deviceId: String) = hubLog("Connecting…") {
        val ok = ble.connect(deviceId)
        _state.update { st ->
            val current = st.device
            st.copy(
                device = current.copy(
                    isConnected = ok,
                    id = deviceId,
                    glassesBattery = null,
                    caseBattery = null,
                    firmwareVersion = null,
                    signalRssi = null,
                )
            )
        }
        updateDeviceStatus(state.value.device)
        if (ok) {
            val label = "Connected to ${state.value.device.name ?: "My G1"}"
            hubAddLog(label)
            markScanConnected()
            refreshDeviceVitals()
        } else {
            hubAddLog("Connection failed")
            markScanFailed()
        }
    }

    private suspend fun disconnectFlow() = hubLog("Disconnecting…") {
        ble.disconnect()
        _state.update { it.copy(device = DeviceInfo()) }
        updateDeviceStatus(state.value.device)
        hubAddLog("Disconnected")
        lastTelemetryDigest = null
    }

    private suspend fun commandFlow(command: String) = hubLog("Cmd: $command") {
        val resp = ble.send(command)
        val summary = "[BLE] → $command | ← $resp"
        hubAddLog(summary)
    }

    private suspend fun refreshDeviceVitals() {
        if (!state.value.device.isConnected) return
        val battery = runCatching { ble.battery() }.getOrNull()
        val case = runCatching { ble.caseBattery() }.getOrNull()
        val firmware = runCatching { ble.firmware() }.getOrNull()
        val signal = runCatching { ble.signal() }.getOrNull()
        val mac = runCatching { ble.macAddress() }.getOrNull()
        applyDeviceTelemetry(
            battery = battery,
            case = case,
            firmware = firmware,
            signal = signal,
            deviceId = mac,
            connectionState = "CONNECTED",
        )
        val parts = buildList {
            battery?.let { add("Glasses ${it}%") }
            case?.let { add("Case ${it}%") }
            firmware?.let { add("FW $it") }
            signal?.let { add("Signal ${it} dBm") }
        }
        val digest = parts.joinToString(separator = " | ")
        if (digest.isNotEmpty() && digest != lastTelemetryDigest) {
            hubAddLog("[BLE] Telemetry • $digest")
            lastTelemetryDigest = digest
        }
    }

    private fun markScanConnected() {
        val context = activeScanContext ?: return
        scanCountdownJob?.cancel()
        scanCountdownJob = null
        context.remainingSeconds = 0
        publishScanState(context, forceStage = ScanStage.Connected)
        activeScanContext = null
        viewModelScope.launch { ble.stopScan() }
    }

    private fun markScanFailed() {
        val context = activeScanContext ?: return
        scanCountdownJob?.cancel()
        scanCountdownJob = null
        context.remainingSeconds = 0
        publishScanState(context, forceStage = ScanStage.Timeout)
        activeScanContext = null
    }

    private fun hideScanBanner() {
        _state.update { it.copy(scan = ScanStatus()) }
    }

    private fun publishScanState(
        context: PairScanContext,
        timedOut: Boolean = false,
        forceStage: ScanStage? = null,
    ) {
        val stage = forceStage ?: when {
            timedOut -> ScanStage.Timeout
            context.isComplete -> ScanStage.BothDetected
            context.leftDetected || context.rightDetected -> ScanStage.WaitingForCompanion
            else -> ScanStage.Searching
        }
        val countdown = context.remainingSeconds.coerceAtLeast(0)
        val awaitingLabel = context.awaitingLabel()
        val displayName = context.displayName()
        val title = when (stage) {
            ScanStage.Searching -> "Scanning for headset"
            ScanStage.WaitingForCompanion -> when {
                context.leftDetected && !context.rightDetected -> "Left lens detected"
                context.rightDetected && !context.leftDetected -> "Right lens detected"
                else -> "Lens detected"
            }
            ScanStage.BothDetected -> "Both lenses ready"
            ScanStage.Connected -> "Headset connected"
            ScanStage.Timeout -> "Companion lens not found"
            ScanStage.Idle -> ""
        }
        val message = when (stage) {
            ScanStage.Searching -> "Looking for both lenses nearby…"
            ScanStage.WaitingForCompanion -> "Searching for the $awaitingLabel — ${countdown}s left"
            ScanStage.BothDetected -> "We found both lenses for $displayName. Finalizing the connection…"
            ScanStage.Connected -> "$displayName is connected and ready."
            ScanStage.Timeout -> "We couldn’t locate the $awaitingLabel. Keep the case open and try again."
            ScanStage.Idle -> ""
        }
        val hint = when (stage) {
            ScanStage.WaitingForCompanion -> "Keep the case open so the $awaitingLabel can wake up."
            ScanStage.Timeout -> "Keep the case open or power cycle the missing lens, then restart the scan."
            else -> null
        }
        val showCountdown = stage == ScanStage.Searching || stage == ScanStage.WaitingForCompanion
        val showSpinner = when (stage) {
            ScanStage.Timeout, ScanStage.Connected, ScanStage.Idle -> false
            else -> true
        }
        _state.update { current ->
            current.copy(
                scan = ScanStatus(
                    isVisible = stage != ScanStage.Idle,
                    stage = stage,
                    title = title,
                    message = message,
                    hint = hint,
                    countdownSeconds = countdown,
                    showCountdown = showCountdown,
                    showSpinner = showSpinner,
                )
            )
        }
    }

    private data class PairScanContext(
        var pairToken: String? = null,
        var friendlyName: String? = null,
        var leftDetected: Boolean = false,
        var rightDetected: Boolean = false,
        var remainingSeconds: Int = SCAN_PAIR_TIMEOUT_SECONDS,
        var connectionLaunched: Boolean = false,
    )

    private enum class CompanionSide { LEFT, RIGHT, UNKNOWN }

    private data class DetectionMetadata(
        val pairToken: String,
        val side: CompanionSide,
        val displayName: String?,
    )

    private fun PairScanContext.registerDetection(metadata: DetectionMetadata): Boolean {
        if (pairToken == null) {
            pairToken = metadata.pairToken
        }
        if (pairToken != metadata.pairToken) {
            return false
        }
        if (friendlyName.isNullOrBlank() && !metadata.displayName.isNullOrBlank()) {
            friendlyName = metadata.displayName
        }
        when (metadata.side) {
            CompanionSide.LEFT -> leftDetected = true
            CompanionSide.RIGHT -> rightDetected = true
            CompanionSide.UNKNOWN -> {
                if (!leftDetected) {
                    leftDetected = true
                } else if (!rightDetected) {
                    rightDetected = true
                }
            }
        }
        return true
    }

    private val PairScanContext.isComplete: Boolean
        get() = leftDetected && rightDetected

    private fun PairScanContext.displayName(): String {
        val raw = when {
            !friendlyName.isNullOrBlank() -> friendlyName
            !pairToken.isNullOrBlank() -> pairToken
            else -> null
        }
        return raw?.let(::formatDisplayName) ?: "your headset"
    }

    private fun PairScanContext.awaitingLabel(): String = when {
        leftDetected && !rightDetected -> "right lens"
        rightDetected && !leftDetected -> "left lens"
        else -> "companion lens"
    }

    private fun mapScanResult(result: ScanResult): DetectionMetadata {
        val rawName = result.name?.takeIf { it.isNotBlank() }
        val sanitized = sanitizePairLabel(rawName)
        val tokenSource = sanitized.ifBlank { rawName ?: result.id }
        val normalizedToken = nonTokenRegex.replace(tokenSource.uppercase(Locale.US), "-").trim('-')
        val token = normalizedToken.ifBlank { result.id.uppercase(Locale.US) }
        val side = inferSide(rawName)
        val displayName = sanitized.takeIf { it.isNotBlank() }?.let(::formatDisplayName)
        return DetectionMetadata(pairToken = token, side = side, displayName = displayName)
    }

    private fun inferSide(label: String?): CompanionSide {
        if (label.isNullOrBlank()) return CompanionSide.UNKNOWN
        val lower = label.lowercase(Locale.US)
        return when {
            lower.startsWith("left ") || lower.startsWith("left-") || lower.startsWith("left_") -> CompanionSide.LEFT
            lower.startsWith("right ") || lower.startsWith("right-") || lower.startsWith("right_") -> CompanionSide.RIGHT
            lower.endsWith(" left") || lower.endsWith("-l") || lower.endsWith("_l") || lower.endsWith(" l") -> CompanionSide.LEFT
            lower.endsWith(" right") || lower.endsWith("-r") || lower.endsWith("_r") || lower.endsWith(" r") -> CompanionSide.RIGHT
            lower == "left" -> CompanionSide.LEFT
            lower == "right" -> CompanionSide.RIGHT
            else -> CompanionSide.UNKNOWN
        }
    }

    private fun sanitizePairLabel(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val withoutPrefix = stripSidePrefix(value.trim())
        val withoutSuffix = stripSideSuffix(withoutPrefix)
        return withoutSuffix.replace('_', ' ').replace('-', ' ').trim()
    }

    private fun stripSidePrefix(value: String): String {
        val trimmed = value.trim()
        val match = sidePrefixRegex.find(trimmed)
        if (match != null && match.range.first == 0) {
            val endIndex = min(trimmed.length, match.range.last + 1)
            return trimmed.substring(endIndex).trimStart('-', '_', ' ', '\t')
        }
        return trimmed
    }

    private fun stripSideSuffix(value: String): String {
        val trimmed = value.trim()
        val match = sideSuffixRegex.find(trimmed)
        if (match != null && match.range.last == trimmed.length - 1) {
            return trimmed.substring(0, match.range.first).trimEnd('-', '_', ' ', '\t')
        }
        return trimmed
    }

    private fun formatDisplayName(raw: String): String {
        val words = raw.replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return "G1 Headset"
        return words.joinToString(" ") { word ->
            word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString() }
        }
    }

    private val nonTokenRegex = Regex("[^A-Z0-9]+")
    private val sidePrefixRegex = Regex("^(left|right)([-_\\s]+)?", RegexOption.IGNORE_CASE)
    private val sideSuffixRegex = Regex("([-_\\s]+)?(left|right)$", RegexOption.IGNORE_CASE)

    private fun applyDeviceTelemetry(
        battery: Int?,
        case: Int?,
        firmware: String?,
        signal: Int?,
        deviceId: String?,
        connectionState: String? = null,
    ) {
        _state.update { current ->
            val device = current.device
            val updated = device.copy(
                id = deviceId ?: device.id,
                glassesBattery = battery ?: device.glassesBattery,
                caseBattery = case ?: device.caseBattery,
                firmwareVersion = firmware ?: device.firmwareVersion,
                signalRssi = signal ?: device.signalRssi,
                connectionState = connectionState ?: device.connectionState,
            )
            current.copy(device = updated)
        }
        updateDeviceStatus(state.value.device)
    }

    private suspend fun handleTranscript(text: String) {
        assistantAsk(text)
    }

    private suspend fun assistantAsk(text: String) {
        _state.update {
            it.copy(
                assistant = it.assistant.copy(
                    isBusy = true,
                    isThinking = true,
                )
            )
        }
        appendChatMessage(MessageSource.USER, text)
        routeAndHandle(text)
        _state.update { it.copy(assistant = it.assistant.copy(isBusy = false, isThinking = false)) }
    }

    private suspend fun routeAndHandle(text: String) {
        // Use the stable 4-parameter responder to maintain compatibility with existing handlers.
        val respond: (String, Boolean, Boolean, String?) -> Unit =
            { response, offline, speak, error ->
                if (response.isNotBlank()) {
                    recordAssistantReply(response, offline, speak, error)
                }
            }
        when (router.route(text)) {
            Route.DEVICE_STATUS -> DeviceStatusHandler.run(
                ble,
                display,
                memory,
                state.value.device.isConnected,
                { respond(it, false, false, null) },
                ::hubAddLog,
                onTelemetry = { glasses, case, firmware, signal ->
                    applyDeviceTelemetry(
                        battery = glasses,
                        case = case,
                        firmware = firmware,
                        signal = signal,
                        deviceId = null,
                        connectionState = if (state.value.device.isConnected) "CONNECTED" else "DISCONNECTED",
                    )
                }
            )
            Route.COMMAND_CONTROL -> BleCommandHandler.run(text, ble, display, { respond(it, false, false, null) }, ::hubAddLog)
            Route.LIVE_FEED -> LiveFeedHandler.run(ble, display, memory, { respond(it, false, false, null) }, ::hubAddLog)
            Route.SUBTITLES -> SubtitleHandler.run(text, display, memory, { respond(it, false, false, null) }, ::hubAddLog)
            Route.AI_ASSISTANT -> AiAssistHandler.run(
                text,
                buildContext(),
                llm,
                display,
                onAssistant = { reply ->
                    if (reply.isOnline) {
                        respond(reply.text, false, true, reply.errorMessage)
                    } else {
                        enqueueOfflinePrompt(text)
                        viewModelScope.launch {
                            val diagnostic = OfflineAssistant.generateResponse(
                                prompt = text,
                                state = state.value,
                                diagnostics = diagnostics,
                                pendingQueries = offlineQueue.size,
                            )
                            respond(diagnostic, true, true, reply.errorMessage)
                        }
                    }
                },
                log = ::hubAddLog
            )
            Route.TRANSIT -> TransitHandler.run(text, display, { respond(it, false, true, null) }, ::hubAddLog)
            Route.BLE_DEBUG -> BleDebugHandler.run(text, ble, { respond(it, false, false, null) }, ::hubAddLog)
            Route.UNKNOWN -> {
                val msg = "Not sure. Try 'battery status' or 'turn off right lens'."
                display.showLines(listOf(msg))
                respond(msg, false, false, null)
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

    private fun appendChatMessage(
        source: MessageSource,
        text: String,
        persist: Boolean = true,
        forceOfflinePrefix: Boolean = false,
        origin: MessageOrigin = MessageOrigin.LLM,
    ) {
        val adjustedText = if (
            source == MessageSource.ASSISTANT &&
            (forceOfflinePrefix || _assistantConn.value.state != AssistantConnState.ONLINE) &&
            text.trimStart().startsWith("🛑").not()
        ) {
            "🛑 $text"
        } else {
            text
        }
        val message = ChatMessage(adjustedText, source, System.currentTimeMillis(), origin)
        _state.update { st ->
            val history = (st.assistant.history + message).takeLast(MAX_HISTORY)
            val assistantPane = st.assistant.copy(
                history = history,
                lastTranscript = if (source == MessageSource.USER) text else st.assistant.lastTranscript,
                lastResponse = if (source == MessageSource.ASSISTANT) adjustedText else st.assistant.lastResponse,
                isThinking = if (source == MessageSource.ASSISTANT) false else st.assistant.isThinking,
            )
            st.copy(assistant = assistantPane)
        }
        if (persist) {
            viewModelScope.launch { memory.addChatMessage(source, adjustedText, origin) }
        }
    }

    private fun enqueueOfflinePrompt(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        if (offlineQueue.contains(trimmed)) return
        if (offlineQueue.size >= MAX_OFFLINE_QUEUE) {
            offlineQueue.removeFirst()
        }
        offlineQueue.addLast(trimmed)
    }

    private fun maybeAnnounceOffline() {
        if (offlineAnnouncementShown) return
        offlineAnnouncementShown = true
        appendChatMessage(
            MessageSource.ASSISTANT,
            "We are offline. Reverting back to fallback — Beep boop offline!",
            forceOfflinePrefix = true,
            origin = MessageOrigin.OFFLINE,
        )
    }

    private fun recordAssistantReply(
        text: String,
        offline: Boolean,
        speak: Boolean,
        errorMessage: String? = null,
    ) {
        val isDiagnostic = text.startsWith("Assistant 🟣") || text.contains("[Status]")
        val origin = when {
            isDiagnostic -> MessageOrigin.DEVICE
            offline -> MessageOrigin.OFFLINE
            else -> MessageOrigin.LLM
        }

        if (offline) {
            maybeAnnounceOffline()
        }

        appendChatMessage(
            MessageSource.ASSISTANT,
            text,
            forceOfflinePrefix = offline,
            origin = origin,
        )

        if (origin == MessageOrigin.DEVICE) {
            hubAddLog("[DIAG] ${text.replace('\n', ' ')}")
        }

        _state.update {
            it.copy(assistant = it.assistant.copy(isOffline = offline))
        }
        updateAssistantStatus(offline, errorMessage, autoReport = !offline)

        if (!errorMessage.isNullOrBlank()) {
            _uiError.value = UiError("Assistant error", errorMessage, ErrorAction.NONE)
        } else {
            _uiError.value = null
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

    fun refreshAssistantStatus(forceOnline: Boolean = false) {
        val key = prefs.getString("openai_api_key", null)
        val missingKey = key.isNullOrBlank()
        val offline = when {
            missingKey -> true
            forceOnline -> false
            else -> state.value.assistant.isOffline
        }
        _state.update {
            it.copy(
                assistant = it.assistant.copy(
                    isOffline = offline
                )
            )
        }
        updateAssistantStatus(offline, error = null)
    }

    private fun updateAssistantStatus(offline: Boolean, error: String?, autoReport: Boolean = true) {
        val key = prefs.getString("openai_api_key", null)
        val missingKey = key.isNullOrBlank()
        val previous = _assistantConn.value
        val updated = when {
            offline -> AssistantConnInfo(
                state = AssistantConnState.FALLBACK,
                model = null,
                reason = error?.takeIf { it.isNotBlank() } ?: "No network connectivity",
            )
            !error.isNullOrBlank() -> AssistantConnInfo(
                state = AssistantConnState.ERROR,
                model = null,
                reason = error
            )
            missingKey -> AssistantConnInfo(
                state = AssistantConnState.OFFLINE,
                model = null,
                reason = "Disabled – add API key"
            )
            else -> AssistantConnInfo(
                state = AssistantConnState.ONLINE,
                model = prefs.getString("openai_model", ModelCatalog.defaultModel())?.ifBlank { null }
                    ?: ModelCatalog.defaultModel()
            )
        }
        _assistantConn.value = updated
        val stateChanged =
            previous.state != updated.state || previous.reason != updated.reason || previous.model != updated.model

        val becameOfflineState =
            updated.state == AssistantConnState.FALLBACK || updated.state == AssistantConnState.OFFLINE
        val isFallbackOffline = updated.state == AssistantConnState.FALLBACK

        if (offline && isFallbackOffline && !offlineAnnouncementShown) {
            maybeAnnounceOffline()
        }

        if (autoReport && stateChanged && isFallbackOffline) {
            viewModelScope.launch {
                val prompt = state.value.assistant.lastTranscript.orEmpty()
                val report = OfflineAssistant.generateResponse(
                    prompt = prompt,
                    state = state.value,
                    diagnostics = diagnostics,
                    pendingQueries = offlineQueue.size,
                )
                appendChatMessage(
                    MessageSource.ASSISTANT,
                    report,
                    origin = MessageOrigin.OFFLINE,
                    forceOfflinePrefix = true,
                )
            }
        }

        val cameOnline = previous.state != AssistantConnState.ONLINE && updated.state == AssistantConnState.ONLINE
        if (cameOnline) {
            offlineAnnouncementShown = false
            OfflineAssistant.resetSession()
            val queued = offlineQueue.toList()
            offlineQueue.clear()
            appendChatMessage(
                MessageSource.ASSISTANT,
                "I’m back online ✅ and ready to continue.",
                origin = MessageOrigin.LLM,
            )
            queued.take(MAX_OFFLINE_QUEUE).forEachIndexed { index, prompt ->
                viewModelScope.launch {
                    if (index > 0) delay(400L * index)
                    post(AppEvent.AssistantAsk(prompt))
                }
            }
        }
    }

    private fun updateDeviceStatus(device: DeviceInfo) {
        _deviceConn.value = if (device.isConnected) {
            DeviceConnInfo(
                state = DeviceConnState.CONNECTED,
                deviceName = device.name ?: "My G1",
                deviceId = device.id,
                batteryPct = device.glassesBattery?.takeIf { it in 0..100 },
                caseBatteryPct = device.caseBattery?.takeIf { it in 0..100 },
                firmware = device.firmwareVersion,
                signalRssi = device.signalRssi,
                connectionState = device.connectionState,
            )
        } else {
            DeviceConnInfo(DeviceConnState.DISCONNECTED)
        }
    }

    fun filteredAssistantHistory(): List<ChatMessage> =
        state.value.assistant.history.filter { message ->
            message.source == MessageSource.USER || message.source == MessageSource.ASSISTANT
        }

    fun handleBluetoothOff() {
        _state.update { st ->
            val current = st.device
            st.copy(
                device = current.copy(
                    isConnected = false,
                    glassesBattery = null,
                    caseBattery = null,
                    firmwareVersion = null,
                    signalRssi = null,
                    connectionState = "DISCONNECTED",
                )
            )
        }
        updateDeviceStatus(state.value.device)
        hubAddLog("[BLE] Bluetooth off – keepalive paused")
    }

    fun handleBluetoothOn() {
        hubAddLog("[BLE] Bluetooth on – awaiting device connection")
    }

    private fun pushUiError(error: UiError, updateAssistant: Boolean) {
        _uiError.value = error
        if (updateAssistant) {
            updateAssistantStatus(offline = true, error = error.title)
        }
    }

    fun dismissUiError() {
        _uiError.value = null
        if (_assistantConn.value.state == AssistantConnState.ERROR) {
            updateAssistantStatus(offline = state.value.assistant.isOffline, error = null)
        }
    }

    fun retryLastAssistant() {
        state.value.assistant.lastTranscript?.let { prompt ->
            post(AppEvent.AssistantAsk(prompt))
        }
    }

    fun requestDeviceReconnect() {
        post(AppEvent.Disconnect)
        viewModelScope.launch {
            delay(300)
            post(AppEvent.StartScan)
        }
    }

    private fun buildContext(): List<LlmTool.Message> =
        filteredAssistantHistory().takeLast(12).map { entry ->
            LlmTool.Message(
                role = when (entry.source) {
                    MessageSource.SYSTEM -> LlmTool.Role.SYSTEM
                    MessageSource.USER -> LlmTool.Role.USER
                    MessageSource.ASSISTANT -> LlmTool.Role.ASSISTANT
                    MessageSource.BLE -> LlmTool.Role.SYSTEM
                },
                content = entry.text
            )
        }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val KEY_VOICE_ENABLED = "assistant_voice_enabled"
    }
}
