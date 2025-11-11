package com.loopermallee.moncchichi.hub.ui.developer

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.ble.DashboardDataEncoder
import com.loopermallee.moncchichi.hub.audio.AudioSink
import com.loopermallee.moncchichi.hub.audio.MicSource
import com.loopermallee.moncchichi.hub.audio.MicStreamManager
import com.loopermallee.moncchichi.hub.audio.MicMetrics
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.data.telemetry.LensGestureEvent
import com.loopermallee.moncchichi.hub.ui.developer.DeveloperViewModel.DeveloperEvent
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREF_KEY_MODE = "developer_mode_selection"

class DeveloperViewModel(
    private val appContext: Context,
    private val hubViewModel: HubViewModel,
    private val telemetry: BleTelemetryRepository,
    private val micManager: MicStreamManager,
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    sealed interface DeveloperEvent {
        data class CopyToClipboard(val payload: String) : DeveloperEvent
        data class ShareLogs(val intent: Intent) : DeveloperEvent
        data class Notify(val message: String) : DeveloperEvent
    }

    private val _mode = MutableStateFlow(loadInitialMode())
    val mode: StateFlow<DeveloperMode> = _mode.asStateFlow()

    private val _consoleLines = MutableStateFlow(hubViewModel.state.value.consoleLines)
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    private val _snapshot = MutableStateFlow(telemetry.snapshot.value)
    val snapshot: StateFlow<BleTelemetryRepository.Snapshot> = _snapshot.asStateFlow()

    val caseStatus: StateFlow<BleTelemetryRepository.CaseStatus> = telemetry.caseStatus

    private val _telemetrySummary = MutableStateFlow("")
    val telemetrySummary: StateFlow<String> = _telemetrySummary.asStateFlow()

    private val _micStats = MutableStateFlow(
        MicMetrics(
            source = SettingsRepository.getMicSource(),
            sampleRateHz = 0,
            framesPerSec = 0,
            gapCount = 0,
            lastGapMs = 0,
            rssiAvg = null,
            packetLossPct = null,
        ),
    )
    val micStats: StateFlow<MicMetrics> = _micStats.asStateFlow()

    private val _dashboardStatus = MutableStateFlow(telemetry.dashboardStatus.value)
    val dashboardStatus: StateFlow<DashboardDataEncoder.BurstStatus> = _dashboardStatus.asStateFlow()

    private val _micEnabled = MutableStateFlow(SettingsRepository.isMicEnabled())
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val _voiceOnLift = MutableStateFlow(SettingsRepository.isVoiceWakeOnLiftEnabled())
    val voiceOnLift: StateFlow<Boolean> = _voiceOnLift.asStateFlow()

    val micSource: StateFlow<MicSource> = SettingsRepository.micSourceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.getMicSource())

    val audioSink: StateFlow<AudioSink> = SettingsRepository.audioSinkFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.getAudioSink())

    val deviceTelemetry: StateFlow<Map<MoncchichiBleService.Lens, BleTelemetryRepository.DeviceTelemetrySnapshot>> =
        telemetry.deviceTelemetryFlow
            .map { snapshots -> snapshots.associateBy { it.lens } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val validationStatus: StateFlow<BleTelemetryRepository.ValidationStatus> = telemetry.validationStatus

    val gestures: SharedFlow<LensGestureEvent> = telemetry.gesture

    private val _events = MutableSharedFlow<DeveloperEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DeveloperEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            hubViewModel.state.collect { state ->
                _consoleLines.value = state.consoleLines
            }
        }
        viewModelScope.launch {
            combine(
                telemetry.deviceTelemetryFlow,
                telemetry.caseStatus,
            ) { snapshots, case ->
                buildTelemetrySummary(snapshots, case)
            }.collect { summary ->
                _telemetrySummary.value = summary
            }
        }
        viewModelScope.launch {
            telemetry.snapshot.collect { snap ->
                _snapshot.value = snap
            }
        }
        viewModelScope.launch {
            micManager.metrics.collect { stats ->
                _micStats.value = stats
            }
        }
        viewModelScope.launch {
            telemetry.dashboardStatus.collect { status ->
                _dashboardStatus.value = status
            }
        }
    }

    fun setMode(mode: DeveloperMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        prefs.edit().putString(PREF_KEY_MODE, mode.name).apply()
    }

    fun copy(mode: DeveloperMode) {
        val payload = when (mode) {
            DeveloperMode.CONSOLE -> buildConsoleText(_consoleLines.value)
            DeveloperMode.DIAGNOSTICS -> buildSnapshotText(_snapshot.value)
        }
        viewModelScope.launch {
            if (payload.isBlank()) {
                _events.emit(DeveloperEvent.Notify(appContext.getString(R.string.developer_toast_nothing_to_copy)))
            } else {
                _events.emit(DeveloperEvent.CopyToClipboard(payload))
            }
        }
    }

    fun clear(mode: DeveloperMode) {
        when (mode) {
            DeveloperMode.CONSOLE -> viewModelScope.launch { hubViewModel.post(AppEvent.ClearConsole) }
            DeveloperMode.DIAGNOSTICS -> telemetry.reset()
        }
        viewModelScope.launch {
            val message = when (mode) {
                DeveloperMode.CONSOLE -> R.string.developer_toast_console_cleared
                DeveloperMode.DIAGNOSTICS -> R.string.developer_toast_diagnostics_cleared
            }
            _events.emit(DeveloperEvent.Notify(appContext.getString(message)))
        }
    }

    fun runValidationTest() {
        val started = telemetry.startValidationTest()
        viewModelScope.launch {
            val message = if (started) {
                "Validation test started"
            } else {
                "Validation already running"
            }
            _events.emit(DeveloperEvent.Notify(message))
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        if (_micEnabled.value == enabled) return
        val previous = _micEnabled.value
        _micEnabled.value = enabled
        viewModelScope.launch {
            val ok = telemetry.sendMicToggle(enabled)
            if (!ok) {
                _micEnabled.value = previous
                _events.emit(DeveloperEvent.Notify(appContext.getString(R.string.developer_mic_toggle_failed)))
            }
        }
    }

    fun setVoiceOnLift(enabled: Boolean) {
        if (_voiceOnLift.value == enabled) return
        _voiceOnLift.value = enabled
        SettingsRepository.setVoiceWakeOnLiftEnabled(enabled)
    }

    fun setMicSource(source: MicSource) {
        SettingsRepository.setMicSource(source)
        micManager.startCapture(source)
    }

    fun setAudioSink(sink: AudioSink) {
        SettingsRepository.setAudioSink(sink)
    }

    fun exportAllLogs(context: Context) {
        viewModelScope.launch(ioDispatcher) {
            val console = buildConsoleText(_consoleLines.value)
            val diagnostics = buildSnapshotText(_snapshot.value)
            if (console.isBlank() && diagnostics.isBlank()) {
                _events.emit(DeveloperEvent.Notify(appContext.getString(R.string.developer_toast_nothing_to_export)))
                return@launch
            }

            val content = buildString {
                append("# Moncchichi Developer Export\n\n")
                if (console.isNotBlank()) {
                    append("## Console Logs\n")
                    append(console)
                    append("\n\n")
                }
                append("## Diagnostics\n")
                append(if (diagnostics.isNotBlank()) diagnostics else appContext.getString(R.string.developer_no_diagnostics))
                append('\n')
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val exportFile = File(exportDir, "moncchichi-logs-$timestamp.txt")
            exportFile.writeText(content)
            val authority = context.packageName + ".fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, exportFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.developer_export_subject))
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _events.emit(DeveloperEvent.ShareLogs(intent))
        }
    }

    private fun loadInitialMode(): DeveloperMode {
        val stored = prefs.getString(PREF_KEY_MODE, null)
        return stored?.let { runCatching { DeveloperMode.valueOf(it) }.getOrNull() } ?: DeveloperMode.CONSOLE
    }

    private fun buildConsoleText(lines: List<String>): String =
        lines.joinToString(separator = "\n")

    private fun buildSnapshotText(snapshot: BleTelemetryRepository.Snapshot): String {
        if (snapshot == BleTelemetryRepository.Snapshot()) return ""
        return buildString {
            val summaryLine = telemetrySummary.value
            if (summaryLine.isNotBlank()) {
                appendLine(summaryLine)
                appendLine()
            }
            appendLine("Uptime: ${snapshot.uptimeSeconds?.let { formatDuration(it) } ?: "–"}")
            appendLine("Last lens: ${snapshot.lastLens?.name ?: "–"}")
            appendLine("Connection sequence: ${snapshot.connectionSequence ?: "–"}")
            appendLine("Last frame: ${snapshot.lastFrameHex ?: "–"}")
            val case = caseStatus.value
            appendLine("Case battery: ${case.batteryPercent?.let { "$it%" } ?: "–"}")
            appendLine("Case charging: ${case.charging?.let { if (it) "yes" else "no" } ?: "–"}")
            appendLine("Case lid: ${formatCaseDoor(case.lidOpen)}")
            appendLine("Silent mode: ${formatSilent(case.silentMode)}")
            val deviceSnapshots = deviceTelemetry.value
            val leftTelemetryJson = deviceSnapshots[MoncchichiBleService.Lens.LEFT]
                ?.let { telemetry.toTelemetryJson(it, case) }
            val rightTelemetryJson = deviceSnapshots[MoncchichiBleService.Lens.RIGHT]
                ?.let { telemetry.toTelemetryJson(it, case) }
            appendLine("Left telemetry JSON: ${leftTelemetryJson ?: "–"}")
            appendLine("Right telemetry JSON: ${rightTelemetryJson ?: "–"}")
            appendLine()
            appendLensBlock("Left", snapshot.left)
            appendLine()
            appendLensBlock("Right", snapshot.right)
            appendLine("Auto reconnect attempts: ${snapshot.autoReconnectAttempts}")
            appendLine("Auto reconnect successes: ${snapshot.autoReconnectSuccesses}")
            appendLine("Pairing dialogs shown: ${snapshot.pairingDialogsShown}")
            appendLine("Bond resets: ${snapshot.bondResetEvents}")
        }
    }

    private fun buildTelemetrySummary(
        snapshots: List<BleTelemetryRepository.DeviceTelemetrySnapshot>,
        case: BleTelemetryRepository.CaseStatus,
    ): String {
        if (snapshots.isEmpty()) return ""
        val left = snapshots.firstOrNull { it.lens == MoncchichiBleService.Lens.LEFT }
        val right = snapshots.firstOrNull { it.lens == MoncchichiBleService.Lens.RIGHT }
        val uptimeSeconds = snapshots.mapNotNull { it.uptimeSeconds }.maxOrNull()
        val firmware = left?.firmwareVersion ?: right?.firmwareVersion
        val hasCase = listOf(case.batteryPercent, case.charging, case.lidOpen, case.silentMode).any { it != null }
        val hasLens = listOf(left?.batteryVoltageMv, right?.batteryVoltageMv, firmware, uptimeSeconds).any { it != null }
        if (!hasCase && !hasLens) return ""
        val caseBattery = case.batteryPercent?.let { "$it%" } ?: "–"
        val caseState = when (case.lidOpen) {
            true -> "Open"
            false -> "Closed"
            null -> "Unknown"
        }
        val silent = case.silentMode?.let { if (it) "Silent On" else "Silent Off" }
        val charging = case.charging?.let { if (it) "Charging" else "Not Charging" }
        val leftVoltage = left?.batteryVoltageMv?.let { "${it}mV" } ?: "–"
        val rightVoltage = right?.batteryVoltageMv?.let { "${it}mV" } ?: "–"
        val uptime = uptimeSeconds?.let { formatDuration(it) }
        return buildString {
            append("Case $caseBattery ($caseState)")
            silent?.let {
                append(" • ")
                append(it)
            }
            charging?.let {
                append(" • ")
                append(it)
            }
            append(" • L ")
            append(leftVoltage)
            append(" • R ")
            append(rightVoltage)
            firmware?.let {
                append(" • FW ")
                append(it)
            }
            uptime?.let {
                append(" • Up ")
                append(it)
            }
        }
    }

    private fun StringBuilder.appendLensBlock(label: String, lens: BleTelemetryRepository.LensTelemetry) {
        appendLine("$label lens:")
        appendLine("  Battery: ${lens.batteryPercent?.let { "$it%" } ?: "–"}")
        appendLine("  Charging: ${lens.charging?.let { if (it) "yes" else "no" } ?: "–"}")
        appendLine("  Wearing: ${formatPresence(lens.wearing)}")
        appendLine("  Case state: ${formatCaseState(lens.inCase)}")
        appendLine("  Case battery: ${lens.caseBatteryPercent?.let { "$it%" } ?: "–"}")
        appendLine("  Case lid: ${formatCaseDoor(lens.caseOpen)}")
        appendLine("  Silent mode: ${formatSilent(lens.silentMode)}")
        appendLine("  RSSI: ${lens.rssi?.let { "${it} dBm" } ?: "–"}")
        appendLine("  Firmware: ${lens.firmwareVersion ?: "–"}")
        appendLine("  Bonded: ${if (lens.bonded) "yes" else "no"}")
        appendLine("  Bond attempts: ${lens.bondAttempts}")
        appendLine("  Bond transitions: ${lens.bondTransitions}")
        appendLine("  Bond timeouts: ${lens.bondTimeouts}")
        appendLine("  Last bond result: ${lens.lastBondResult ?: "–"}")
        appendLine("  Last bond state: ${formatBondState(lens.lastBondState)}")
        appendLine("  Last bond reason: ${formatBondReason(lens.lastBondReason)}")
        appendLine("  Bond event at: ${formatBondTimestamp(lens.lastBondEventAt)}")
        appendLine("  Disconnect reason: ${lens.disconnectReason ?: "–"}")
        appendLine("  Reconnect attempts: ${lens.reconnectAttempts}")
        appendLine("  Reconnect successes: ${lens.reconnectSuccesses}")
        appendLine("  SMP frames: ${lens.smpFrames}")
        appendLine("  Last SMP opcode: ${lens.lastSmpOpcode ?: "–"}")
        appendLine("  Refresh count: ${lens.refreshCount}")
        appendLine("  Pairing dialogs: ${lens.pairingDialogsShown}")
        appendLine("  Reconnecting now: ${if (lens.reconnecting) "yes" else "no"}")
        appendLine("  Last ACK: ${formatAckTimestamp(lens.lastAckAt)}")
        appendLine(
            "  ACK counts: ok=${lens.ackSuccessCount} fail=${lens.ackFailureCount} warmup=${lens.ackWarmupCount} drop=${lens.ackDropCount}"
        )
        appendLine("  ACK opcode: ${formatOpcode(lens.lastAckOpcode)} latency=${formatLatency(lens.lastAckLatencyMs)}")
        if (!lens.notes.isNullOrBlank()) {
            appendLine("  Notes: ${lens.notes}")
        }
        appendLine("  Battery source: ${formatSource(lens.batterySourceOpcode, lens.batteryUpdatedAt)}")
        appendLine("  Charging source: ${formatSource(lens.chargingSourceOpcode, lens.chargingUpdatedAt)}")
        appendLine("  Firmware source: ${formatSource(lens.firmwareSourceOpcode, lens.firmwareUpdatedAt)}")
        appendLine("  Last power frame: ${formatSource(lens.lastPowerOpcode, lens.lastPowerUpdatedAt)}")
        appendLine("  Last state frame: ${lens.lastStateUpdatedAt?.let { formatTimeOfDay(it) } ?: "–"}")
        val lastUpdated = lens.lastUpdatedAt?.let { formatTimeOfDay(it) }
        appendLine("  Last update: ${lastUpdated ?: "–"}")
        if (lens.powerHistory.isNotEmpty()) {
            appendLine("  Power frames:")
            lens.powerHistory.takeLast(10).asReversed().forEach { frame ->
                appendLine("    ${formatOpcode(frame.opcode)} @ ${formatTimeOfDay(frame.timestampMs)} ${formatPowerHex(frame.hex)}")
            }
        }
    }

    private fun formatBondState(state: Int?): String = when (state) {
        null -> "–"
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> state.toString()
    }

    private fun formatBondReason(reason: Int?): String = when (reason) {
        null -> "–"
        1 -> "UNBOND_REASON_AUTH_FAILED"
        2 -> "UNBOND_REASON_AUTH_REJECTED"
        3 -> "UNBOND_REASON_AUTH_CANCELED"
        4 -> "UNBOND_REASON_REMOTE_DEVICE_DOWN"
        5 -> "UNBOND_REASON_REMOVED"
        6 -> "UNBOND_REASON_OPERATION_CANCELED"
        7 -> "UNBOND_REASON_REPEATED_ATTEMPTS"
        8 -> "UNBOND_REASON_REMOTE_AUTH_CANCELED"
        9 -> "UNBOND_REASON_UNKNOWN"
        10 -> "BOND_FAILURE_UNKNOWN"
        else -> reason.toString()
    }

    private fun formatPresence(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "–"
    }

    private fun formatCaseState(value: Boolean?): String = when (value) {
        true -> "in case"
        false -> "out of case"
        null -> "–"
    }

    private fun formatCaseDoor(value: Boolean?): String = when (value) {
        true -> "open"
        false -> "closed"
        null -> "–"
    }

    private fun formatSilent(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "–"
    }

    private fun formatAckTimestamp(value: Long?): String = value?.let { formatTimeOfDay(it) } ?: "–"

    private fun formatLatency(value: Long?): String = value?.let { "${it}ms" } ?: "–"

    private fun formatSource(opcode: Int?, timestamp: Long?): String {
        if (opcode == null || timestamp == null) return "–"
        return "${formatOpcode(opcode)} @ ${formatTimeOfDay(timestamp)}"
    }

    private fun formatOpcode(opcode: Int?): String = opcode?.let { String.format(Locale.US, "0x%02X", it and 0xFF) } ?: "–"

    private fun formatTimeOfDay(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

    private fun formatPowerHex(hex: String): String = hex.chunked(2).joinToString(separator = " ") { it.uppercase(Locale.US) }

    private fun formatBondTimestamp(timestamp: Long?): String {
        if (timestamp == null) return "–"
        return runCatching {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
        }.getOrDefault("–")
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
    }

    class Factory(
        private val appContext: Context,
        private val hubViewModel: HubViewModel,
        private val telemetry: BleTelemetryRepository,
        private val micManager: MicStreamManager,
        private val prefs: SharedPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DeveloperViewModel::class.java))
            return DeveloperViewModel(appContext, hubViewModel, telemetry, micManager, prefs) as T
        }
    }
}
