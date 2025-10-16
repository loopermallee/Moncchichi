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
import android.view.ViewGroup
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TestActivity : Activity() {

    private var serviceBound = false
    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var hintView: TextView
    private lateinit var reconnectButton: Button
    private var displayService: G1DisplayService? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bluetoothReceiver: BroadcastReceiver? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? G1DisplayService.LocalBinder
            displayService = binder?.getService()
            serviceBound = true
            updateStatus("🟢 Connected to Service")
            observeConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
            updateStatus("🔴 Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(50, 50, 50, 50)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Title
        val title = TextView(this).apply {
            text = "Moncchichi"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 60)
        }

        // Status Text
        statusView = TextView(this).apply {
            text = "⏳ Initializing..."
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        // Info Text (Device name + RSSI)
        infoView = TextView(this).apply {
            text = ""
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Hint text (Bluetooth ON/OFF or troubleshooting)
        hintView = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.parseColor("#F1C40F"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Reconnect Button
        reconnectButton = Button(this).apply {
            text = "Reconnect"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.GRAY)
            isEnabled = false
            setPadding(30, 20, 30, 20)
            setOnClickListener {
                displayService?.requestReconnect()
                showHint("🔄 Trying to reconnect...")
            }
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(infoView)
        root.addView(hintView)
        root.addView(reconnectButton)
        setContentView(root)

        registerBluetoothReceiver()
        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            perms += Manifest.permission.ACCESS_FINE_LOCATION

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        else
            startDisplayServiceSafely()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            startDisplayServiceSafely()
        else {
            updateStatus("❌ Missing permissions.")
            showHint("Please enable Bluetooth and Location permissions in Settings.")
        }
    }

    private fun startDisplayServiceSafely() {
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else startService(intent)

        if (!serviceBound)
            serviceBound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun observeConnectionState() {
        uiScope.launch {
            displayService?.connectionState?.collectLatest { state ->
                when (state) {
                    G1ConnectionState.CONNECTED -> {
                        updateStatus("🟢 Connected")
                        updateButton(Color.parseColor("#2ECC71"), false, "Connected")
                        infoView.text = displayService?.getConnectedDeviceName()?.let { "📶 $it" } ?: ""
                        showHint("Device link stable.")
                    }
                    G1ConnectionState.CONNECTING -> {
                        updateStatus("🟡 Connecting…")
                        updateButton(Color.parseColor("#F1C40F"), false, "Connecting…")
                        showHint("Please wait while connection stabilizes.")
                    }
                    G1ConnectionState.RECONNECTING -> {
                        updateStatus("🟠 Reconnecting…")
                        updateButton(Color.parseColor("#F39C12"), false, "Reconnecting…")
                        countdownRetry(5)
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        updateStatus("🔴 Disconnected")
                        updateButton(Color.parseColor("#E74C3C"), true, "Reconnect")
                        showHint("Bluetooth may be off or device out of range.")
                    }
                    else -> {
                        updateStatus("⚙️ Idle")
                        updateButton(Color.GRAY, true, "Reconnect")
                    }
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    private fun updateButton(color: Int, enabled: Boolean, text: String) {
        runOnUiThread {
            reconnectButton.apply {
                setBackgroundColor(color)
                isEnabled = enabled
                this.text = text
            }
        }
    }

    private fun countdownRetry(seconds: Int) {
        uiScope.launch {
            for (i in seconds downTo 1) {
                showHint("Retrying in ${i}s…")
                delay(1000)
            }
            showHint("Reattempting connection...")
            displayService?.requestReconnect()
        }
    }

    private fun showHint(text: String) {
        runOnUiThread { hintView.text = text }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        updateStatus("🔕 Bluetooth Off")
                        showHint("Turn Bluetooth ON to reconnect your G1 glasses.")
                        updateButton(Color.GRAY, false, "Reconnect")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        showHint("Bluetooth ON — ready to connect.")
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
        if (serviceBound) unbindService(connection)
        super.onDestroy()
    }
}
