package com.loopermallee.moncchichi.hub.viewmodel

import android.content.SharedPreferences
import com.loopermallee.moncchichi.hub.audio.AudioSink
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
import com.loopermallee.moncchichi.hub.ui.scanner.LensChipState
import com.loopermallee.moncchichi.hub.ui.scanner.LensConnectionPhase
import com.loopermallee.moncchichi.hub.ui.scanner.PairingProgress
import com.loopermallee.moncchichi.hub.ui.scanner.ScanBannerState
import com.loopermallee.moncchichi.hub.ui.scanner.ScanStage
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val MAX_HISTORY = 60
private const val MAX_OFFLINE_QUEUE = 10
private const val PAIR_DISCOVERY_TIMEOUT_MS = 12_000L

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

    private val _permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<PermissionRequest> = _permissionRequests.asSharedFlow()

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
            telemetry.events.collect { event ->
                hubAddLog(event)
            }
        }
        viewModelScope.launch {
            ble.events.collect { event ->
                when (event) {
                    BleTool.Event.ConnectionFailed -> {
                        hubAddLog("[BLE] Connection failed twice – showing troubleshoot tips")
                        _state.update { it.copy(showTroubleshootDialog = true) }
                    }
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
                ble.stopScan()
                clearScanningState()
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

    fun requestAudioPermission() {
        viewModelScope.launch {
            _permissionRequests.emit(PermissionRequest.RecordAudio)
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        val status = if (granted) "granted" else "denied"
        hubAddLog("[PERMS] RECORD_AUDIO $status")
    }

    fun setAudibleResponsesEnabled(enabled: Boolean) {
        SettingsRepository.setAudibleResponsesEnabled(enabled)
        hubAddLog("[VOICE] Audible responses ${if (enabled) "enabled" else "disabled"}")
    }

    fun setPreferPhoneMicEnabled(enabled: Boolean) {
        SettingsRepository.setPreferPhoneMicEnabled(enabled)
        hubAddLog("[VOICE] Prefer phone mic ${if (enabled) "enabled" else "disabled"}")
    }

    fun setAudioSink(sink: AudioSink) {
        SettingsRepository.setAudioSink(sink)
        hubAddLog("[VOICE] Output sink → ${sink.name}")
    }

    private suspend fun clearConsole() {
        memory.clearConsole()
        _state.update { it.copy(consoleLines = emptyList()) }
    }

    private suspend fun startScanFlow() = hubLog("Scanning…") {
        val connectLaunched = AtomicBoolean(false)
        val discoveries = mutableMapOf<String, PairDiscovery>()
        val timeoutJobs = mutableMapOf<String, Job>()
        val countdowns = mutableMapOf<String, Int>()

        _state.update { st ->
            st.copy(
                device = st.device.copy(
                    name = null,
                    id = null,
                    isConnected = false,
                    glassesBattery = null,
                    caseBattery = null,
                    firmwareVersion = null,
                    signalRssi = null,
                    connectionState = "Scanning for headsets…",
                ),
                scanBanner = ScanBannerState(
                    stage = ScanStage.Searching,
                    headline = "Scanning for headsets",
                    supporting = "Looking for both left and right lenses nearby.",
                    showSpinner = true,
                    tip = "The G1 has left and right radios – we’ll connect to both automatically.",
                ),
                pairingProgress = emptyMap(),
            )
        }
        updateDeviceStatus(state.value.device)

        ble.scanDevices { result ->
            viewModelScope.launch {
                handleScanResult(result, connectLaunched, discoveries, timeoutJobs, countdowns)
            }
        }
    }

    private suspend fun handleScanResult(
        result: ScanResult,
        connectLaunched: AtomicBoolean,
        discoveries: MutableMap<String, PairDiscovery>,
        timeoutJobs: MutableMap<String, Job>,
        countdowns: MutableMap<String, Int>,
    ) {
        val lens = result.lens
        val pairToken = result.pairToken

        if (lens == null) {
            if (connectLaunched.compareAndSet(false, true)) {
                hubAddLog("[SCAN] ${result.name ?: "Unnamed"} (${result.id}) rssi=${result.rssi}")
                _state.update { st ->
                    st.copy(
                        device = st.device.copy(
                            name = result.name,
                            id = result.id,
                            isConnected = false,
                            connectionState = "Connecting…",
                            signalRssi = result.rssi,
                        ),
                        scanBanner = ScanBannerState(
                            stage = ScanStage.Connecting,
                            headline = "Connecting to ${result.name ?: "headset"}",
                            supporting = "Establishing links to both lenses…",
                            showSpinner = true,
                        ),
                        pairingProgress = emptyMap(),
                    )
                }
                updateDeviceStatus(state.value.device)
                ble.stopScan()
                connectFlow(result.id)
            }
            return
        }

        val discovery = discoveries.getOrPut(pairToken) { PairDiscovery(pairToken) }
        val wasNewLens = discovery.record(lens, result)

        if (wasNewLens) {
            val lensLabel = lens.readable()
            hubAddLog("[SCAN] Found $lensLabel lens ${result.name ?: "Unnamed"} (${result.id}) rssi=${result.rssi}")
        }

        val waitingLens = discovery.missingLens()
        val displayName = discovery.displayName() ?: result.name ?: "headset"

        if (waitingLens == null) {
            timeoutJobs.remove(pairToken)?.cancel()
            countdowns.remove(pairToken)
            updatePairingProgress(
                token = pairToken,
                discovery = discovery,
                waitingLens = null,
                remainingSeconds = null,
                stageOverride = ScanStage.Connecting,
            )
            if (connectLaunched.compareAndSet(false, true)) {
                val label = displayName.ifBlank { "headset" }
                hubAddLog("[SCAN] $label ready – attempting dual connect")
                _state.update { st ->
                    st.copy(
                        device = st.device.copy(
                            connectionState = "Connecting to $label…",
                        ),
                        scanBanner = ScanBannerState(
                            stage = ScanStage.Connecting,
                            headline = "Connecting to $label",
                            supporting = "Pairing both lenses now…",
                            showSpinner = true,
                        ),
                    )
                }
                updateDeviceStatus(state.value.device)
                ble.stopScan()
                val connectId = discovery.primaryId() ?: result.id
                hubAddLog("[SCAN] Connecting companion lens links")
                connectFlow(connectId)
            }
            return
        }

        val remainingSeconds = countdowns.getOrPut(pairToken) { secondsForTimeout() }
        updatePairingProgress(pairToken, discovery, waitingLens, remainingSeconds)

        if (wasNewLens && timeoutJobs.containsKey(pairToken).not()) {
            timeoutJobs[pairToken] = launchCountdownJob(
                pairToken = pairToken,
                discoveries = discoveries,
                connectLaunched = connectLaunched,
                countdowns = countdowns,
                timeoutJobs = timeoutJobs,
            )
        }
    }

    private data class PairDiscovery(
        val token: String,
        val ids: MutableMap<Lens, String> = mutableMapOf(),
        val names: MutableMap<Lens, String?> = mutableMapOf(),
        val rssis: MutableMap<Lens, Int> = mutableMapOf(),
    ) {
        fun record(lens: Lens, result: ScanResult): Boolean {
            val isNew = ids.containsKey(lens).not()
            ids[lens] = result.id
            names[lens] = result.name
            rssis[lens] = result.rssi
            return isNew
        }

        fun missingLens(): Lens? = Lens.values().firstOrNull { !ids.containsKey(it) }

        fun isComplete(): Boolean = missingLens() == null

        fun primaryId(): String? = ids[Lens.LEFT] ?: ids[Lens.RIGHT]

        fun displayName(): String? {
            val raw = names.values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
            val cleaned = raw.replace(Regex("\\s+(left|right)$", RegexOption.IGNORE_CASE), "").trim()
            return cleaned.ifBlank { raw }
        }

        fun strongestRssi(): Int? = rssis.values.maxOrNull()
    }

    private fun Lens.readable(): String = when (this) {
        Lens.LEFT -> "left"
        Lens.RIGHT -> "right"
    }

    private fun secondsForTimeout(): Int = (PAIR_DISCOVERY_TIMEOUT_MS / 1000).toInt()

    private fun launchCountdownJob(
        pairToken: String,
        discoveries: MutableMap<String, PairDiscovery>,
        connectLaunched: AtomicBoolean,
        countdowns: MutableMap<String, Int>,
        timeoutJobs: MutableMap<String, Job>,
    ): Job = viewModelScope.launch {
        try {
            var remaining = countdowns[pairToken] ?: secondsForTimeout()
            while (remaining >= 0 && isActive) {
                val pending = discoveries[pairToken] ?: break
                if (pending.isComplete() || connectLaunched.get()) {
                    break
                }
                countdowns[pairToken] = remaining
                updatePairingProgress(pairToken, pending, pending.missingLens(), remaining)
                delay(1_000)
                remaining--
            }
            countdowns.remove(pairToken)
            val pending = discoveries[pairToken] ?: return@launch
            if (pending.isComplete() || connectLaunched.get()) {
                return@launch
            }
            handlePairTimeout(pairToken, pending, pending.missingLens())
        } finally {
            countdowns.remove(pairToken)
            timeoutJobs.remove(pairToken)
        }
    }

    private suspend fun handlePairTimeout(
        pairToken: String,
        pending: PairDiscovery,
        missing: Lens?,
    ) {
        val missingLabel = missing?.readable() ?: "companion"
        val label = pending.displayName()?.ifBlank { "headset" } ?: "headset"
        val tip = tipForMissingLens(missing)
        hubAddLog("[SCAN] Timeout waiting for $missingLabel lens of $label – pairing deferred")
        updatePairingProgress(
            token = pairToken,
            discovery = pending,
            waitingLens = missing,
            remainingSeconds = 0,
            stageOverride = ScanStage.Timeout,
            tipOverride = tip,
            timedOutLens = missing,
        )
        _state.update { st ->
            st.copy(
                device = st.device.copy(
                    name = pending.displayName() ?: st.device.name,
                    id = pending.primaryId(),
                    isConnected = false,
                    signalRssi = pending.strongestRssi(),
                    connectionState = "Timed out waiting for $missingLabel lens",
                )
            )
        }
        updateDeviceStatus(state.value.device)
        ble.stopScan()
    }

    private fun updatePairingProgress(
        token: String,
        discovery: PairDiscovery,
        waitingLens: Lens?,
        remainingSeconds: Int?,
        stageOverride: ScanStage? = null,
        tipOverride: String? = null,
        timedOutLens: Lens? = null,
    ) {
        val displayName = discovery.displayName() ?: "G1 headset"
        val stage = stageOverride ?: when {
            discovery.isComplete() -> ScanStage.Ready
            waitingLens == null -> ScanStage.LensDetected
            else -> ScanStage.WaitingForCompanion
        }
        val banner = buildBannerState(
            stage = stage,
            displayName = displayName,
            waitingLens = waitingLens,
            remainingSeconds = remainingSeconds,
            tip = tipOverride,
        )
        val candidateIds = buildSet {
            add(token.lowercase(Locale.US))
            discovery.primaryId()?.let { add(it.lowercase(Locale.US)) }
            discovery.ids.values.forEach { id -> add(id.lowercase(Locale.US)) }
            val name = displayName.lowercase(Locale.US)
            if (name.isNotBlank()) add(name)
        }
        val leftChip = buildLensChip(
            lens = Lens.LEFT,
            discovery = discovery,
            waitingLens = waitingLens,
            remainingSeconds = remainingSeconds,
            stage = stage,
            timedOutLens = timedOutLens,
            tipOverride = tipOverride,
        )
        val rightChip = buildLensChip(
            lens = Lens.RIGHT,
            discovery = discovery,
            waitingLens = waitingLens,
            remainingSeconds = remainingSeconds,
            stage = stage,
            timedOutLens = timedOutLens,
            tipOverride = tipOverride,
        )
        _state.update { st ->
            val updatedDevice = st.device.copy(
                name = displayName,
                id = discovery.primaryId() ?: st.device.id,
                isConnected = false,
                signalRssi = discovery.strongestRssi(),
                connectionState = connectionStateLabel(stage, waitingLens, remainingSeconds),
            )
            val progress = st.pairingProgress.toMutableMap()
            progress[token] = PairingProgress(
                token = token,
                displayName = displayName,
                stage = stage,
                countdownSeconds = remainingSeconds,
                leftChip = leftChip,
                rightChip = rightChip,
                tip = tipOverride,
                candidateIds = candidateIds,
            )
            st.copy(
                device = updatedDevice,
                scanBanner = banner,
                pairingProgress = progress,
            )
        }
        updateDeviceStatus(state.value.device)
    }

    private fun buildBannerState(
        stage: ScanStage,
        displayName: String,
        waitingLens: Lens?,
        remainingSeconds: Int?,
        tip: String?,
    ): ScanBannerState {
        val label = displayName.ifBlank { "headset" }
        return when (stage) {
            ScanStage.Searching -> ScanBannerState(
                stage = stage,
                headline = "Scanning for headsets",
                supporting = "Looking for both left and right lenses nearby.",
                showSpinner = true,
                tip = tip,
            )

            ScanStage.LensDetected, ScanStage.WaitingForCompanion -> {
                val missingLabel = waitingLens?.readable() ?: "companion"
                val countdownText = remainingSeconds?.let { " — ${it}s left" } ?: ""
                ScanBannerState(
                    stage = stage,
                    headline = "Waiting for $missingLabel lens",
                    supporting = "Found ${waitingLens?.opposite()?.readable() ?: "a"} lens for $label$countdownText.",
                    countdownSeconds = remainingSeconds,
                    showSpinner = true,
                    tip = tip ?: tipForMissingLens(waitingLens),
                )
            }

            ScanStage.Connecting -> {
                val snapshot = telemetry.snapshot.value
                val leftBonded = snapshot.left.bonded
                val rightBonded = snapshot.right.bonded
                val supportingText = when {
                    leftBonded && rightBonded -> "Both lenses bonded – finalizing connection…"
                    leftBonded -> "Pairing right lens…"
                    rightBonded -> "Pairing left lens…"
                    snapshot.leftBondAttempts > 0 || snapshot.rightBondAttempts > 0 -> "Pairing lenses…"
                    else -> "Pairing both lenses now…"
                }
                ScanBannerState(
                    stage = stage,
                    headline = "Connecting to $label",
                    supporting = supportingText,
                    showSpinner = true,
                    tip = tip,
                )
            }

            ScanStage.Ready -> ScanBannerState(
                stage = stage,
                headline = "Both lenses detected",
                supporting = "Preparing to connect to $label…",
                showSpinner = true,
                tip = tip,
            )

            ScanStage.Timeout -> ScanBannerState(
                stage = stage,
                headline = "Missing lens",
                supporting = "Timed out waiting for the ${waitingLens?.readable() ?: "companion"} lens.",
                countdownSeconds = remainingSeconds,
                isWarning = true,
                showSpinner = false,
                tip = tip ?: tipForMissingLens(waitingLens),
            )

            ScanStage.Completed -> ScanBannerState(
                stage = stage,
                headline = "Both lenses ready",
                supporting = "Connected to $label.",
                showSpinner = false,
                tip = tip,
            )

            ScanStage.Idle -> ScanBannerState()
        }
    }

    private fun buildLensChip(
        lens: Lens,
        discovery: PairDiscovery,
        waitingLens: Lens?,
        remainingSeconds: Int?,
        stage: ScanStage,
        timedOutLens: Lens?,
        tipOverride: String?,
    ): LensChipState {
        val titleBase = if (lens == Lens.LEFT) "Left" else "Right"
        val detected = discovery.ids.containsKey(lens)
        val rssi = discovery.rssis[lens]
        val name = discovery.names[lens]
        val snapshot = telemetry.snapshot.value
        val lensTelemetry = if (lens == Lens.LEFT) snapshot.left else snapshot.right
        if (lensTelemetry.reconnecting) {
            return LensChipState(
                title = "$titleBase reconnecting",
                status = LensConnectionPhase.Connecting,
                detail = "Reconnecting…",
            )
        }
        val detailParts = buildList {
            if (!name.isNullOrBlank()) add(name)
            rssi?.let { add("Signal ${it} dBm") }
            val statusDetail = when {
                lensTelemetry.bonded -> "Bonded ✅"
                lensTelemetry.bondAttempts > 0 -> "Pairing…"
                else -> null
            }
            statusDetail?.let { add(it) }
        }
        val detail = detailParts.joinToString(" • ")
        return when {
            timedOutLens == lens -> LensChipState(
                title = "$titleBase retry needed",
                status = LensConnectionPhase.Timeout,
                detail = tipOverride ?: "Keep the case open and tap retry.",
                isWarning = true,
            )

            detected && stage == ScanStage.Connecting -> LensChipState(
                title = "$titleBase detected",
                status = LensConnectionPhase.Connecting,
                detail = detail.ifBlank { "Linking…" },
            )

            detected && (stage == ScanStage.Ready || stage == ScanStage.Completed) -> LensChipState(
                title = "$titleBase connected",
                status = LensConnectionPhase.Connected,
                detail = detail.ifBlank { "Ready" },
            )

            detected -> LensChipState(
                title = "$titleBase detected",
                status = LensConnectionPhase.Connecting,
                detail = detail.ifBlank { "Identified" },
            )

            waitingLens == lens -> {
                val searchingDetail = buildString {
                    append("Searching…")
                    if (remainingSeconds != null) {
                        append(" ${remainingSeconds}s left")
                    }
                }
                LensChipState(
                    title = "$titleBase searching",
                    status = LensConnectionPhase.Searching,
                    detail = searchingDetail,
                )
            }

            else -> LensChipState(
                title = "$titleBase idle",
                status = LensConnectionPhase.Idle,
                detail = "Waiting for signal",
            )
        }
    }

    private fun connectionStateLabel(
        stage: ScanStage,
        waitingLens: Lens?,
        remainingSeconds: Int?,
    ): String = when (stage) {
        ScanStage.WaitingForCompanion, ScanStage.LensDetected -> {
            val missingLabel = waitingLens?.readable() ?: "companion"
            val suffix = remainingSeconds?.let { " – ${it}s left" } ?: ""
            "Waiting for $missingLabel lens$suffix"
        }

        ScanStage.Connecting -> "Connecting…"
        ScanStage.Timeout -> {
            val missingLabel = waitingLens?.readable() ?: "companion"
            "Timed out waiting for $missingLabel lens"
        }
        ScanStage.Completed -> "CONNECTED"
        ScanStage.Ready -> "Preparing to connect"
        ScanStage.Searching -> "Scanning for headsets…"
        ScanStage.Idle -> state.value.device.connectionState ?: "Idle"
    }

    private fun tipForMissingLens(lens: Lens?): String = when (lens) {
        Lens.LEFT -> "Keep the case open and wake the left lens."
        Lens.RIGHT -> "Cycle power on the right lens and keep it near the hub."
        null -> "Ensure both lenses are awake and advertising."
    }

    private fun Lens.opposite(): Lens = when (this) {
        Lens.LEFT -> Lens.RIGHT
        Lens.RIGHT -> Lens.LEFT
    }

    private fun clearScanningState() {
        _state.update { st ->
            if (st.scanBanner.stage == ScanStage.Idle && st.pairingProgress.isEmpty()) {
                st
            } else {
                st.copy(
                    scanBanner = ScanBannerState(),
                    pairingProgress = emptyMap(),
                )
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
            val connectedName = state.value.device.name ?: "My G1"
            hubAddLog("Connected to $connectedName")
            _state.update { st ->
                st.copy(
                    scanBanner = ScanBannerState(
                        stage = ScanStage.Completed,
                        headline = "Both lenses ready",
                        supporting = "Connected to $connectedName.",
                    ),
                    pairingProgress = emptyMap(),
                )
            }
            refreshDeviceVitals()
        } else {
            hubAddLog("Connection failed")
            clearScanningState()
        }
    }

    fun retryScanAndConnect() {
        dismissTroubleshootDialog()
        viewModelScope.launch {
            hubAddLog("[BLE] Troubleshoot retry requested")
            try {
                ble.stopScan()
            } catch (_: Throwable) {
                // Ignore stop scan errors when retrying.
            }
            ble.resetPairingCache()
            clearScanningState()
            try {
                startScanFlow()
            } catch (error: Throwable) {
                hubAddLog("[BLE] Retry scan failed: ${error.message ?: "unknown"}")
            }
        }
    }

    fun openHelpPage(topic: String) {
        dismissTroubleshootDialog()
        hubAddLog("[HELP] Opening support for $topic")
        viewModelScope.launch {
            display.toast("Opening $topic help…")
        }
    }

    fun dismissTroubleshootDialog() {
        _state.update { it.copy(showTroubleshootDialog = false) }
    }

    private suspend fun disconnectFlow() = hubLog("Disconnecting…") {
        ble.disconnect()
        _state.update { it.copy(device = DeviceInfo()) }
        clearScanningState()
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

    sealed interface PermissionRequest {
        data object RecordAudio : PermissionRequest
    }
}
