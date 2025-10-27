package com.loopermallee.moncchichi.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loopermallee.moncchichi.service.R
import com.loopermallee.moncchichi.bluetooth.BluetoothManager
import com.loopermallee.moncchichi.bluetooth.DeviceManager
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import com.loopermallee.moncchichi.service.protocol.G1Glasses
import com.loopermallee.moncchichi.service.protocol.G1ServiceState
import com.loopermallee.moncchichi.service.protocol.IG1Service
import com.loopermallee.moncchichi.service.protocol.IG1StateCallback
import com.loopermallee.moncchichi.service.protocol.IG1ServiceClient
import com.loopermallee.moncchichi.service.protocol.ObserveStateCallback
import com.loopermallee.moncchichi.service.protocol.OperationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.collections.ArrayDeque

internal enum class DeviceSide(val prefix: String, val displayName: String, val defaultBattery: Int) {
    LEFT("left", "Left Glass", 82),
    RIGHT("right", "Right Glass", 67);

    fun buildId(address: String?, pairToken: String? = null): String {
        val suffix = pairToken?.takeIf { it.isNotBlank() }
            ?: address
                ?.takeLast(4)
                ?.replace(":", "")
                ?.lowercase(Locale.US)
            ?: "mock"
        return "$prefix-$suffix"
    }
}

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

internal data class InternalDevice(
    val id: String,
    val address: String?,
    val name: String,
    val connectionState: DeviceManager.ConnectionState,
    val batteryPercentage: Int,
    val side: DeviceSide,
    val isMock: Boolean,
    val firmwareVersion: String?,
)

private fun InternalDevice.toGlasses(): G1Glasses = G1Glasses().apply {
    id = this@toGlasses.id
    name = this@toGlasses.name
    connectionState = connectionState.toInt()
    batteryPercentage = batteryPercentage
    firmwareVersion = this@toGlasses.firmwareVersion
}

private fun defaultDevices(): Map<String, InternalDevice> =
    DeviceSide.values().associate { side ->
        val device = InternalDevice(
            id = side.buildId(null),
            address = null,
            name = side.displayName,
            connectionState = DeviceManager.ConnectionState.DISCONNECTED,
            batteryPercentage = side.defaultBattery,
            side = side,
            isMock = true,
            firmwareVersion = null,
        )
        device.id to device
    }

private val sideMarkerRegex = Regex("([_\\-\\s])(l|r|left|right)(?=[_\\-\\s]|\\b)", RegexOption.IGNORE_CASE)
private val nonIdCharactersRegex = Regex("[^a-z0-9]+")
private val repeatedHyphenRegex = Regex("-+")
private val trailingSideRegex = Regex("\\s*\\((left|right)\\)\\s*\$", RegexOption.IGNORE_CASE)

private fun identifySide(name: String?): DeviceSide? {
    if (name.isNullOrBlank()) return null
    val normalized = name.lowercase(Locale.US)
    return when {
        normalized.contains("_l_") || normalized.endsWith("_l") ||
            normalized.contains("-l-") || normalized.endsWith("-l") ||
            normalized.startsWith("left ") || normalized.contains(" left") -> DeviceSide.LEFT
        normalized.contains("_r_") || normalized.endsWith("_r") ||
            normalized.contains("-r-") || normalized.endsWith("-r") ||
            normalized.startsWith("right ") || normalized.contains(" right") -> DeviceSide.RIGHT
        else -> null
    }
}

private fun extractPairTokenFromName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val match = sideMarkerRegex.find(name)
    return if (match != null) {
        name.substring(0, match.range.first)
    } else {
        null
    }
}

private fun sanitizePairToken(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().lowercase(Locale.US)
    val replaced = nonIdCharactersRegex.replace(normalized, "-")
    val compacted = repeatedHyphenRegex.replace(replaced, "-")
    return compacted.trim('-').takeIf { it.isNotBlank() }
}

private fun stripSidePrefixFromId(id: String?): String? {
    if (id.isNullOrBlank()) return null
    for (side in DeviceSide.values()) {
        val prefix = "${side.prefix}-"
        if (id.startsWith(prefix)) {
            return id.removePrefix(prefix)
        }
    }
    return id
}

private fun formatPairDisplayName(raw: String): String {
    val words = raw
        .replace('_', ' ')
        .replace('-', ' ')
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (words.isEmpty()) {
        return raw.trim()
    }
    return words.joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    }
}

private fun extractPairDisplayBase(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return trailingSideRegex.replace(name, "").trim().takeIf { it.isNotBlank() }
}

