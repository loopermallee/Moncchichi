package com.loopermallee.moncchichi

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
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
    private lateinit var statusView: TextView
    private lateinit var reconnectButton: Button
    private var displayService: G1DisplayService? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? G1DisplayService.LocalBinder
            displayService = binder?.getService()
            serviceBound = true
            updateStatus("✅ Connected to G1DisplayService")
            observeConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            updateStatus("⚠️ Service disconnected")
            serviceBound = false
            displayService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        // Status text
        statusView = TextView(this).apply {
            text = "🚀 Starting Moncchichi..."
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        // Reconnect button
        reconnectButton = Button(this).apply {
            text = "Reconnect"
            setBackgroundColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            isEnabled = false
            setOnClickListener {
                displayService?.requestReconnect()
                updateButtonState(G1ConnectionState.RECONNECTING)
            }
        }

        layout.addView(statusView)
        layout.addView(reconnectButton)

        setContentView(layout)

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startDisplayServiceSafely()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startDisplayServiceSafely()
        } else {
            updateStatus("❌ Missing permissions — exiting")
            finish()
        }
    }

    private fun startDisplayServiceSafely() {
        updateStatus("🔄 Starting G1DisplayService...")
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        if (!serviceBound) {
            serviceBound = bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private fun observeConnectionState() {
        uiScope.launch {
            displayService?.connectionState?.collectLatest { state ->
                when (state) {
                    G1ConnectionState.CONNECTED -> {
                        updateStatus("🟢 Connected to G1 Glasses")
                        updateButtonState(G1ConnectionState.CONNECTED)
                    }
                    G1ConnectionState.CONNECTING -> {
                        updateStatus("🟡 Connecting…")
                        updateButtonState(G1ConnectionState.CONNECTING)
                    }
                    G1ConnectionState.RECONNECTING -> {
                        updateStatus("🟠 Reconnecting…")
                        updateButtonState(G1ConnectionState.RECONNECTING)
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        updateStatus("🔴 Disconnected")
                        updateButtonState(G1ConnectionState.DISCONNECTED)
                    }
                    else -> {
                        updateStatus("⚙️ Unknown state")
                        updateButtonState(G1ConnectionState.DISCONNECTED)
                    }
                }
            }
        }
    }

    private fun updateButtonState(state: G1ConnectionState) {
        when (state) {
            G1ConnectionState.CONNECTED -> {
                reconnectButton.text = "Connected"
                reconnectButton.isEnabled = false
                reconnectButton.setBackgroundColor(Color.parseColor("#2ECC71")) // green
            }
            G1ConnectionState.RECONNECTING, G1ConnectionState.CONNECTING -> {
                reconnectButton.text = "Reconnecting…"
                reconnectButton.isEnabled = false
                reconnectButton.setBackgroundColor(Color.parseColor("#F39C12")) // orange
            }
            G1ConnectionState.DISCONNECTED -> {
                reconnectButton.text = "Reconnect"
                reconnectButton.isEnabled = true
                reconnectButton.setBackgroundColor(Color.parseColor("#E74C3C")) // red
            }
            else -> {
                reconnectButton.text = "Reconnect"
                reconnectButton.isEnabled = true
                reconnectButton.setBackgroundColor(Color.GRAY)
            }
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (serviceBound) unbindService(connection)
        super.onDestroy()
    }
}
