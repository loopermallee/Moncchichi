package com.loopermallee.moncchichi.hub.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.handlers.SystemEventHandler
import com.loopermallee.moncchichi.hub.util.LogFormatter
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DeveloperFragment : Fragment() {

    private lateinit var systemHandler: SystemEventHandler
    private val hubViewModel: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.diagnostics,
            AppLocator.perms,
            AppLocator.tts,
            AppLocator.prefs,
            AppLocator.telemetry,
        )
    }
    private val viewModel: DeveloperViewModel by viewModels {
        DeveloperViewModel.Factory(
            requireContext().applicationContext,
            hubViewModel,
            AppLocator.telemetry,
            AppLocator.prefs,
        )
    }

    private var currentMode: DeveloperMode = DeveloperMode.CONSOLE

    override fun onAttach(context: Context) {
        super.onAttach(context)
        systemHandler = SystemEventHandler(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_developer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<View>(R.id.developer_root)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_modes)
        val consoleButtonId = R.id.button_mode_console
        val diagnosticsButtonId = R.id.button_mode_diagnostics
        val consoleScroll = view.findViewById<NestedScrollView>(R.id.console_scroll)
        val diagnosticsScroll = view.findViewById<ScrollView>(R.id.diagnostics_scroll)
        val logsView = view.findViewById<TextView>(R.id.text_console_logs)
        val jumpLatest = view.findViewById<MaterialButton>(R.id.button_jump_latest)
        val uptimeView = view.findViewById<TextView>(R.id.text_overview_uptime)
        val lensLastView = view.findViewById<TextView>(R.id.text_overview_last_lens)
        val sequenceView = view.findViewById<TextView>(R.id.text_overview_sequence)
        val frameView = view.findViewById<TextView>(R.id.text_overview_last_frame)
        val autoReconnectView = view.findViewById<TextView>(R.id.text_overview_reconnect)
        val pairingView = view.findViewById<TextView>(R.id.text_overview_pairing)
        val resetView = view.findViewById<TextView>(R.id.text_overview_resets)
        val leftSummary = view.findViewById<TextView>(R.id.text_glasses_left_summary)
        val leftSources = view.findViewById<TextView>(R.id.text_glasses_left_sources)
        val rightSummary = view.findViewById<TextView>(R.id.text_glasses_right_summary)
        val rightSources = view.findViewById<TextView>(R.id.text_glasses_right_sources)
        val leftPowerButton = view.findViewById<MaterialButton>(R.id.button_left_power_history)
        val rightPowerButton = view.findViewById<MaterialButton>(R.id.button_right_power_history)

        var autoScrollEnabled = true
        var latestSnapshot: BleTelemetryRepository.Snapshot? = null

        consoleScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val atBottom = !consoleScroll.canScrollVertically(1)
            if (atBottom) {
                if (!autoScrollEnabled) {
                    autoScrollEnabled = true
                    jumpLatest.isVisible = false
                }
            } else if (autoScrollEnabled) {
                autoScrollEnabled = false
                jumpLatest.isVisible = true
            }
        }

        jumpLatest.setOnClickListener {
            autoScrollEnabled = true
            jumpLatest.isVisible = false
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }

        leftPowerButton.setOnClickListener {
            val snapshot = latestSnapshot ?: return@setOnClickListener
            showPowerHistoryDialog(getString(R.string.developer_section_left), snapshot.left.powerHistory)
        }
        rightPowerButton.setOnClickListener {
            val snapshot = latestSnapshot ?: return@setOnClickListener
            showPowerHistoryDialog(getString(R.string.developer_section_right), snapshot.right.powerHistory)
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                consoleButtonId -> DeveloperMode.CONSOLE
                diagnosticsButtonId -> DeveloperMode.DIAGNOSTICS
                else -> DeveloperMode.CONSOLE
            }
            viewModel.setMode(mode)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mode.collectLatest { mode ->
                    currentMode = mode
                    when (mode) {
                        DeveloperMode.CONSOLE -> toggleGroup.check(consoleButtonId)
                        DeveloperMode.DIAGNOSTICS -> toggleGroup.check(diagnosticsButtonId)
                    }
                    applyMode(root, consoleScroll, diagnosticsScroll, jumpLatest, mode)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.consoleLines.collectLatest { lines ->
                    val context = requireContext()
                    val formatted = lines.map { LogFormatter.format(context, it) }
                    logsView.text = SpannableStringBuilder().apply {
                        formatted.forEachIndexed { index, span ->
                            append(span)
                            if (index < formatted.lastIndex) append('\n')
                        }
                    }
                    if (autoScrollEnabled) {
                        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
                    } else {
                        jumpLatest.isVisible = true
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snapshot.collectLatest { snapshot ->
                    latestSnapshot = snapshot
                    val uptime = snapshot.uptimeSeconds?.let { formatDuration(it) }
                        ?: getString(R.string.developer_value_missing)
                    val lastLens = snapshot.lastLens?.name ?: getString(R.string.developer_value_missing)
                    val sequence = snapshot.connectionSequence ?: getString(R.string.developer_value_missing)
                    val lastFrame = snapshot.lastFrameHex ?: getString(R.string.developer_value_missing)
                    uptimeView.text = getString(R.string.developer_label_uptime, uptime)
                    lensLastView.text = getString(R.string.developer_label_last_lens, lastLens)
                    sequenceView.text = getString(R.string.developer_label_sequence, sequence)
                    frameView.text = getString(R.string.developer_label_last_frame, lastFrame)
                    autoReconnectView.text = getString(
                        R.string.developer_label_reconnect,
                        snapshot.autoReconnectAttempts,
                        snapshot.autoReconnectSuccesses,
                    )
                    pairingView.text = getString(
                        R.string.developer_label_pairing,
                        snapshot.pairingDialogsShown,
                    )
                    resetView.text = getString(
                        R.string.developer_label_resets,
                        snapshot.bondResetEvents,
                    )

                    leftSummary.text = buildLensSummary(snapshot.left)
                    leftSources.text = buildLensSources(snapshot.left)
                    rightSummary.text = buildLensSummary(snapshot.right)
                    rightSources.text = buildLensSources(snapshot.right)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                systemHandler.events.collectLatest { event ->
                    hubViewModel.logSystemEvent(event)
                    Toast.makeText(requireContext(), event, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        is DeveloperViewModel.DeveloperEvent.CopyToClipboard -> copyToClipboard(event.payload)
                        is DeveloperViewModel.DeveloperEvent.Notify -> Toast.makeText(
                            requireContext(),
                            event.message,
                            Toast.LENGTH_SHORT,
                        ).show()
                        is DeveloperViewModel.DeveloperEvent.ShareLogs -> shareLogs(event.intent)
                    }
                }
            }
        }
    }

    fun handleToolbarAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_copy_logs -> {
                viewModel.copy(currentMode)
                true
            }
            R.id.action_clear_logs -> {
                viewModel.clear(currentMode)
                true
            }
            R.id.action_export_logs -> {
                viewModel.exportAllLogs(requireContext())
                true
            }
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        systemHandler.register()
    }

    override fun onStop() {
        super.onStop()
        systemHandler.unregister()
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.developer_clipboard_label), content))
        Toast.makeText(requireContext(), R.string.developer_toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs(intent: Intent) {
        val chooser = Intent.createChooser(intent, getString(R.string.developer_share_title))
        runCatching { startActivity(chooser) }.onFailure {
            Toast.makeText(requireContext(), R.string.developer_toast_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyMode(
        root: View,
        consoleScroll: NestedScrollView,
        diagnosticsScroll: ScrollView,
        jumpLatest: MaterialButton,
        mode: DeveloperMode,
    ) {
        val context = requireContext()
        val consoleColor = ContextCompat.getColor(context, R.color.developer_console_background)
        val diagnosticsColor = ContextCompat.getColor(context, R.color.developer_diagnostics_background)
        when (mode) {
            DeveloperMode.CONSOLE -> {
                root.setBackgroundColor(consoleColor)
                consoleScroll.isVisible = true
                diagnosticsScroll.isVisible = false
                jumpLatest.isVisible = false
            }
            DeveloperMode.DIAGNOSTICS -> {
                root.setBackgroundColor(diagnosticsColor)
                consoleScroll.isVisible = false
                diagnosticsScroll.isVisible = true
                jumpLatest.isVisible = false
            }
        }
    }

    private fun buildLensSummary(lens: BleTelemetryRepository.LensTelemetry): String {
        if (lens.lastPowerUpdatedAt == null && lens.batteryPercent == null && lens.caseBatteryPercent == null) {
            return getString(R.string.developer_value_waiting_battery)
        }
        val battery = formatPercent(lens.batteryPercent)
        val case = formatPercent(lens.caseBatteryPercent)
        val charging = formatCharging(lens.charging)
        val rssi = formatRssi(lens.rssi)
        val firmware = lens.firmwareVersion ?: getString(R.string.developer_value_missing)
        val bonded = if (lens.bonded) getString(R.string.developer_value_yes) else getString(R.string.developer_value_no)
        val reconnect = getString(
            R.string.developer_label_reconnect_stats,
            lens.reconnectAttempts,
            lens.reconnectSuccesses,
            if (lens.reconnecting) getString(R.string.developer_value_yes) else getString(R.string.developer_value_no),
        )
        val ackCounts = getString(
            R.string.developer_label_ack_counts,
            lens.ackSuccessCount,
            lens.ackFailureCount,
            lens.ackWarmupCount,
        )
        val lastAck = formatAckTimestamp(lens.lastAckAt)
        val latency = formatLatency(lens.lastAckLatencyMs)
        val updated = lens.lastUpdatedAt?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_waiting)
        val powerSource = formatOpcode(lens.lastPowerOpcode)
        val powerTime = lens.lastPowerUpdatedAt?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_waiting)
        val stateTime = lens.lastStateUpdatedAt?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_waiting)
        return buildString {
            append("Battery $battery / Case $case • Charging $charging")
            append('\n')
            append(
                "State ${formatCaseState(lens.inCase)} • Door ${formatCaseDoor(lens.caseOpen)} • " +
                    "Wearing ${formatWearing(lens.wearing)} • Silent ${formatSilent(lens.silentMode)}"
            )
            append('\n')
            append("RSSI $rssi • Firmware $firmware")
            append('\n')
            append("Bonded $bonded")
            append(" • ")
            append(reconnect)
            append('\n')
            append(ackCounts)
            append(" • Latency $latency • Last $lastAck")
            append('\n')
            append("Updated $updated • Power $powerSource @ $powerTime • State @ $stateTime")
        }
    }

    private fun buildLensSources(lens: BleTelemetryRepository.LensTelemetry): String {
        val batterySource = describeSource(lens.batterySourceOpcode, lens.batteryUpdatedAt)
        val chargingSource = describeSource(lens.chargingSourceOpcode, lens.chargingUpdatedAt)
        val firmwareSource = describeSource(lens.firmwareSourceOpcode, lens.firmwareUpdatedAt)
        val powerSource = describeSource(lens.lastPowerOpcode, lens.lastPowerUpdatedAt)
        val stateSource = lens.lastStateUpdatedAt?.let { formatTimeOfDay(it) } ?: getString(R.string.developer_value_waiting)
        val header = buildString {
            append("Battery $batterySource • Charging $chargingSource • Firmware $firmwareSource")
            append('\n')
            append("Power $powerSource • State @ $stateSource")
        }
        val history = lens.powerHistory.takeLast(POWER_HISTORY_PREVIEW).asReversed()
        if (history.isEmpty()) {
            return header
        }
        val frames = history.joinToString(separator = "\n") { frame ->
            val opcode = formatOpcode(frame.opcode)
            val timestamp = formatTimeOfDay(frame.timestampMs)
            "$opcode @ $timestamp  ${formatPowerHex(frame.hex)}"
        }
        return buildString {
            append(header)
            append('\n')
            append(frames)
        }
    }

    private fun describeSource(opcode: Int?, timestamp: Long?): String {
        if (opcode == null || timestamp == null) {
            return getString(R.string.developer_value_missing)
        }
        val code = formatOpcode(opcode)
        val time = formatTimeOfDay(timestamp)
        return "$code @ $time"
    }

    private fun showPowerHistoryDialog(
        lensLabel: String,
        frames: List<BleTelemetryRepository.PowerFrame>,
    ) {
        if (frames.isEmpty()) {
            Toast.makeText(requireContext(), R.string.developer_no_power_frames, Toast.LENGTH_SHORT).show()
            return
        }
        val message = frames.takeLast(POWER_HISTORY_PREVIEW).asReversed().joinToString(separator = "\n\n") { frame ->
            val opcode = formatOpcode(frame.opcode)
            val time = formatTimeOfDay(frame.timestampMs)
            "$opcode @ $time\n${formatPowerHex(frame.hex)}"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.developer_power_history_title, lensLabel))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatPercent(value: Int?): String = value?.let { "$it%" } ?: getString(R.string.developer_value_missing)

    private fun formatRssi(value: Int?): String =
        value?.let { getString(R.string.developer_value_rssi, it) }
            ?: getString(R.string.developer_value_missing)

    private fun formatCharging(value: Boolean?): String = when (value) {
        true -> getString(R.string.developer_value_yes)
        false -> getString(R.string.developer_value_no)
        null -> getString(R.string.developer_value_missing)
    }

    private fun formatWearing(value: Boolean?): String = when (value) {
        true -> getString(R.string.developer_value_wearing)
        false -> getString(R.string.developer_value_not_wearing)
        null -> getString(R.string.developer_value_missing)
    }

    private fun formatCaseState(value: Boolean?): String = when (value) {
        true -> getString(R.string.developer_value_case_in)
        false -> getString(R.string.developer_value_case_out)
        null -> getString(R.string.developer_value_missing)
    }

    private fun formatCaseDoor(value: Boolean?): String = when (value) {
        true -> getString(R.string.developer_value_case_open)
        false -> getString(R.string.developer_value_case_closed)
        null -> getString(R.string.developer_value_missing)
    }

    private fun formatSilent(value: Boolean?): String = when (value) {
        true -> getString(R.string.developer_value_silent_on)
        false -> getString(R.string.developer_value_silent_off)
        null -> getString(R.string.developer_value_missing)
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return getString(R.string.developer_value_duration, hours, minutes, remaining)
    }

    private fun formatAckTimestamp(value: Long?): String =
        value?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_missing)

    private fun formatLatency(value: Long?): String = value?.let { "$it ms" }
        ?: getString(R.string.developer_value_missing)

    private fun formatOpcode(opcode: Int?): String = opcode?.let { String.format("0x%02X", it and 0xFF) }
        ?: getString(R.string.developer_value_missing)

    private fun formatPowerHex(hex: String): String = hex.chunked(2).joinToString(separator = " ") { it.uppercase() }

    private fun formatTimestamp(millis: Long): String =
        android.text.format.DateFormat.format("HH:mm:ss", millis).toString()

    private fun formatTimeOfDay(millis: Long): String =
        android.text.format.DateFormat.format("HH:mm:ss", millis).toString()

    companion object {
        private const val POWER_HISTORY_PREVIEW = 10
    }
}
