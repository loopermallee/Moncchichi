package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.loopermallee.moncchichi.hub.R

class HubFragment : Fragment() {
    private val vm: SharedBleViewModel by activityViewModels()

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

        vm.isConnected.observe(viewLifecycleOwner) { connected ->
            status.text = if (connected) "Connected" else "Not connected"
            btnPair.isEnabled = !connected
            btnDisconnect.isEnabled = connected
            btnPing.isEnabled = connected
        }
        vm.deviceName.observe(viewLifecycleOwner) { n ->
            name.text = "Device: $n"
        }

        btnPair.setOnClickListener { vm.startScanAndConnect() }
        btnDisconnect.setOnClickListener { vm.disconnect() }
        btnPing.setOnClickListener { vm.sendDemoPing() }
    }
}
