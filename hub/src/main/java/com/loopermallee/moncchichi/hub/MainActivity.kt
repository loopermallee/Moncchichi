package com.loopermallee.moncchichi.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.MoncchichiCrashReporter
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
    private lateinit var toggleButton: Button
    private var hud: ServiceDebugHUD? = null
    private var service: G1DisplayService? = null
    private var isBound = false
    private var connectionStateJob: Job? = null
    private var serviceBound = CompletableDeferred<Unit>()
    private var serviceIntent: Intent? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? G1DisplayService.LocalBinder ?: return
            service = localBinder.getService()
            if (service == null) return
            isBound = true
            ServiceRepository.setBinding()
            logger.debug("Activity", "${tt()} Service bound successfully")
            if (!serviceBound.isCompleted) {
                serviceBound.complete(Unit)
            }
            hud?.show("Service bound", Color.GREEN, autoHide = false)
            updateToggleButton(ServiceState.BINDING)
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
            updateToggleButton(ServiceState.DISCONNECTED)
        }
    }

    private var firstStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            setContentView(R.layout.activity_main)
            status = findViewById(R.id.status)
            toggleButton = findViewById(R.id.connection_toggle)
            serviceIntent = Intent(this, G1DisplayService::class.java)

            status.setText(R.string.status_disconnected)
            ServiceRepository.setDisconnected()
            updateToggleButton(ServiceRepository.state.value)

            toggleButton.setOnClickListener {
                when (ServiceRepository.state.value) {
                    ServiceState.BINDING, ServiceState.CONNECTED -> disconnectFromService()
                    else -> connectToService()
                }
            }

            lifecycleScope.launch {
                ServiceRepository.state.collectLatest { updateToggleButton(it) }
            }
        }.onFailure { error ->
            logger.e("Activity", "${tt()} onCreate failed", error)
            MoncchichiCrashReporter.reportNonFatal("MainActivity#onCreate", error)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (hud == null) {
            hud = ServiceDebugHUD(this)
        }
        if (isBound && service != null) {
            hud?.update("Service already bound", Color.GREEN, autoHide = false)
            return
        }

        serviceIntent = serviceIntent ?: Intent(this, G1DisplayService::class.java)
        val intent = serviceIntent ?: return
        lifecycleScope.launch {
            if (firstStart) {
                firstStart = false
                delay(750L)
            }
            runCatching {
                attemptBind(intent, 0, userInitiated = false)
            }.onFailure { error ->
                logger.e("Activity", "${tt()} Deferred bind scheduling failed", error)
                MoncchichiCrashReporter.reportNonFatal("MainActivity#onStart", error)
            }
        }
    }

    private fun connectToService() {
        if (isBound && service != null) {
            hud?.update("Service already bound", Color.GREEN, autoHide = false)
            return
        }
        val intent = serviceIntent ?: Intent(this, G1DisplayService::class.java).also {
            serviceIntent = it
        }
        hud?.show("Starting service...", Color.YELLOW, autoHide = false)
        ServiceRepository.setBinding()
        status.setText(R.string.boot_service_binding)
        ContextCompat.startForegroundService(this, intent)
        attemptBind(intent, Context.BIND_AUTO_CREATE, userInitiated = true)
    }

    private fun disconnectFromService() {
        hud?.update("Stopping service...", Color.YELLOW, autoHide = false)
        connectionStateJob?.cancel()
        connectionStateJob = null
        if (isBound) {
            runCatching { unbindService(connection) }
            isBound = false
        }
        service = null
        serviceBound = CompletableDeferred()
        serviceIntent?.let { intent -> stopService(intent) }
        ServiceRepository.setDisconnected()
        status.setText(R.string.status_disconnected)
        hud?.update("Service stopped", Color.RED, autoHide = false)
        updateToggleButton(ServiceState.DISCONNECTED)
    }

    private fun updateToggleButton(state: ServiceState) {
        if (!::toggleButton.isInitialized) {
            return
        }
        val active = state == ServiceState.BINDING || state == ServiceState.CONNECTED
        val textRes = if (active) R.string.action_disconnect else R.string.action_connect
        toggleButton.text = getString(textRes)
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

    private fun attemptBind(serviceIntent: Intent, bindFlags: Int, userInitiated: Boolean) {
        if (isBound) {
            if (userInitiated) {
                hud?.update("Service already bound", Color.GREEN, autoHide = false)
            }
            return
        }
        serviceBound = CompletableDeferred()
        lifecycleScope.launch {
            val bindResult = withTimeoutOrNull(5_000L) {
                if (!bindService(serviceIntent, connection, bindFlags)) {
                    return@withTimeoutOrNull false
                }
                serviceBound.await()
                true
            }
            when (bindResult) {
                true -> {
                    logger.debug(
                        "Activity",
                        "${tt()} Service bind established (flags=$bindFlags)"
                    )
                    if (userInitiated) {
                        hud?.update("Service bound", Color.GREEN, autoHide = false)
                    }
                }
                false -> {
                    logger.w(
                        "Activity",
                        "${tt()} bindService returned false (flags=$bindFlags)"
                    )
                    if (userInitiated) {
                        ServiceRepository.setError()
                        status.setText(R.string.boot_service_timeout)
                        hud?.update("Bind failed", Color.RED)
                    } else {
                        ServiceRepository.setDisconnected()
                        status.setText(R.string.status_disconnected)
                        hud?.update("Service idle", Color.DKGRAY, autoHide = false)
                        updateToggleButton(ServiceState.DISCONNECTED)
                    }
                }
                null -> {
                    logger.w(
                        "Activity",
                        "${tt()} Service bind timeout (flags=$bindFlags)"
                    )
                    if (userInitiated) {
                        ServiceRepository.setBinding()
                        status.text = getString(R.string.status_bind_timeout_retry)
                        hud?.update("Bind timeout", Color.RED)
                        tryBindAgain()
                    } else {
                        ServiceRepository.setError()
                        status.setText(R.string.boot_service_timeout)
                        hud?.update("Bind timeout", Color.RED)
                    }
                }
            }
        }
    }

    private fun tryBindAgain() {
        lifecycleScope.launch {
            delay(2_000)
            val intent = serviceIntent ?: Intent(this@MainActivity, G1DisplayService::class.java).also {
                serviceIntent = it
            }
            ContextCompat.startForegroundService(this@MainActivity, intent)
            attemptBind(intent, Context.BIND_AUTO_CREATE, userInitiated = true)
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
