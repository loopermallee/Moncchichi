package com.loopermallee.moncchichi

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var displayService: G1DisplayService? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var hintView: TextView
    private lateinit var reconnectButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var checklistLayout: LinearLayout

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var buttonPulseAnimator: ObjectAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? G1DisplayService.LocalBinder
            displayService = localBinder?.getService()
            serviceBound = true
            startFade(statusView, "🟢 Connected to service", 400)
            observeConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
            startFade(statusView, "🔴 Service disconnected", 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(40, 80, 40, 80)
        }

        val title = TextView(this).apply {
            text = "Moncchichi"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 60)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10).apply {
                bottomMargin = 50
            }
            progressDrawable?.setTint(Color.parseColor("#00BCD4"))
        }

        statusView = TextView(this).apply {
            text = "⏳ Initializing…"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        infoView = TextView(this).apply {
            text = ""
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        hintView = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.parseColor("#F1C40F"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        reconnectButton = Button(this).apply {
            text = "Reconnect"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.GRAY)
            isEnabled = false
            setPadding(40, 25, 40, 25)
            setOnClickListener {
                startChecklistAnimation()
                displayService?.requestReconnect()
            }
        }

        checklistLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            visibility = View.GONE
        }

        root.addView(title)
        root.addView(progressBar)
        root.addView(statusView)
        root.addView(infoView)
        root.addView(hintView)
        root.addView(reconnectButton)
        root.addView(checklistLayout)
        setContentView(root)

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
                        animateButton(Color.parseColor("#2ECC71"), "Connected", false)
                        infoView.text = displayService?.getConnectedDeviceName()?.let { "📶 $it" } ?: ""
                        progressBar.progress = 100
                        checklistLayout.visibility = View.GONE
                        showHint("Device link stable.")
                    }
                    G1ConnectionState.CONNECTING -> {
                        fadeStatus("🟡 Connecting…", Color.parseColor("#F1C40F"))
                        animateButton(Color.parseColor("#F1C40F"), "Connecting…", false)
                        progressBar.progress = 60
                        showHint("Attempting connection…")
                    }
                    G1ConnectionState.RECONNECTING -> {
                        fadeStatus("🟠 Reconnecting…", Color.parseColor("#F39C12"))
                        animateButton(Color.parseColor("#F39C12"), "Reconnecting…", false)
                        progressBar.progress = 40
                        startChecklistAnimation()
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        fadeStatus("🔴 Disconnected", Color.parseColor("#E74C3C"))
                        animateButton(Color.parseColor("#E74C3C"), "Reconnect", true)
                        progressBar.progress = 0
                        showHint("Bluetooth may be off or device out of range.")
                    }
                    else -> {
                        fadeStatus("⚙️ Idle", Color.GRAY)
                        animateButton(Color.GRAY, "Reconnect", true)
                        progressBar.progress = 0
                    }
                }
            }
        }
    }

    private fun fadeStatus(text: String, color: Int) {
        startFade(statusView, text, 400)
        statusView.setTextColor(color)
    }

    private fun animateButton(color: Int, label: String, enabled: Boolean) {
        reconnectButton.apply {
            setBackgroundColor(color)
            text = label
            isEnabled = enabled
            alpha = if (enabled) 1.0f else 0.6f
        }
        val shouldPulse = enabled
        if (shouldPulse) {
            if (buttonPulseAnimator == null) {
                buttonPulseAnimator = ObjectAnimator.ofFloat(reconnectButton, View.ALPHA, 0.6f, 1f).apply {
                    duration = 700
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                }
            }
            buttonPulseAnimator?.start()
        } else {
            buttonPulseAnimator?.cancel()
            reconnectButton.alpha = if (enabled) 1.0f else 0.6f
        }
    }

    private fun startChecklistAnimation() {
        checklistLayout.removeAllViews()
        checklistLayout.visibility = View.VISIBLE
        val steps = listOf(
            "Checking Bluetooth state…",
            "Detecting nearby G1 glasses…",
            "Attempting handshake…",
            "Finalizing secure connection…"
        )
        uiScope.launch {
            for (step in steps) {
                val item = TextView(this@TestActivity).apply {
                    text = "⬜ $step"
                    setTextColor(Color.LTGRAY)
                    textSize = 16f
                    setPadding(10, 10, 10, 10)
                }
                checklistLayout.addView(item)
                delay(450)
                startFade(item, "✅ ${step.replace("…", " done")}")
                delay(600)
            }
            showHint("Reconnection sequence complete (or timed out).")
        }
    }

    private fun startFade(view: TextView, text: String, duration: Long = 350) {
        val fade = AlphaAnimation(0f, 1f).apply { this.duration = duration }
        view.startAnimation(fade)
        view.text = text
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
                        fadeStatus("🔕 Bluetooth Off", Color.RED)
                        showHint("Turn Bluetooth ON to reconnect your G1 glasses.")
                        animateButton(Color.GRAY, "Reconnect", false)
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
        if (serviceBound) {
            unbindService(connection)
        }
        buttonPulseAnimator?.cancel()
        buttonPulseAnimator = null
        super.onDestroy()
    }
}
