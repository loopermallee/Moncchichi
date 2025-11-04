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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.loopermallee.moncchichi.hub.R
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
        val uptimeView = view.findViewById<TextView>(R.id.text_overview_uptime)
        val lensLastView = view.findViewById<TextView>(R.id.text_overview_last_lens)
        val sequenceView = view.findViewById<TextView>(R.id.text_overview_sequence)
        val frameView = view.findViewById<TextView>(R.id.text_overview_last_frame)
        val autoReconnectView = view.findViewById<TextView>(R.id.text_overview_reconnect)
        val pairingView = view.findViewById<TextView>(R.id.text_overview_pairing)
        val resetView = view.findViewById<TextView>(R.id.text_overview_resets)

        val leftBattery = view.findViewById<TextView>(R.id.text_left_battery)
        val leftCase = view.findViewById<TextView>(R.id.text_left_case)
        val leftPresence = view.findViewById<TextView>(R.id.text_left_presence)
        val leftCaseState = view.findViewById<TextView>(R.id.text_left_case_state)
        val leftSilent = view.findViewById<TextView>(R.id.text_left_silent)
        val leftRssi = view.findViewById<TextView>(R.id.text_left_rssi)
        val leftFirmware = view.findViewById<TextView>(R.id.text_left_firmware)
        val leftBond = view.findViewById<TextView>(R.id.text_left_bond)
        val leftBondStats = view.findViewById<TextView>(R.id.text_left_bond_stats)
        val leftReconnect = view.findViewById<TextView>(R.id.text_left_reconnect)
        val leftSmp = view.findViewById<TextView>(R.id.text_left_smp)
        val leftLastAck = view.findViewById<TextView>(R.id.text_left_last_ack)
        val leftAckCounts = view.findViewById<TextView>(R.id.text_left_ack_counts)
        val leftUpdated = view.findViewById<TextView>(R.id.text_left_updated)

        val rightBattery = view.findViewById<TextView>(R.id.text_right_battery)
        val rightCase = view.findViewById<TextView>(R.id.text_right_case)
        val rightPresence = view.findViewById<TextView>(R.id.text_right_presence)
        val rightCaseState = view.findViewById<TextView>(R.id.text_right_case_state)
        val rightSilent = view.findViewById<TextView>(R.id.text_right_silent)
        val rightRssi = view.findViewById<TextView>(R.id.text_right_rssi)
        val rightFirmware = view.findViewById<TextView>(R.id.text_right_firmware)
        val rightBond = view.findViewById<TextView>(R.id.text_right_bond)
        val rightBondStats = view.findViewById<TextView>(R.id.text_right_bond_stats)
        val rightReconnect = view.findViewById<TextView>(R.id.text_right_reconnect)
        val rightSmp = view.findViewById<TextView>(R.id.text_right_smp)
        val rightLastAck = view.findViewById<TextView>(R.id.text_right_last_ack)
        val rightAckCounts = view.findViewById<TextView>(R.id.text_right_ack_counts)
        val rightUpdated = view.findViewById<TextView>(R.id.text_right_updated)

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
                    applyMode(root, consoleScroll, diagnosticsScroll, mode)
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
                    consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snapshot.collectLatest { snapshot ->
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

                    snapshot.left.apply {
                        leftBattery.text = getString(R.string.developer_label_battery, formatPercent(batteryPercent))
                        leftCase.text = getString(R.string.developer_label_case_battery, formatPercent(caseBatteryPercent))
                        leftPresence.text = getString(R.string.developer_label_wearing, formatWearing(wearing))
                        leftCaseState.text = getString(R.string.developer_label_case_state, formatCaseState(inCase))
                        leftSilent.text = getString(R.string.developer_label_silent_mode, formatSilent(silentMode))
                        leftRssi.text = getString(R.string.developer_label_rssi, formatRssi(rssi))
                        val firmware = firmwareVersion ?: getString(R.string.developer_value_missing)
                        leftFirmware.text = getString(R.string.developer_label_firmware, firmware)
                        val bondedValue = getString(
                            if (bonded) R.string.developer_value_yes else R.string.developer_value_no,
                        )
                        leftBond.text = getString(R.string.developer_label_bonded, bondedValue)
                        leftBondStats.text = getString(
                            R.string.developer_label_bond_stats,
                            bondAttempts,
                            bondTransitions,
                            bondTimeouts,
                        )
                        leftReconnect.text = getString(
                            R.string.developer_label_reconnect_stats,
                            reconnectAttempts,
                            reconnectSuccesses,
                            if (reconnecting) getString(R.string.developer_value_yes) else getString(R.string.developer_value_no),
                        )
                        leftSmp.text = getString(
                            R.string.developer_label_smp,
                            smpFrames,
                            lastSmpOpcode?.toString() ?: getString(R.string.developer_value_missing),
                        )
                        leftLastAck.text = getString(
                            R.string.developer_label_last_ack,
                            formatAckTimestamp(lastAckAt),
                        )
                        leftAckCounts.text = getString(
                            R.string.developer_label_ack_counts,
                            ackSuccessCount,
                            ackFailureCount,
                            ackWarmupCount,
                        )
                        val updated = lastUpdated?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_missing)
                        leftUpdated.text = getString(R.string.developer_label_updated, updated)
                    }

                    snapshot.right.apply {
                        rightBattery.text = getString(R.string.developer_label_battery, formatPercent(batteryPercent))
                        rightCase.text = getString(R.string.developer_label_case_battery, formatPercent(caseBatteryPercent))
                        rightPresence.text = getString(R.string.developer_label_wearing, formatWearing(wearing))
                        rightCaseState.text = getString(R.string.developer_label_case_state, formatCaseState(inCase))
                        rightSilent.text = getString(R.string.developer_label_silent_mode, formatSilent(silentMode))
                        rightRssi.text = getString(R.string.developer_label_rssi, formatRssi(rssi))
                        val firmware = firmwareVersion ?: getString(R.string.developer_value_missing)
                        rightFirmware.text = getString(R.string.developer_label_firmware, firmware)
                        val bondedValue = getString(
                            if (bonded) R.string.developer_value_yes else R.string.developer_value_no,
                        )
                        rightBond.text = getString(R.string.developer_label_bonded, bondedValue)
                        rightBondStats.text = getString(
                            R.string.developer_label_bond_stats,
                            bondAttempts,
                            bondTransitions,
                            bondTimeouts,
                        )
                        rightReconnect.text = getString(
                            R.string.developer_label_reconnect_stats,
                            reconnectAttempts,
                            reconnectSuccesses,
                            if (reconnecting) getString(R.string.developer_value_yes) else getString(R.string.developer_value_no),
                        )
                        rightSmp.text = getString(
                            R.string.developer_label_smp,
                            smpFrames,
                            lastSmpOpcode?.toString() ?: getString(R.string.developer_value_missing),
                        )
                        rightLastAck.text = getString(
                            R.string.developer_label_last_ack,
                            formatAckTimestamp(lastAckAt),
                        )
                        rightAckCounts.text = getString(
                            R.string.developer_label_ack_counts,
                            ackSuccessCount,
                            ackFailureCount,
                            ackWarmupCount,
                        )
                        val updated = lastUpdated?.let { formatTimestamp(it) } ?: getString(R.string.developer_value_missing)
                        rightUpdated.text = getString(R.string.developer_label_updated, updated)
                    }
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
            }
            DeveloperMode.DIAGNOSTICS -> {
                root.setBackgroundColor(diagnosticsColor)
                consoleScroll.isVisible = false
                diagnosticsScroll.isVisible = true
            }
        }
    }

    private fun formatPercent(value: Int?): String = value?.let { "$it%" } ?: getString(R.string.developer_value_missing)

    private fun formatRssi(value: Int?): String =
        value?.let { getString(R.string.developer_value_rssi, it) }
            ?: getString(R.string.developer_value_missing)

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

    private fun formatTimestamp(millis: Long): String =
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", millis).toString()
}
