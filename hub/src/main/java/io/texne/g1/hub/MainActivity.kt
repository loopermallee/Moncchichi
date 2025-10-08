package io.texne.g1.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.service.G1DisplayService
import com.loopermallee.moncchichi.ui.ServiceDebugHUD
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var hud: ServiceDebugHUD? = null
    private var service: G1DisplayService? = null
    private var isBound = false
    private var connectionStateJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? G1DisplayService.LocalBinder)?.getService()
            if (service == null) return
            isBound = true
            MoncchichiLogger.debug("Activity", "Service bound successfully")
            hud?.show("Service bound", Color.GREEN, autoHide = false)
            observeConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
            connectionStateJob?.cancel()
            connectionStateJob = null
            hud?.update("Service disconnected", Color.RED)
            ServiceRepository.setDisconnected()
            status.text = getString(R.string.status_disconnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        status.setText(R.string.boot_wait)
    }

    override fun onStart() {
        super.onStart()
        if (hud == null) {
            hud = ServiceDebugHUD(this)
        }
        hud?.show("Starting service...", Color.YELLOW, autoHide = false)

        if (isBound && service != null) {
            hud?.update("Service already bound", Color.GREEN, autoHide = false)
            return
        }

        val serviceIntent = Intent(this, G1DisplayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        ServiceRepository.setBinding()
        status.setText(R.string.boot_service_binding)

        lifecycleScope.launch {
            delay(800)
            val bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            MoncchichiLogger.debug("Activity", "Attempting to bind service...")
            if (!bound) {
                ServiceRepository.setError()
                status.setText(R.string.boot_service_timeout)
                hud?.update("Bind timeout", Color.RED)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hud?.destroy()
        hud = null
        MoncchichiLogger.debug("Activity", "Unbinding service (hasInstance=${'$'}{service != null})")
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        service = null
        connectionStateJob?.cancel()
        connectionStateJob = null
        ServiceRepository.setDisconnected()
    }

    private fun observeConnectionState() {
        connectionStateJob?.cancel()
        connectionStateJob = lifecycleScope.launch {
            service?.connectionState?.collect { state ->
                when (state) {
                    G1ConnectionState.CONNECTED -> {
                        ServiceRepository.setConnected()
                        status.text = getString(R.string.status_connected_green)
                        hud?.update("🟢 Connected to G1", Color.GREEN, autoHide = false)
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        ServiceRepository.setDisconnected()
                        status.text = getString(R.string.status_disconnected)
                        hud?.update("🔴 Disconnected", Color.RED, autoHide = false)
                    }
                    G1ConnectionState.RECONNECTING -> {
                        ServiceRepository.setBinding()
                        status.text = getString(R.string.status_reconnecting_yellow)
                        hud?.update("🔵 Reconnecting...", Color.CYAN, autoHide = false)
                    }
                    G1ConnectionState.WAITING_FOR_RECONNECT -> {
                        ServiceRepository.setBinding()
                        status.text = getString(R.string.status_waiting_red)
                        hud?.update("🟠 Waiting for reconnect", Color.YELLOW, autoHide = false)
                    }
                }
            }
        }
    }
}