private fun buildDevicesSnapshot(
    scanResults: List<ScanResult>,
    previousState: InternalState,
    selectedAddress: String?,
    connectionState: DeviceManager.ConnectionState,
): Map<String, InternalDevice> {
    if (scanResults.isEmpty() && previousState.devices.isEmpty()) {
        return defaultDevices()
    }

    val byAddress = scanResults.associateBy { it.device.address }
    val selectedPairToken = selectedAddress?.let { address ->
        val raw = byAddress[address]?.device?.name?.let(::extractPairTokenFromName)
        sanitizePairToken(raw)
            ?: previousState.devices.values.firstOrNull { it.address == address }
                ?.id
                ?.let(::stripSidePrefixFromId)
    }

    val assignedBySide = mutableMapOf<DeviceSide, ScanResult>()
    val unassigned = ArrayDeque<ScanResult>()
    scanResults.forEach { result ->
        val side = identifySide(result.device.name)
        if (side != null && assignedBySide[side] == null) {
            assignedBySide[side] = result
        } else {
            unassigned.addLast(result)
        }
    }

    val assignments = DeviceSide.values().map { side ->
        val previous = previousState.devices.values.firstOrNull { it.side == side }
        val result = assignedBySide[side] ?: if (unassigned.isEmpty()) null else unassigned.removeFirst()
        val address = result?.device?.address ?: previous?.address
        val rawPairToken = result?.device?.name?.let(::extractPairTokenFromName)
            ?: previous?.id?.let(::stripSidePrefixFromId)
        val pairToken = sanitizePairToken(rawPairToken)
        val resolvedId = when {
            pairToken != null -> side.buildId(address, pairToken)
            address != null -> side.buildId(address)
            previous != null -> previous.id
            else -> side.buildId(null)
        }
        val isMock = address.isNullOrBlank()
        val resolvedConnection = when {
            !isMock && address != null && address == selectedAddress -> connectionState
            !isMock && pairToken != null && selectedPairToken != null && pairToken == selectedPairToken -> connectionState
            else -> DeviceManager.ConnectionState.DISCONNECTED
        }
        val baseName = rawPairToken?.let(::formatPairDisplayName)
            ?: previous?.name?.let(::extractPairDisplayBase)
        val sideLabel = if (side == DeviceSide.LEFT) "Left" else "Right"
        val resolvedName = when {
            !baseName.isNullOrBlank() -> "$baseName ($sideLabel)"
            result?.device?.name?.isNotBlank() == true -> result.device.name
            previous?.name?.isNotBlank() == true -> previous.name
            else -> side.displayName
        }
        InternalDevice(
            id = resolvedId,
            address = address,
            name = resolvedName,
            connectionState = resolvedConnection,
            batteryPercentage = previous?.batteryPercentage ?: side.defaultBattery,
            side = side,
            isMock = isMock,
            firmwareVersion = previous?.firmwareVersion,
        )
    }
    return assignments.associateBy { it.id }
}

private fun InternalState.toState(): G1ServiceState = G1ServiceState().apply {
    status = this@toState.status.toInt()
    glasses = DeviceSide.values().mapNotNull { side ->
        this@toState.devices.values.firstOrNull { it.side == side }?.toGlasses()
    }.toTypedArray()
}

enum class ServiceStatus {
    READY,
    LOOKING,
    LOOKED,
    ERROR,
}

internal data class InternalState(
    val status: ServiceStatus = ServiceStatus.READY,
    val devices: Map<String, InternalDevice> = defaultDevices(),
    val selectedAddress: String? = null,
)

class G1Service : Service() {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothManager by lazy { BluetoothManager(this, coroutineScope) }

