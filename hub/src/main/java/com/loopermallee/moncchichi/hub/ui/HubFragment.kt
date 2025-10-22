package com.loopermallee.moncchichi.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnState
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.util.LogFormatter
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HubFragment : Fragment() {
    private val vm: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.speech,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.perms,
            AppLocator.tts,
            AppLocator.prefs
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val statusBar = view.findViewById<StatusBarView>(R.id.status_bar)
        val deviceName = view.findViewById<TextView>(R.id.text_device_name)
        val deviceRssi = view.findViewById<TextView>(R.id.text_device_rssi)
        val deviceBattery = view.findViewById<TextView>(R.id.text_device_battery)
        val deviceFirmware = view.findViewById<TextView>(R.id.text_device_firmware)
        val logsView = view.findViewById<TextView>(R.id.text_logs)
        val scroll = view.findViewById<NestedScrollView>(R.id.scroll_logs)
        val btnPair = view.findViewById<MaterialButton>(R.id.btn_pair)
        val btnDisconnect = view.findViewById<MaterialButton>(R.id.btn_disconnect)
        val btnPing = view.findViewById<MaterialButton>(R.id.btn_ping)
        val btnCopy = view.findViewById<MaterialButton>(R.id.button_copy_logs)

        btnPair.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.StartScan) }
        }
        btnDisconnect.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.Disconnect) }
        }
        btnPing.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.SendBleCommand("PING")) }
        }
        btnCopy.setOnClickListener {
            val text = logsView.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Moncchichi Logs", text))
                Toast.makeText(requireContext(), "Logs copied to clipboard ✅", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.assistantConn, vm.deviceConn) { assistant: AssistantConnInfo, device: DeviceConnInfo ->
                    assistant to device
                }.collectLatest { (assistant, device) ->
                    statusBar.render(assistant, device)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.deviceConn.collectLatest { device ->
                    deviceName.text = "Device: ${device.deviceName ?: "—"}"
                    deviceRssi.text = "RSSI: ${device.rssi ?: "—"}"
                    deviceBattery.text = "Battery: ${device.batteryPct ?: "—"}%"
                    deviceFirmware.text = "Firmware: ${device.firmware ?: "—"}"
                    val connected = device.state == DeviceConnState.CONNECTED
                    btnPair.isEnabled = !connected
                    btnDisconnect.isEnabled = connected
                    btnPing.isEnabled = connected
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { st ->
                    val ctx = requireContext()
                    val formatted = st.consoleLines.map { LogFormatter.format(ctx, it) }
                    logsView.text = SpannableStringBuilder().apply {
                        formatted.forEachIndexed { index, span ->
                            append(span)
                            if (index < formatted.lastIndex) {
                                append("\n")
                            }
                        }
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
