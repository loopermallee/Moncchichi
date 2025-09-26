package io.texne.g1.basis.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loopermallee.moncchichi.bluetooth.BluetoothManager
import com.loopermallee.moncchichi.bluetooth.DeviceManager
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import io.texne.g1.basis.service.protocol.G1Glasses
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.IG1ServiceClient
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private fun DeviceManager.ConnectionState.toInt(): Int =
    when (this) {
        DeviceManager.ConnectionState.DISCONNECTED -> G1Glasses.DISCONNECTED
        DeviceManager.ConnectionState.CONNECTING -> G1Glasses.CONNECTING
        DeviceManager.ConnectionState.CONNECTED -> G1Glasses.CONNECTED
        DeviceManager.ConnectionState.DISCONNECTING -> G1Glasses.DISCONNECTING
        DeviceManager.ConnectionState.ERROR -> G1Glasses.ERROR
    }

private fun ServiceStatus.toInt(): Int =
    when (this) {
        ServiceStatus.READY -> G1ServiceState.READY
        ServiceStatus.LOOKING -> G1ServiceState.LOOKING
        ServiceStatus.LOOKED -> G1ServiceState.LOOKED
        ServiceStatus.ERROR -> G1ServiceState.ERROR
    }

private fun InternalDevice.toGlasses(): G1Glasses = G1Glasses().apply {
    id = address
    name = this@toGlasses.name
    connectionState = connectionState.toInt()
    batteryPercentage = -1
}

private fun InternalState.toState(): G1ServiceState = G1ServiceState().apply {
    status = this@toState.status.toInt()
    glasses = this@toState.devices.values.map { it.toGlasses() }.toTypedArray()
}

enum class ServiceStatus {
    READY,
    LOOKING,
    LOOKED,
    ERROR,
}

internal data class InternalDevice(
    val address: String,
    val name: String,
    val connectionState: DeviceManager.ConnectionState,
)

internal data class InternalState(
    val status: ServiceStatus = ServiceStatus.READY,
    val devices: Map<String, InternalDevice> = emptyMap(),
    val selectedAddress: String? = null,
)

class G1Service : Service() {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothManager by lazy { BluetoothManager(this, coroutineScope) }

