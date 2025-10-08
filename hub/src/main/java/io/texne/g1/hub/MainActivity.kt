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
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.service.G1DisplayService
import com.loopermallee.moncchichi.ui.ServiceDebugHUD
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var isBound = false
    private var binder: G1DisplayService.LocalBinder? = null
    private var service: G1DisplayService? = null
    private var connectionStateJob: Job? = null
    private var readinessJob: Job? = null
    private var hud: ServiceDebugHUD? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? G1DisplayService.LocalBinder ?: return
            binder = localBinder
            this@MainActivity.service = localBinder.getService()
            isBound = true
            MoncchichiLogger.debug("Activity", "Service bound successfully")
            readinessJob?.cancel()
            readinessJob = lifecycleScope.launch {
                val ready = withTimeoutOrNull(5_000L) { localBinder.readiness.first { it } }
                if (ready == true) {
                    ServiceRepository.setConnected()
                    status.setText(R.string.boot_service_connected)
                    observeConnectionState()
                } else {
                    ServiceRepository.setError()
                    status.setText(R.string.boot_service_timeout)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            binder = null
            service = null
            ServiceRepository.setDisconnected()
            connectionStateJob?.cancel()
            connectionStateJob = null
            readinessJob?.cancel()
            readinessJob = null
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
        hud?.show("Starting service...", Color.YELLOW)

        if (isBound) {
            hud?.show("Service already bound", Color.GREEN)
            return
        }
        val serviceIntent = Intent(this, G1DisplayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        ServiceRepository.setBinding()
        status.setText(R.string.boot_service_binding)
        lifecycleScope.launch {
            delay(800)
            val bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            MoncchichiLogger.debug("Activity", "Attempting to bind service...")
            if (!bound) {
                ServiceRepository.setError()
                status.setText(R.string.boot_service_timeout)
                hud?.show("Bind timeout", Color.RED)
            } else {
                hud?.show("Service bound", Color.GREEN)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hud?.hide()
        hud = null
        MoncchichiLogger.debug("Activity", "Unbinding service (hasInstance=${'$'}{service != null})")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        binder = null
        service = null
        connectionStateJob?.cancel()
        connectionStateJob = null
        readinessJob?.cancel()
        readinessJob = null
        ServiceRepository.setDisconnected()
    }

    private fun observeConnectionState() {
        val g1Binder = binder ?: return
        connectionStateJob?.cancel()
        connectionStateJob = lifecycleScope.launch {
            g1Binder.stateFlow.collectLatest { state ->
                status.text = when (state) {
                    G1ConnectionState.CONNECTED -> getString(R.string.status_connected_green)
                    G1ConnectionState.RECONNECTING -> getString(R.string.status_reconnecting_yellow)
                    G1ConnectionState.WAITING_FOR_RECONNECT -> getString(R.string.status_waiting_red)
                    G1ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
                }
            }
        }
    }
}
