package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
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
            AppLocator.perms
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
        val status = view.findViewById<TextView>(R.id.text_status)
        val name = view.findViewById<TextView>(R.id.text_device)
        val btnPair = view.findViewById<Button>(R.id.btn_pair)
        val btnDisconnect = view.findViewById<Button>(R.id.btn_disconnect)
        val btnPing = view.findViewById<Button>(R.id.btn_ping)

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
                vm.state.collectLatest { st ->
                    val connected = st.device.isConnected
                    status.text = if (connected) "Connected" else "Not connected"
                    val deviceName = st.device.name ?: st.device.id ?: "(none)"
                    val rssi = st.device.rssi?.let { " RSSI ${it}dBm" } ?: ""
                    val battery = st.device.battery?.let { " | Battery ${it}%" } ?: ""
                    name.text = "Device: $deviceName$rssi$battery"
                    btnPair.isEnabled = !connected
                    btnDisconnect.isEnabled = connected
                    btnPing.isEnabled = connected
                }
            }
        }
    }
}
