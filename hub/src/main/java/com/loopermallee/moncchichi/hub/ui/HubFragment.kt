package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.loopermallee.moncchichi.core.bluetooth.BluetoothStateReceiver
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HubFragment : Fragment() {
    private var bluetoothReceiver: BluetoothStateReceiver? = null
    private val vm: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.diagnostics,
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
        val deviceStatus = view.findViewById<TextView>(R.id.text_device_status)
        val deviceBattery = view.findViewById<TextView>(R.id.text_device_battery)
        val deviceFirmware = view.findViewById<TextView>(R.id.text_device_firmware)
        val deviceSignal = view.findViewById<TextView>(R.id.text_device_signal)
        val btnPair = view.findViewById<MaterialButton>(R.id.btn_pair)
        val btnDisconnect = view.findViewById<MaterialButton>(R.id.btn_disconnect)
        val btnPing = view.findViewById<MaterialButton>(R.id.btn_ping)

        btnPair.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.StartScan) }
        }
        btnDisconnect.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.Disconnect) }
        }
        btnPing.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.SendBleCommand("PING")) }
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
                    deviceName.text = device.deviceName ?: "My G1"
                    val stateLabel = when (device.state) {
                        DeviceConnState.CONNECTED -> device.connectionState ?: "Connected"
                        DeviceConnState.DISCONNECTED -> "Waiting for connection…"
                    }
                    deviceStatus.text = stateLabel
                    val glasses = device.batteryPct?.let { "$it %" } ?: "— %"
                    val case = device.caseBatteryPct?.let { "$it %" } ?: "— %"
                    deviceBattery.text = "Glasses $glasses • Case $case"
                    deviceFirmware.text = "Firmware: ${device.firmware ?: "—"}"
                    val rssi = device.signalRssi?.let { "${it} dBm" } ?: "— dBm"
                    deviceSignal.text = "Signal: $rssi"
                    val connected = device.state == DeviceConnState.CONNECTED
                    btnPair.isEnabled = !connected
                    btnDisconnect.isEnabled = connected
                    btnPing.isEnabled = connected
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val receiver = BluetoothStateReceiver(
            onOff = { vm.handleBluetoothOff() },
            onOn = { vm.handleBluetoothOn() }
        )
        bluetoothReceiver = receiver
        requireContext().registerReceiver(receiver, BluetoothStateReceiver.filter())
    }

    override fun onPause() {
        super.onPause()
        bluetoothReceiver?.let {
            runCatching { requireContext().unregisterReceiver(it) }
        }
        bluetoothReceiver = null
    }
}
