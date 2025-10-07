package io.texne.g1.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var bound = false
    private var binder: G1DisplayService.G1Binder? = null
    private var connectionStateJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            binder = service as? G1DisplayService.G1Binder
            ServiceRepository.setConnected()
            status.setText(R.string.boot_service_connected)
            observeConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            binder = null
            ServiceRepository.setDisconnected()
            connectionStateJob?.cancel()
            connectionStateJob = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        status.setText(R.string.boot_wait)
        lifecycleScope.launch {
            ServiceRepository.setBinding()
            status.setText(R.string.boot_service_binding)
            val ok = withTimeoutOrNull(5_000L) { bindServiceAwait() } ?: false
            if (!ok) {
                ServiceRepository.setError()
                status.setText(R.string.boot_service_timeout)
            }
        }
    }

    private suspend fun bindServiceAwait(): Boolean = suspendCancellableCoroutine { cont ->
        val intent = Intent(this, G1DisplayService::class.java)
        val started = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!started) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        lifecycleScope.launch {
            repeat(60) {
                if (bound) {
                    if (!cont.isCompleted) cont.resume(true)
                    return@launch
                }
                delay(50)
            }
            if (!cont.isCompleted) cont.resume(bound)
        }
        cont.invokeOnCancellation {
            if (bound) {
                unbindService(serviceConnection)
                bound = false
                binder = null
                ServiceRepository.setDisconnected()
                connectionStateJob?.cancel()
                connectionStateJob = null
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
            binder = null
            ServiceRepository.setDisconnected()
        }
        connectionStateJob?.cancel()
        connectionStateJob = null
        super.onDestroy()
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
