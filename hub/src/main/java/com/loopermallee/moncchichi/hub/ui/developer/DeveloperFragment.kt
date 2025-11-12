package com.loopermallee.moncchichi.hub.ui.developer

import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.chip.Chip
import com.google.android.material.R as MaterialR
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository.DeviceTelemetrySnapshot
import com.loopermallee.moncchichi.hub.data.telemetry.LensGestureEvent
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.handlers.SystemEventHandler
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import com.loopermallee.moncchichi.hub.ble.DashboardDataEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
            AppLocator.mic,
            AppLocator.prefs,
        )
    }

    private var currentMode: DeveloperMode = DeveloperMode.CONSOLE
    private var lensCards: List<LensCardController> = emptyList()
    private var caseCard: CaseCardController? = null
    private var ackSummaryView: TextView? = null
    private var linkSummaryView: TextView? = null
    private var snapshotSummaryView: TextView? = null
    private val snapshotTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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
        val consoleContainer = view.findViewById<View>(R.id.console_container)
        val consoleSummaryView = view.findViewById<TextView>(R.id.text_console_summary)
        val consoleRecycler = view.findViewById<RecyclerView>(R.id.list_console_logs)
        val pauseButton = view.findViewById<MaterialButton>(R.id.button_console_paused)
        val diagnosticsScroll = view.findViewById<ScrollView>(R.id.diagnostics_scroll)
        val chipPing = view.findViewById<Chip>(R.id.chip_filter_ping)
        val chipCase = view.findViewById<Chip>(R.id.chip_filter_case)
        val chipGesture = view.findViewById<Chip>(R.id.chip_filter_gesture)
        val chipAck = view.findViewById<Chip>(R.id.chip_filter_ack)
        val uptimeView = view.findViewById<TextView>(R.id.text_overview_uptime)
        val lensLastView = view.findViewById<TextView>(R.id.text_overview_last_lens)
        val sequenceView = view.findViewById<TextView>(R.id.text_overview_sequence)
        val frameView = view.findViewById<TextView>(R.id.text_overview_last_frame)
        val autoReconnectView = view.findViewById<TextView>(R.id.text_overview_reconnect)
        val overviewContainer = frameView.parent as? LinearLayout
        val summaryMargin = (resources.displayMetrics.density * 8f).roundToInt()
        val linkTextView = TextView(context).apply {
            id = View.generateViewId()
            TextViewCompat.setTextAppearance(this, MaterialR.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = summaryMargin }
            text = "LINK: L — • R —"
            setTextColor(ContextCompat.getColor(context, R.color.er_text_secondary))
        }
        val snapshotTextView = TextView(context).apply {
            id = View.generateViewId()
            TextViewCompat.setTextAppearance(this, MaterialR.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = summaryMargin }
            text = "SNAPSHOT: –"
            setTextColor(SNAPSHOT_COLOR_NORMAL)
        }
        val ackTextView = TextView(context).apply {
            id = View.generateViewId()
            TextViewCompat.setTextAppearance(this, MaterialR.style.TextAppearance_Material3_BodyMedium)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = summaryMargin }
            text = "ACK: — (L — • R —)"
            setTextColor(ContextCompat.getColor(context, R.color.er_text_secondary))
        }
        overviewContainer?.let { container ->
            val insertIndex = container.indexOfChild(autoReconnectView).takeIf { it >= 0 }
                ?: container.childCount
            container.addView(snapshotTextView, insertIndex)
            container.addView(linkTextView, insertIndex + 1)
            container.addView(ackTextView, insertIndex + 2)
        }
        linkSummaryView = linkTextView
        ackSummaryView = ackTextView
        snapshotSummaryView = snapshotTextView
        val pairingView = view.findViewById<TextView>(R.id.text_overview_pairing)
        val resetView = view.findViewById<TextView>(R.id.text_overview_resets)
        val leftPowerButton = view.findViewById<MaterialButton>(R.id.button_left_power_history)
        val rightPowerButton = view.findViewById<MaterialButton>(R.id.button_right_power_history)
        val micSwitch = view.findViewById<MaterialSwitch>(R.id.switch_mic_enabled)
        val voiceLiftSwitch = view.findViewById<MaterialSwitch>(R.id.switch_voice_on_lift)
        val audioRateView = view.findViewById<TextView>(R.id.text_audio_rx_rate)
        val audioGapsView = view.findViewById<TextView>(R.id.text_audio_gaps)
        val audioSeqView = view.findViewById<TextView>(R.id.text_audio_last_seq)
        val dashboardStatusView = view.findViewById<TextView>(R.id.text_dashboard_status)

        val caseController = CaseCardController(
            card = view.findViewById(R.id.card_case),
            batteryView = view.findViewById(R.id.text_case_battery_value),
            chargingIcon = view.findViewById(R.id.text_case_charging_icon),
            chargingValue = view.findViewById(R.id.text_case_charging_value),
            lidIcon = view.findViewById(R.id.text_case_lid_icon),
            lidValue = view.findViewById(R.id.text_case_lid_value),
            silentView = view.findViewById(R.id.text_case_silent_value),
        )
        caseCard = caseController
        caseController.bind(viewModel.caseStatus.value)

        val leftCardController = LensCardController(
            lens = MoncchichiBleService.Lens.LEFT,
            card = view.findViewById<MaterialCardView>(R.id.card_lens_left),
            voltageView = view.findViewById(R.id.text_lens_left_voltage_value),
            chargingIcon = view.findViewById(R.id.text_lens_left_charging_icon),
            chargingValue = view.findViewById(R.id.text_lens_left_charging_value),
            uptimeView = view.findViewById(R.id.text_lens_left_uptime_value),
            firmwareView = view.findViewById(R.id.text_lens_left_firmware_value),
            gestureRow = view.findViewById(R.id.row_lens_left_gesture),
            gestureValue = view.findViewById(R.id.text_lens_left_gesture_value),
        )
        val rightCardController = LensCardController(
            lens = MoncchichiBleService.Lens.RIGHT,
            card = view.findViewById<MaterialCardView>(R.id.card_lens_right),
            voltageView = view.findViewById(R.id.text_lens_right_voltage_value),
            chargingIcon = view.findViewById(R.id.text_lens_right_charging_icon),
            chargingValue = view.findViewById(R.id.text_lens_right_charging_value),
            uptimeView = view.findViewById(R.id.text_lens_right_uptime_value),
            firmwareView = view.findViewById(R.id.text_lens_right_firmware_value),
            gestureRow = view.findViewById(R.id.row_lens_right_gesture),
            gestureValue = view.findViewById(R.id.text_lens_right_gesture_value),
        )
        lensCards = listOf(leftCardController, rightCardController)

        val filterChips = mapOf(
            DeveloperViewModel.ConsoleFilterTag.PING to chipPing,
            DeveloperViewModel.ConsoleFilterTag.CASE to chipCase,
            DeveloperViewModel.ConsoleFilterTag.GESTURE to chipGesture,
            DeveloperViewModel.ConsoleFilterTag.ACK to chipAck,
        )
        var suppressFilterListener = false
        filterChips.forEach { (tag, chip) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (suppressFilterListener) return@setOnCheckedChangeListener
                viewModel.setConsoleFilterEnabled(tag, isChecked)
            }
        }

        val consoleAdapter = DeveloperConsoleAdapter()
        val consoleLayoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        consoleRecycler.layoutManager = consoleLayoutManager
        consoleRecycler.adapter = consoleAdapter
        consoleRecycler.itemAnimator = null
        consoleRecycler.setHasFixedSize(false)
        consoleRecycler.setItemViewCacheSize(64)

        var autoScrollEnabled = true
        var latestSnapshot: BleTelemetryRepository.Snapshot? = null
        var lastRenderedEntries: List<DeveloperViewModel.ConsoleEntry> = emptyList()
        val highlightedEntryIds = mutableSetOf<String>()

        fun renderConsole(entries: List<DeveloperViewModel.ConsoleEntry>, shouldScroll: Boolean) {
            val items = entries.map { entry ->
                DeveloperConsoleAdapter.ConsoleItem(entry, highlightedEntryIds.contains(entry.id))
            }
            consoleAdapter.submitList(items) {
                if (shouldScroll) {
                    val target = consoleAdapter.itemCount - 1
                    if (target >= 0) {
                        consoleRecycler.post {
                            consoleRecycler.smoothScrollToPosition(target)
                        }
                    }
                }
            }
        }

        fun resumeAutoScroll(forceScroll: Boolean) {
            if (autoScrollEnabled) return
            autoScrollEnabled = true
            highlightedEntryIds.clear()
            pauseButton.text = getString(R.string.developer_console_paused)
            pauseButton.isVisible = false
            renderConsole(lastRenderedEntries, forceScroll)
        }

        consoleRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val total = consoleAdapter.itemCount
                if (total == 0) return
                val lastVisible = consoleLayoutManager.findLastVisibleItemPosition()
                if (lastVisible == RecyclerView.NO_POSITION) return
                val remaining = (total - 1) - lastVisible
                if (autoScrollEnabled) {
                    if (remaining > CONSOLE_PAUSE_THRESHOLD && dy < 0) {
                        autoScrollEnabled = false
                        pauseButton.text = getString(R.string.developer_console_paused)
                        pauseButton.isVisible = true
                    }
                } else if (remaining <= CONSOLE_RESUME_THRESHOLD) {
                    resumeAutoScroll(false)
                }
            }
        })

        pauseButton.setOnClickListener { resumeAutoScroll(true) }

        leftPowerButton.setOnClickListener {
            val snapshot = latestSnapshot ?: return@setOnClickListener
            showPowerHistoryDialog(getString(R.string.developer_section_left), snapshot.left.powerHistory)
        }
        rightPowerButton.setOnClickListener {
            val snapshot = latestSnapshot ?: return@setOnClickListener
            showPowerHistoryDialog(getString(R.string.developer_section_right), snapshot.right.powerHistory)
        }

        var suppressMicListener = false
        micSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressMicListener) return@setOnCheckedChangeListener
            viewModel.setMicEnabled(isChecked)
        }
        var suppressVoiceListener = false
        voiceLiftSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressVoiceListener) return@setOnCheckedChangeListener
            viewModel.setVoiceOnLift(isChecked)
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
                    applyMode(root, consoleContainer, diagnosticsScroll, pauseButton, mode, autoScrollEnabled)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.consoleFilterState.collectLatest { state ->
                    suppressFilterListener = true
                    filterChips.forEach { (tag, chip) ->
                        chip.isChecked = state[tag] ?: true
                    }
                    suppressFilterListener = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filteredConsoleEntries.collectLatest { entries ->
                    val previousSize = lastRenderedEntries.size
                    lastRenderedEntries = entries

                    if (!autoScrollEnabled) {
                        val entryIds = entries.mapTo(mutableSetOf()) { it.id }
                        highlightedEntryIds.retainAll(entryIds)
                    }

                    val delta = entries.size - previousSize
                    if (!autoScrollEnabled && delta > 0) {
                        entries.takeLast(delta).forEach { entry ->
                            highlightedEntryIds.add(entry.id)
                        }
                    } else if (autoScrollEnabled) {
                        highlightedEntryIds.clear()
                    }

                    if (!autoScrollEnabled) {
                        val highlightCount = highlightedEntryIds.size
                        pauseButton.text = if (highlightCount > 0) {
                            getString(R.string.developer_console_paused_new, highlightCount)
                        } else {
                            getString(R.string.developer_console_paused)
                        }
                    }

                    renderConsole(entries, autoScrollEnabled)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.telemetrySummary.collectLatest { summary ->
                    consoleSummaryView.text = summary.ifBlank {
                        getString(R.string.developer_console_summary_placeholder)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snapshotLine.collectLatest { log ->
                    snapshotSummaryView?.let { view ->
                        if (log == null) {
                            view.text = "SNAPSHOT: –"
                            view.setTextColor(SNAPSHOT_COLOR_NORMAL)
                        } else {
                            val time = synchronized(snapshotTimeFormat) { snapshotTimeFormat.format(Date(log.timestamp)) }
                            view.text = "SNAPSHOT [$time] ${log.message}"
                            view.setTextColor(resolveSnapshotLineColor(log))
                        }
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
                    linkSummaryView?.let { view ->
                        val summary = buildLinkSummary(snapshot)
                        view.text = summary.text
                        view.setTextColor(ContextCompat.getColor(view.context, summary.colorRes))
                    }

                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.caseStatus.collectLatest { status ->
                    caseController.bind(status)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deviceTelemetry.collectLatest { telemetry ->
                    lensCards.forEach { controller ->
                        controller.bind(telemetry[controller.lens])
                    }
                    ackSummaryView?.let { view ->
                        val summary = buildAckSummary(telemetry)
                        view.text = summary.text
                        view.setTextColor(ContextCompat.getColor(view.context, summary.colorRes))
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.gestures.collectLatest { event ->
                    lensCards.firstOrNull { it.lens == event.lens }?.handleGesture(event)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.micEnabled.collectLatest { enabled ->
                    suppressMicListener = true
                    micSwitch.isChecked = enabled
                    suppressMicListener = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.voiceOnLift.collectLatest { enabled ->
                    suppressVoiceListener = true
                    voiceLiftSwitch.isChecked = enabled
                    suppressVoiceListener = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.micStats.collectLatest { stats ->
                    val hasData = stats.framesPerSec > 0 || stats.gapCount > 0 || stats.packetLossPct != null
                    if (!hasData) {
                        audioRateView.text = getString(R.string.developer_audio_rx_placeholder)
                        audioGapsView.text = getString(R.string.developer_audio_gaps_placeholder)
                        audioSeqView.text = getString(R.string.developer_audio_last_seq_placeholder)
                    } else {
                        val lossLabel = stats.packetLossPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
                        val sourceLabel = stats.source.name.lowercase(Locale.US).replaceFirstChar { ch -> ch.titlecase(Locale.US) }
                        audioRateView.text = getString(R.string.developer_audio_rx, stats.framesPerSec)
                        audioGapsView.text = getString(R.string.developer_audio_gaps, stats.gapCount, stats.lastGapMs)
                        audioSeqView.text = getString(R.string.developer_audio_last_seq, sourceLabel, lossLabel)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dashboardStatus.collectLatest { status ->
                    val text = when {
                        status.active -> getString(
                            R.string.developer_dashboard_status_active,
                            status.sentChunks,
                            status.totalChunks,
                        )
                        status.result is DashboardDataEncoder.BurstStatus.Result.Success -> {
                            val sequenceLabel = status.lastSequence?.let { String.format(Locale.US, "0x%02X", it and 0xFF) }
                                ?: getString(R.string.developer_firmware_placeholder)
                            getString(
                                R.string.developer_dashboard_status_success,
                                status.totalChunks,
                                sequenceLabel,
                            )
                        }
                        status.result is DashboardDataEncoder.BurstStatus.Result.Failure -> getString(
                            R.string.developer_dashboard_status_failure,
                            status.sentChunks,
                        )
                        else -> getString(R.string.developer_dashboard_status_idle)
                    }
                    dashboardStatusView.text = text
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

    override fun onDestroyView() {
        super.onDestroyView()
        caseCard?.clear()
        caseCard = null
        lensCards.forEach { it.clear() }
        lensCards = emptyList()
    }

    private inner class CaseCardController(
        private val card: MaterialCardView,
        private val batteryView: TextView,
        private val chargingIcon: TextView,
        private val chargingValue: TextView,
        private val lidIcon: TextView,
        private val lidValue: TextView,
        private val silentView: TextView,
    ) {
        private val context = card.context
        private val placeholder = context.getString(R.string.developer_value_missing)
        private val yesLabel = context.getString(R.string.developer_value_yes).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val noLabel = context.getString(R.string.developer_value_no).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val openLabel = context.getString(R.string.developer_value_case_open)
        private val closedLabel = context.getString(R.string.developer_value_case_closed)
        private val silentOn = context.getString(R.string.developer_value_silent_on).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val silentOff = context.getString(R.string.developer_value_silent_off).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val defaultTextColor = ContextCompat.getColor(context, R.color.er_text_primary)
        private val secondaryTextColor = ContextCompat.getColor(context, R.color.er_text_secondary)
        private val batteryHighColor = ContextCompat.getColor(context, R.color.batteryNormal)
        private val batteryMidColor = ContextCompat.getColor(context, R.color.chargingActive)
        private val batteryLowColor = ContextCompat.getColor(context, R.color.batteryLow)
        private val chargingActiveColor = ContextCompat.getColor(context, R.color.chargingActive)
        private val chargingInactiveColor = ContextCompat.getColor(context, R.color.chargingInactive)
        private val lidOpenColor = ContextCompat.getColor(context, R.color.lens_header_left)
        private val lidClosedColor = defaultTextColor
        private var chargingAnimator: ObjectAnimator? = null

        fun bind(status: BleTelemetryRepository.CaseStatus) {
            updateBattery(status.batteryPercent)
            updateCharging(status.charging)
            updateLid(status.lidOpen)
            updateSilent(status.silentMode)
        }

        fun clear() {
            chargingAnimator?.cancel()
            chargingAnimator = null
            batteryView.text = placeholder
            batteryView.setTextColor(defaultTextColor)
            chargingIcon.alpha = 1f
            chargingIcon.setTextColor(chargingInactiveColor)
            chargingValue.text = placeholder
            chargingValue.setTextColor(defaultTextColor)
            lidIcon.setTextColor(lidClosedColor)
            lidValue.text = placeholder
            lidValue.setTextColor(defaultTextColor)
            silentView.text = context.getString(R.string.developer_case_silent_placeholder)
            silentView.setTextColor(secondaryTextColor)
        }

        private fun updateBattery(value: Int?) {
            if (value == null) {
                batteryView.text = placeholder
                batteryView.setTextColor(defaultTextColor)
                return
            }
            batteryView.text = String.format(Locale.US, "%d%%", value)
            val color = when {
                value >= 60 -> batteryHighColor
                value >= 30 -> batteryMidColor
                else -> batteryLowColor
            }
            batteryView.setTextColor(color)
        }

        private fun updateCharging(value: Boolean?) {
            chargingAnimator?.cancel()
            chargingAnimator = null
            when (value) {
                true -> {
                    chargingValue.text = yesLabel
                    chargingValue.setTextColor(defaultTextColor)
                    chargingIcon.setTextColor(chargingActiveColor)
                    chargingAnimator = ObjectAnimator.ofFloat(chargingIcon, View.ALPHA, 1f, 0.3f).apply {
                        duration = 1000L
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        start()
                    }
                }
                false -> {
                    chargingValue.text = noLabel
                    chargingValue.setTextColor(defaultTextColor)
                    chargingIcon.setTextColor(chargingInactiveColor)
                    chargingIcon.alpha = 1f
                }
                null -> {
                    chargingValue.text = placeholder
                    chargingValue.setTextColor(defaultTextColor)
                    chargingIcon.setTextColor(chargingInactiveColor)
                    chargingIcon.alpha = 1f
                }
            }
        }

        private fun updateLid(value: Boolean?) {
            when (value) {
                true -> {
                    lidValue.text = openLabel
                    lidValue.setTextColor(defaultTextColor)
                    lidIcon.setTextColor(lidOpenColor)
                }
                false -> {
                    lidValue.text = closedLabel
                    lidValue.setTextColor(defaultTextColor)
                    lidIcon.setTextColor(lidClosedColor)
                }
                null -> {
                    lidValue.text = placeholder
                    lidValue.setTextColor(defaultTextColor)
                    lidIcon.setTextColor(lidClosedColor)
                }
            }
        }

        private fun updateSilent(value: Boolean?) {
            if (value == null) {
                silentView.text = context.getString(R.string.developer_case_silent_placeholder)
                silentView.setTextColor(secondaryTextColor)
                return
            }
            val label = if (value) silentOn else silentOff
            silentView.text = context.getString(R.string.developer_case_silent_label, label)
            silentView.setTextColor(defaultTextColor)
        }
    }

    private inner class LensCardController(
        val lens: MoncchichiBleService.Lens,
        private val card: MaterialCardView,
        private val voltageView: TextView,
        private val chargingIcon: TextView,
        private val chargingValue: TextView,
        private val uptimeView: TextView,
        private val firmwareView: TextView,
        private val gestureRow: View,
        private val gestureValue: TextView,
    ) {
        private val context = card.context
        private val placeholder = context.getString(R.string.developer_value_missing)
        private val yesLabel = context.getString(R.string.developer_value_yes).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val noLabel = context.getString(R.string.developer_value_no).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        private val defaultTextColor = ContextCompat.getColor(context, R.color.er_text_primary)
        private val batteryNormalColor = ContextCompat.getColor(context, R.color.batteryNormal)
        private val batteryLowColor = ContextCompat.getColor(context, R.color.batteryLow)
        private val chargingActiveColor = ContextCompat.getColor(context, R.color.chargingActive)
        private val chargingInactiveColor = ContextCompat.getColor(context, R.color.chargingInactive)
        private val firmwareHighlightColor = ContextCompat.getColor(context, R.color.firmwareHighlight)
        private val uptimeStartColor = ContextCompat.getColor(context, R.color.uptimeStart)
        private val uptimeEndColor = ContextCompat.getColor(context, R.color.uptimeEnd)
        private val evaluator = ArgbEvaluator()
        private val glowStrokeWidth = context.resources.getDimensionPixelSize(R.dimen.lens_card_glow_stroke)

        private var chargingAnimator: ObjectAnimator? = null
        private var firmwareAnimator: ValueAnimator? = null
        private var glowAnimator: ValueAnimator? = null
        private var lastFirmware: String? = null

        init {
            card.setStrokeColor(Color.TRANSPARENT)
            card.strokeWidth = 0
        }

        fun bind(snapshot: DeviceTelemetrySnapshot?) {
            val voltage = snapshot?.batteryVoltageMv
            if (voltage != null) {
                voltageView.text = String.format(Locale.US, "%d mV", voltage)
                val color = when {
                    voltage < 3500 -> batteryLowColor
                    voltage > 3600 -> batteryNormalColor
                    else -> defaultTextColor
                }
                voltageView.setTextColor(color)
            } else {
                voltageView.text = placeholder
                voltageView.setTextColor(defaultTextColor)
            }

            updateCharging(snapshot?.isCharging)

            val uptime = snapshot?.uptimeSeconds
            if (uptime != null) {
                uptimeView.text = formatDuration(uptime)
                val ratio = (uptime.coerceIn(0L, 600L).toFloat() / 600f)
                val color = evaluator.evaluate(ratio, uptimeStartColor, uptimeEndColor) as Int
                uptimeView.setTextColor(color)
            } else {
                uptimeView.text = placeholder
                uptimeView.setTextColor(defaultTextColor)
            }

            val firmware = snapshot?.firmwareVersion?.let(::formatFirmware)
            if (firmware != null) {
                firmwareView.text = firmware
                animateFirmwareIfNeeded(firmware)
            } else {
                firmwareAnimator?.cancel()
                firmwareAnimator = null
                firmwareView.text = placeholder
                firmwareView.setTextColor(defaultTextColor)
                lastFirmware = null
            }

            val gestureLabel = snapshot?.lastGesture?.let { labelForGesture(it) }
            gestureRow.isVisible = gestureLabel != null
            gestureLabel?.let { label ->
                gestureValue.text = label
            }
        }

        fun handleGesture(event: LensGestureEvent) {
            if (event.lens != lens) return
            val label = labelForGesture(event)
            gestureRow.isVisible = true
            gestureValue.text = label
            triggerGlow()
        }

        fun clear() {
            chargingAnimator?.cancel()
            chargingAnimator = null
            firmwareAnimator?.cancel()
            firmwareAnimator = null
            glowAnimator?.cancel()
            glowAnimator = null
            card.setStrokeColor(Color.TRANSPARENT)
            card.strokeWidth = 0
            chargingIcon.alpha = 1f
            firmwareView.setTextColor(defaultTextColor)
        }

        private fun updateCharging(value: Boolean?) {
            chargingAnimator?.cancel()
            chargingAnimator = null
            chargingValue.text = when (value) {
                true -> yesLabel
                false -> noLabel
                null -> placeholder
            }
            if (value == true) {
                chargingIcon.setTextColor(chargingActiveColor)
                chargingAnimator = ObjectAnimator.ofFloat(chargingIcon, View.ALPHA, 1f, 0.3f).apply {
                    duration = 1000L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else {
                chargingIcon.setTextColor(chargingInactiveColor)
                chargingIcon.alpha = 1f
            }
        }

        private fun animateFirmwareIfNeeded(version: String) {
            if (version == lastFirmware) {
                firmwareView.setTextColor(defaultTextColor)
                return
            }
            lastFirmware = version
            firmwareAnimator?.cancel()
            firmwareAnimator = ValueAnimator.ofArgb(defaultTextColor, firmwareHighlightColor, defaultTextColor).apply {
                duration = 1200L
                addUpdateListener { animator ->
                    firmwareView.setTextColor(animator.animatedValue as Int)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        firmwareView.setTextColor(defaultTextColor)
                        firmwareAnimator = null
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        firmwareView.setTextColor(defaultTextColor)
                    }
                })
                start()
            }
        }

        private fun triggerGlow() {
            glowAnimator?.cancel()
            card.strokeWidth = glowStrokeWidth
            glowAnimator = ValueAnimator.ofInt(255, 0).apply {
                duration = 600L
                addUpdateListener { animator ->
                    val alpha = animator.animatedValue as Int
                    card.setStrokeColor(ColorUtils.setAlphaComponent(Color.WHITE, alpha))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        card.setStrokeColor(Color.TRANSPARENT)
                        card.strokeWidth = 0
                        glowAnimator = null
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        card.setStrokeColor(Color.TRANSPARENT)
                        card.strokeWidth = 0
                    }
                })
                start()
            }
        }

        private fun formatFirmware(raw: String?): String? {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return null
            return if (cleaned.startsWith("v", ignoreCase = true) || !cleaned.first().isDigit()) {
                cleaned
            } else {
                "v$cleaned"
            }
        }

        private fun labelForGesture(event: LensGestureEvent): String {
            val name = event.gesture.name.ifBlank { "Gesture 0x%02X".format(Locale.US, event.gesture.code and 0xFF) }
            return "${event.lens.shortLabel} → $name"
        }
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
        consoleContainer: View,
        diagnosticsScroll: ScrollView,
        pauseButton: MaterialButton,
        mode: DeveloperMode,
        autoScrollEnabled: Boolean,
    ) {
        val context = requireContext()
        val consoleColor = ContextCompat.getColor(context, R.color.developer_console_background)
        val diagnosticsColor = ContextCompat.getColor(context, R.color.developer_diagnostics_background)
        when (mode) {
            DeveloperMode.CONSOLE -> {
                root.setBackgroundColor(consoleColor)
                consoleContainer.isVisible = true
                diagnosticsScroll.isVisible = false
                pauseButton.isVisible = !autoScrollEnabled
                if (!autoScrollEnabled && pauseButton.text.isBlank()) {
                    pauseButton.text = getString(R.string.developer_console_paused)
                }
            }
            DeveloperMode.DIAGNOSTICS -> {
                root.setBackgroundColor(diagnosticsColor)
                consoleContainer.isVisible = false
                diagnosticsScroll.isVisible = true
                pauseButton.isVisible = false
            }
        }
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

    private data class LinkSummary(val text: String, val colorRes: Int)

    private data class LinkLensInfo(val state: LinkUiState, val segment: String)

    private enum class LinkUiState(val priority: Int) {
        GOOD(0),
        WARNING(1),
        CRITICAL(2),
        UNKNOWN(0),
    }

    private data class AckSummary(val text: String, val colorRes: Int)

    private data class AckLensInfo(val state: AckUiState, val ageLabel: String)

    private enum class AckUiState(val priority: Int) {
        OK(0),
        DELAYED(1),
        STALLED(2),
    }

    private fun buildLinkSummary(snapshot: BleTelemetryRepository.Snapshot): LinkSummary {
        val lensInfos = MoncchichiBleService.Lens.values().map { lens ->
            val telemetry = when (lens) {
                MoncchichiBleService.Lens.LEFT -> snapshot.left
                MoncchichiBleService.Lens.RIGHT -> snapshot.right
            }
            val missCount = telemetry.heartbeatMissCount
            val avgLatency = telemetry.heartbeatLatencyAvgMs ?: telemetry.heartbeatLatencySnapshotMs
            val (state, icon, detail) = when {
                missCount >= LINK_CRITICAL_MISS_THRESHOLD ->
                    Triple(LinkUiState.CRITICAL, "❌", formatMissDetail(missCount))
                missCount > 0 -> Triple(LinkUiState.WARNING, "⚠️", formatMissDetail(missCount))
                avgLatency != null -> Triple(
                    LinkUiState.GOOD,
                    "✅",
                    "(${String.format(Locale.US, "%d ms", avgLatency)})",
                )
                else -> Triple(LinkUiState.UNKNOWN, "⚪", "(—)")
            }
            val segment = "${lens.shortLabel} $icon $detail"
            LinkLensInfo(state = state, segment = segment)
        }
        val worstState = lensInfos.maxByOrNull { it.state.priority }?.state ?: LinkUiState.UNKNOWN
        val colorRes = when (worstState) {
            LinkUiState.GOOD -> R.color.batteryNormal
            LinkUiState.WARNING -> R.color.chargingActive
            LinkUiState.CRITICAL -> R.color.batteryLow
            LinkUiState.UNKNOWN -> R.color.er_text_secondary
        }
        val text = lensInfos.joinToString(separator = " • ") { it.segment }
        return LinkSummary(text = "LINK: $text", colorRes = colorRes)
    }

    private fun formatMissDetail(missCount: Int): String {
        val label = if (missCount == 1) "miss" else "misses"
        return "(${missCount} $label)"
    }

    private fun buildAckSummary(
        telemetry: Map<MoncchichiBleService.Lens, DeviceTelemetrySnapshot>,
    ): AckSummary {
        val now = System.currentTimeMillis()
        val lensSummaries = MoncchichiBleService.Lens.values().map { lens ->
            val snapshot = telemetry[lens]
            val ackAt = snapshot?.lastAckTimestamp
            val delta = ackAt?.let { (now - it).coerceAtLeast(0L) }
            val state = when {
                delta == null -> AckUiState.STALLED
                delta <= ACK_OK_THRESHOLD_MS -> AckUiState.OK
                delta <= ACK_DELAY_THRESHOLD_MS -> AckUiState.DELAYED
                else -> AckUiState.STALLED
            }
            val ageLabel = delta?.let { "${TimeUnit.MILLISECONDS.toSeconds(it)} s ago" } ?: "—"
            lens to AckLensInfo(state, ageLabel)
        }
        val worstState = lensSummaries.maxByOrNull { it.second.state.priority }?.second?.state
            ?: AckUiState.STALLED
        val statusLabel = when (worstState) {
            AckUiState.OK -> "OK"
            AckUiState.DELAYED -> "Delayed"
            AckUiState.STALLED -> "Stalled"
        }
        val segments = lensSummaries.joinToString(" • ") { (lens, info) ->
            "${lens.shortLabel} ${info.ageLabel}"
        }
        val colorRes = when (worstState) {
            AckUiState.OK -> R.color.batteryNormal
            AckUiState.DELAYED -> R.color.chargingActive
            AckUiState.STALLED -> R.color.batteryLow
        }
        val text = "ACK: $statusLabel ($segments)"
        return AckSummary(text, colorRes)
    }

    private fun resolveSnapshotLineColor(log: BleTelemetryRepository.SnapshotLog): Int {
        return when (log.severity) {
            BleTelemetryRepository.SnapshotSeverity.OK -> SNAPSHOT_COLOR_OK
            BleTelemetryRepository.SnapshotSeverity.WARN -> SNAPSHOT_COLOR_WARN
            BleTelemetryRepository.SnapshotSeverity.ERROR -> SNAPSHOT_COLOR_ERROR
            BleTelemetryRepository.SnapshotSeverity.NORMAL -> SNAPSHOT_COLOR_NORMAL
        }
    }

    companion object {
        private const val POWER_HISTORY_PREVIEW = 10
        private const val ACK_OK_THRESHOLD_MS = 15_000L
        private const val ACK_DELAY_THRESHOLD_MS = 60_000L
        private const val LINK_CRITICAL_MISS_THRESHOLD = 3
        private const val CONSOLE_PAUSE_THRESHOLD = 20
        private const val CONSOLE_RESUME_THRESHOLD = 1
        private val SNAPSHOT_COLOR_NORMAL = Color.parseColor("#DDDDDD")
        private val SNAPSHOT_COLOR_OK = Color.parseColor("#9BE37B")
        private val SNAPSHOT_COLOR_WARN = Color.parseColor("#FFC107")
        private val SNAPSHOT_COLOR_ERROR = Color.parseColor("#FF6B6B")
    }
}
