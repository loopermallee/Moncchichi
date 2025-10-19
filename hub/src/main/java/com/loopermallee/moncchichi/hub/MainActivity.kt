package com.loopermallee.moncchichi.hub

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {
    private val logger by lazy { MoncchichiLogger(this) }
    private lateinit var status: TextView
    private var hud: ServiceDebugHUD? = null
    private var service: G1DisplayService? = null
    private var isBound = false
    private var connectionStateJob: Job? = null
    private var serviceBound = CompletableDeferred<Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? G1DisplayService.LocalBinder ?: return
            service = localBinder.getService()
            if (service == null) return
            isBound = true
            logger.debug("Activity", "${tt()} Service bound successfully")
            if (!serviceBound.isCompleted) {
                serviceBound.complete(Unit)
            }
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
            lifecycleScope.launch(Dispatchers.Main) {
                status.text = getString(R.string.status_disconnected)
            }
            serviceBound = CompletableDeferred()
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

        ServiceRepository.setBinding()
        status.setText(R.string.boot_service_binding)
        val serviceIntent = Intent(this, G1DisplayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        attemptBind(serviceIntent)
    }

    override fun onStop() {
        super.onStop()
        hud?.destroy()
        hud = null
        logger.debug("Activity", "${tt()} Unbinding service (hasInstance=${service != null})")
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        service = null
        connectionStateJob?.cancel()
        connectionStateJob = null
        ServiceRepository.setDisconnected()
        if (!serviceBound.isCompleted) {
            serviceBound.completeExceptionally(IllegalStateException("Activity stopped"))
        }
    }

    private fun attemptBind(serviceIntent: Intent) {
        serviceBound = CompletableDeferred()
        lifecycleScope.launch {
            val bindResult = withTimeoutOrNull(5_000L) {
                if (!bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
                    return@withTimeoutOrNull false
                }
                serviceBound.await()
                true
            }
            when (bindResult) {
                true -> {
                    logger.debug("Activity", "${tt()} Service bind established")
                }
                false -> {
                    logger.w("Activity", "${tt()} bindService returned false")
                    ServiceRepository.setError()
                    status.setText(R.string.boot_service_timeout)
                    hud?.update("Bind failed", Color.RED)
                }
                null -> {
                    logger.w("Activity", "${tt()} Service bind timeout")
                    ServiceRepository.setBinding()
                    status.text = getString(R.string.status_bind_timeout_retry)
                    hud?.update("Bind timeout", Color.RED)
                    tryBindAgain()
                }
            }
        }
    }

    private fun tryBindAgain() {
        lifecycleScope.launch {
            delay(2_000)
            val intent = Intent(this@MainActivity, G1DisplayService::class.java)
            ContextCompat.startForegroundService(this@MainActivity, intent)
            attemptBind(intent)
        }
    }

    private fun observeConnectionState() {
        connectionStateJob?.cancel()
        connectionStateJob = lifecycleScope.launch(Dispatchers.Main) {
            service?.connectionState?.collectLatest { state ->
                logger.d("state", "${tt()} observe: $state")
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
                    G1ConnectionState.CONNECTING -> {
                        ServiceRepository.setBinding()
                        status.text = getString(R.string.status_connecting)
                        hud?.update("🟡 Connecting...", Color.YELLOW, autoHide = false)
                    }
                    G1ConnectionState.RECONNECTING -> {
                        ServiceRepository.setBinding()
                        status.text = getString(R.string.status_reconnecting_yellow)
                        hud?.update("🔵 Reconnecting...", Color.CYAN, autoHide = false)
                    }
                    else -> {
                        logger.w("Activity", "${tt()} Unknown state $state")
                        status.text = getString(R.string.status_unknown)
                        hud?.update("⚪ Unknown", Color.LTGRAY, autoHide = false)
                    }
                }
            }
        }
    }

    private fun tt() = "[${Thread.currentThread().name}]"
}
