package com.loopermallee.moncchichi

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TestActivity : Activity() {

    private var serviceBound = false
    private var displayService: G1DisplayService? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var statusView: TextView
    private lateinit var rssiLabel: TextView
    private lateinit var discoveryLabel: TextView
    private lateinit var discoveryTable: TextView
    private lateinit var troubleshootingLabel: TextView
    private lateinit var reconnectButton: Button

    private var bluetoothReceiver: BroadcastReceiver? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? G1DisplayService.LocalBinder
            displayService = localBinder?.getService()
            serviceBound = true
            startFade(statusView, "🟢 Service linked", 400)
            observeConnectionState()
            observeRssi()
            observeNearbyDevices()
            displayService?.getLastDeviceAddress()?.let {
                showHint("Auto-reconnecting to $it…")
                displayService?.requestReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
            startFade(statusView, "🔴 Service disconnected", 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.BLACK)
                setPadding(30, 120, 30, 60)

                statusView = TextView(context).apply {
                    text = "⏳ Service starting…"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, 20)
                }

                val header = TextView(context).apply {
                    text = "💠 Soul Tether"
                    textSize = 26f
                    setTextColor(Color.CYAN)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    alpha = 0f
                    animate().alpha(1f).setDuration(1000).start()
                }
                addView(header)

                addView(statusView)

                rssiLabel = TextView(context).apply {
                    text = "📶 Signal: -- dBm"
                    setTextColor(Color.LTGRAY)
                    textSize = 16f
                    setPadding(0, 20, 0, 20)
                }
                addView(rssiLabel)

                discoveryLabel = TextView(context).apply {
                    text = "🔍 Scanning for nearby devices..."
                    textSize = 16f
                    setTextColor(Color.GRAY)
                }
                addView(discoveryLabel)

                discoveryTable = TextView(context).apply {
                    text = "No devices detected"
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 10, 0, 30)
                }
                addView(discoveryTable)

                reconnectButton = Button(context).apply {
                    text = "Reconnect"
                    setBackgroundColor(Color.DKGRAY)
                    setTextColor(Color.WHITE)
                    setPadding(20, 10, 20, 10)
                    setOnClickListener {
                        displayService?.requestReconnect()
                        displayService?.requestNearbyRescan()
                        displayService?.getLastDeviceAddress()?.let { last ->
                            showHint("Attempting to tether with $last…")
                        }
                    }
                }
                addView(reconnectButton)

                troubleshootingLabel = TextView(context).apply {
                    text = "🧠 Troubleshooting: Checking..."
                    textSize = 16f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 30, 0, 0)
                }
                addView(troubleshootingLabel)
            }
        )

        registerBluetoothReceiver()
        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val perms = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startDisplayServiceSafely()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startDisplayServiceSafely()
        } else {
            showHint("Please enable Bluetooth permissions.")
        }
    }

    private fun startDisplayServiceSafely() {
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!serviceBound) {
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private fun observeConnectionState() {
        uiScope.launch {
            displayService?.connectionState?.collectLatest { state ->
                when (state) {
                    G1ConnectionState.CONNECTED -> {
                        fadeStatus("🟢 Connected", Color.parseColor("#2ECC71"))
                        reconnectButton.isEnabled = true
                        reconnectButton.setBackgroundColor(Color.parseColor("#2ECC71"))
                        reconnectButton.text = "Connected"
                        reconnectButton.alpha = 0.8f
                        showHint("🧠 Troubleshooting: Link stable to ${displayService?.getConnectedDeviceName() ?: "device"}.")
                    }
                    G1ConnectionState.CONNECTING -> {
                        fadeStatus("🟡 Connecting…", Color.parseColor("#F1C40F"))
                        reconnectButton.isEnabled = false
                        reconnectButton.setBackgroundColor(Color.parseColor("#F1C40F"))
                        reconnectButton.text = "Connecting…"
                        reconnectButton.alpha = 0.6f
                        showHint("🧠 Troubleshooting: Negotiating link…")
                    }
                    G1ConnectionState.RECONNECTING -> {
                        fadeStatus("🟠 Reconnecting…", Color.parseColor("#F39C12"))
                        reconnectButton.isEnabled = false
                        reconnectButton.setBackgroundColor(Color.parseColor("#F39C12"))
                        reconnectButton.text = "Reconnecting…"
                        reconnectButton.alpha = 0.6f
                        showHint("🧠 Troubleshooting: Recovery in progress…")
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        fadeStatus("🔴 Disconnected", Color.parseColor("#E74C3C"))
                        reconnectButton.isEnabled = true
                        reconnectButton.setBackgroundColor(Color.DKGRAY)
                        reconnectButton.text = "Reconnect"
                        reconnectButton.alpha = 1f
                        showHint("🧠 Troubleshooting: Bluetooth may be off or device out of range.")
                    }
                    else -> {
                        fadeStatus("⚙️ Idle", Color.GRAY)
                        reconnectButton.isEnabled = true
                        reconnectButton.setBackgroundColor(Color.DKGRAY)
                        reconnectButton.text = "Reconnect"
                        reconnectButton.alpha = 1f
                        showHint("🧠 Troubleshooting: Awaiting instructions…")
                    }
                }
            }
        }
    }

    private fun observeRssi() {
        uiScope.launch {
            displayService?.getRssiFlow()?.collectLatest { rssi ->
                if (rssi != null) {
                    val bars = when {
                        rssi > -60 -> "🟩🟩🟩 Strong"
                        rssi > -75 -> "🟩🟩 Medium"
                        rssi > -90 -> "🟩 Weak"
                        else -> "⬜ No signal"
                    }
                    rssiLabel.text = "📶 $bars ($rssi dBm)"
                } else {
                    rssiLabel.text = "📶 Signal: -- dBm"
                }
            }
        }
    }

    private fun observeNearbyDevices() {
        uiScope.launch {
            displayService?.getNearbyDevicesFlow()?.collectLatest { devices ->
                if (devices.isNotEmpty()) {
                    discoveryLabel.text = "✅ ${devices.size} devices detected"
                    discoveryTable.text = devices.joinToString("\n") { "• $it" }
                } else {
                    discoveryLabel.text = "❌ No nearby devices"
                    discoveryTable.text = ""
                }
            }
        }
    }

    private fun fadeStatus(text: String, color: Int) {
        startFade(statusView, text, 400)
        statusView.setTextColor(color)
    }

    private fun startFade(view: TextView, text: String, duration: Long = 350) {
        val fade = AlphaAnimation(0f, 1f).apply { this.duration = duration }
        view.startAnimation(fade)
        view.text = text
    }

    private fun showHint(text: String) {
        runOnUiThread { startFade(troubleshootingLabel, text, 500) }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        fadeStatus("🔕 Bluetooth Off", Color.RED)
                        reconnectButton.isEnabled = false
                        reconnectButton.setBackgroundColor(Color.DKGRAY)
                        reconnectButton.text = "Reconnect"
                        reconnectButton.alpha = 0.6f
                        showHint("🧠 Troubleshooting: Turn Bluetooth ON to reconnect your G1 glasses.")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        showHint("🧠 Troubleshooting: Bluetooth ON — scanning…")
                        displayService?.requestReconnect()
                    }
                }
            }
        }
        registerReceiver(receiver, filter)
        bluetoothReceiver = receiver
    }

    override fun onDestroy() {
        uiScope.cancel()
        bluetoothReceiver?.let { unregisterReceiver(it) }
        bluetoothReceiver = null
        if (serviceBound) {
            unbindService(connection)
        }
        super.onDestroy()
    }
}