    val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "savedState")

    private val state: MutableStateFlow<InternalState> = MutableStateFlow(InternalState())

    override fun onCreate() {
        super.onCreate()
        Log.d("G1Service", "onCreate")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            withPermissions { ensureForegroundNotification() }
        }
        observeBluetooth()
        coroutineScope.launch {
            bluetoothManager.devices.collectLatest { results ->
                val current = state.value
                val selectedAddress = bluetoothManager.selectedAddress.value
                val connectionState = bluetoothManager.connectionState.value
                val devices = buildDevicesSnapshot(
                    scanResults = results,
                    previousState = current,
                    selectedAddress = selectedAddress,
                    connectionState = connectionState,
                )
                val hasDiscoveredDevice = devices.values.any { !it.isMock }
                val nextStatus = when {
                    current.status == ServiceStatus.LOOKING && !hasDiscoveredDevice -> ServiceStatus.LOOKING
                    hasDiscoveredDevice -> ServiceStatus.LOOKED
                    else -> ServiceStatus.READY
                }
                state.value = current.copy(
                    status = nextStatus,
                    devices = devices,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("G1Service", "onBind action=${intent?.action}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(Intent(applicationContext, G1Service::class.java))
        } else {
            applicationContext.startService(Intent(applicationContext, G1Service::class.java))
        }
        return when (intent?.action) {
            "com.loopermallee.moncchichi.service.protocol.IG1Service" -> binder
            "com.loopermallee.moncchichi.service.protocol.IG1ServiceClient" -> clientBinder
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
                val current = state.value
                val updatedDevices = current.devices.values.associate { device ->
                    val isSelected = address != null && device.address == address
                    val resolvedState = if (isSelected) connection else DeviceManager.ConnectionState.DISCONNECTED
                    val updated = device.copy(connectionState = resolvedState)
                    updated.id to updated
                }
                state.value = current.copy(
                    devices = updatedDevices,
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
        override fun observeState(callback: IG1StateCallback?) = registerStateCallback(callback)

        override fun lookForGlasses() {
            withPermissions {
                bluetoothManager.stopScan()
                state.value = state.value.copy(status = ServiceStatus.LOOKING)
                bluetoothManager.startScan()
            }
        }

        override fun connectGlasses(id: String?, callback: OperationCallback?) {
            val address = resolveDeviceAddress(id)
                ?: state.value.selectedAddress
                ?: state.value.devices.values.firstOrNull { !it.isMock }?.address
                ?: state.value.devices.values.firstOrNull()?.address
            if (address == null) {
                callback?.onResult(false)
                return
            }
            withPermissions {
                val result = bluetoothManager.connect(address)
                if (!result) {
                    callback?.onResult(false)
                } else {
                    state.value = state.value.run {
                        val updatedDevices = devices.values.associate { device ->
                            val isTarget = device.address == address
                            val updated = if (isTarget) {
                                device.copy(connectionState = DeviceManager.ConnectionState.CONNECTING)
                            } else {
                                device.copy(connectionState = DeviceManager.ConnectionState.DISCONNECTED)
                            }
                            updated.id to updated
                        }
                        copy(devices = updatedDevices, selectedAddress = address)
                    }
                    coroutineScope.launch {
                        val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
                        applicationContext.dataStore.edit { prefs ->
                            prefs[LAST_CONNECTED_ID] = address
                        }
                    }
                    callback?.onResult(true)
                }
            }
        }

        override fun connectGlassesById(deviceAddress: String?) {
            if (deviceAddress == null) {
                connectPreferredGlasses()
            } else {
                connectGlasses(deviceAddress, null)
            }
        }

        override fun disconnectGlassesById(deviceAddress: String?) {
            if (deviceAddress == null) {
                disconnectPreferredGlasses()
            } else {
                disconnectGlasses(deviceAddress, null)
            }
        }

        override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
            val address = resolveDeviceAddress(id) ?: state.value.selectedAddress
            val shouldDisconnect = address != null && state.value.selectedAddress == address
            if (shouldDisconnect) {
                bluetoothManager.disconnect()
            }
            if (address != null) {
                state.value = state.value.run {
                    val updatedDevices = devices.values.associate { device ->
                        val matches = device.address == address || device.id == id
                        val updated = if (matches) {
                            device.copy(connectionState = DeviceManager.ConnectionState.DISCONNECTED)
                        } else {
                            device
                        }
                        updated.id to updated
                    }
                    copy(devices = updatedDevices)
                }
            }
            callback?.onResult(true)
        }

        override fun displayTextPage(id: String?, page: Array<out String?>?, callback: OperationCallback?) =
            commonDisplayTextPage(page, callback)

        override fun stopDisplaying(id: String?, callback: OperationCallback?) =
            commonStopDisplaying(callback)

        override fun displayLegacyTextPage(text: String?, page: Int, flags: Int) {
            if (!text.isNullOrEmpty()) {
                commonDisplayTextPage(arrayOf(text), null)
            }
        }

        override fun stopDisplayingWithFlags(flags: Int) {
            commonStopDisplaying(null)
        }

        override fun connectPreferredGlasses() {
            val preferredAddress = state.value.selectedAddress
            if (preferredAddress != null) {
                connectGlasses(preferredAddress, null)
                return
            }
            val firstAvailable = state.value.devices.values.firstOrNull { !it.isMock && it.address != null }
            val identifier = firstAvailable?.address
            if (identifier != null) {
                connectGlasses(identifier, null)
            }
        }

        override fun disconnectPreferredGlasses() {
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

    private fun registerStateCallback(callback: IG1StateCallback?) {
        if (callback == null) return
        coroutineScope.launch {
            state.collectLatest { internalState ->
                val status = when (internalState.status) {
                    ServiceStatus.READY -> G1ServiceState.READY
                    ServiceStatus.LOOKING -> G1ServiceState.LOOKING
                    ServiceStatus.LOOKED -> G1ServiceState.LOOKED
                    ServiceStatus.ERROR -> G1ServiceState.ERROR
                }
                val glasses = DeviceSide.values().mapNotNull { side ->
                    internalState.devices.values.firstOrNull { it.side == side }?.toGlasses()
                }.toTypedArray()
                callback.onStateChanged(status, glasses)
            }
        }
    }

    private fun resolveDeviceAddress(idOrAddress: String?): String? {
        if (idOrAddress.isNullOrEmpty()) {
            return null
        }
        val current = state.value
        val fromId = current.devices[idOrAddress]?.address
        if (fromId != null) {
            return fromId
        }
        val byAddress = current.devices.values.firstOrNull { it.address == idOrAddress }?.address
        return byAddress ?: idOrAddress
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
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(notificationChannel)

        val notificationIntent = Intent(this, javaClass)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_description))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1337,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1337, notification)
        }
    }

    private fun withPermissions(block: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

        Permissions.check(
            this,
            permissions,
            null,
            null,
            object : PermissionHandler() {
                override fun onGranted() {
                    block()
                }
            }
        )
    }
}