    val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "savedState")

    private val state: MutableStateFlow<InternalState> = MutableStateFlow(InternalState())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            withPermissions { ensureForegroundNotification() }
        }
        observeBluetooth()
        coroutineScope.launch {
            bluetoothManager.devices.collectLatest { results ->
                state.value = state.value.copy(
                    status = if (results.isEmpty()) ServiceStatus.READY else ServiceStatus.LOOKED,
                    devices = results.associate { result ->
                        result.device.address to InternalDevice(
                            address = result.device.address,
                            name = result.device.name ?: result.device.address,
                            connectionState = if (bluetoothManager.selectedAddress.value == result.device.address &&
                                bluetoothManager.isConnected()
                            ) DeviceManager.ConnectionState.CONNECTED else DeviceManager.ConnectionState.DISCONNECTED,
                        )
                    },
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(Intent(applicationContext, G1Service::class.java))
        } else {
            applicationContext.startService(Intent(applicationContext, G1Service::class.java))
        }
        return when (intent?.action) {
            "io.texne.g1.basis.service.protocol.IG1Service" -> binder
            "io.texne.g1.basis.service.protocol.IG1ServiceClient" -> clientBinder
            else -> null
        }
    }

    override fun onDestroy() {
        coroutineScope.launch { bluetoothManager.disconnect() }
        super.onDestroy()
    }

    private fun observeBluetooth() {
        coroutineScope.launch {
            bluetoothManager.connectionState.collectLatest { connection ->
                val address = bluetoothManager.selectedAddress.value
                state.value = state.value.copy(
                    devices = state.value.devices.mapValues { entry ->
                        if (entry.key == address) {
                            entry.value.copy(connectionState = connection)
                        } else {
                            entry.value.copy(connectionState = DeviceManager.ConnectionState.DISCONNECTED)
                        }
                    },
                    selectedAddress = address,
                )
            }
        }
    }

    private val clientBinder = object : IG1ServiceClient.Stub() {
        override fun observeState(callback: ObserveStateCallback?) = commonObserveState(callback)
        override fun displayTextPage(id: String?, page: Array<out String?>?, callback: OperationCallback?) =
            commonDisplayTextPage(page, callback)
        override fun stopDisplaying(id: String?, callback: OperationCallback?) =
            commonStopDisplaying(callback)
    }

    private val binder = object : IG1Service.Stub() {
        override fun observeState(callback: ObserveStateCallback?) = commonObserveState(callback)

        override fun lookForGlasses() {
            if (state.value.status == ServiceStatus.LOOKING) {
                return
            }
            withPermissions {
                state.value = state.value.copy(status = ServiceStatus.LOOKING)
                bluetoothManager.startScan()
            }
        }

        override fun connectGlasses(id: String?, callback: OperationCallback?) {
            val address = id ?: state.value.selectedAddress
            if (address == null) {
                callback?.onResult(false)
                return
            }
            withPermissions {
                val result = bluetoothManager.connect(address)
                if (!result) {
                    callback?.onResult(false)
                } else {
                    coroutineScope.launch {
                        val LAST_CONNECTED_ID = androidx.datastore.preferences.core.stringPreferencesKey("last_connected_id")
                        applicationContext.dataStore.edit { prefs ->
                            prefs[LAST_CONNECTED_ID] = address
                        }
                    }
                    callback?.onResult(true)
                }
            }
        }

        override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
            bluetoothManager.disconnect()
            callback?.onResult(true)
        }

        override fun displayTextPage(id: String?, page: Array<out String?>?, callback: OperationCallback?) =
            commonDisplayTextPage(page, callback)

        override fun stopDisplaying(id: String?, callback: OperationCallback?) =
            commonStopDisplaying(callback)

        override fun connectGlasses() {
            val preferred = state.value.selectedAddress ?: state.value.devices.keys.firstOrNull()
            if (preferred != null) {
                connectGlasses(preferred, null)
            }
        }

        override fun disconnectGlasses() {
            bluetoothManager.disconnect()
        }

        override fun isConnected(): Boolean = bluetoothManager.isConnected()

        override fun sendMessage(msg: String?) {
            if (msg == null) return
            coroutineScope.launch { bluetoothManager.sendMessage(msg) }
        }
    }

    private fun commonObserveState(callback: ObserveStateCallback?) {
        if (callback == null) return
        coroutineScope.launch {
            state.collectLatest { callback.onStateChange(it.toState()) }
        }
    }

    private fun commonDisplayTextPage(page: Array<out String?>?, callback: OperationCallback?) {
        if (page.isNullOrEmpty()) {
            callback?.onResult(false)
            return
        }
        val message = page.filterNotNull().joinToString("\n")
        coroutineScope.launch {
            val result = bluetoothManager.sendMessage(message)
            callback?.onResult(result)
        }
    }

    private fun commonStopDisplaying(callback: OperationCallback?) {
        coroutineScope.launch {
            val result = bluetoothManager.clearScreen()
            callback?.onResult(result)
        }
    }

    private fun ensureForegroundNotification() {
        val channelId = "0xC0FFEE"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationChannel = NotificationChannel(
            channelId,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        notificationManager.createNotificationChannel(notificationChannel)

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_service_foreground)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, G1Service::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                0xC0FFEE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(0xC0FFEE, notification)
        }
    }

    private fun withPermissions(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            }
            Permissions.check(
                this@G1Service,
                permissions,
                "Please provide the permissions so the service can interact with the G1 glasses",
                Permissions.Options().setCreateNewTask(true),
                object : PermissionHandler() {
                    override fun onGranted() {
                        block()
                    }
                },
            )
        } else {
            block()
        }
    }
}
