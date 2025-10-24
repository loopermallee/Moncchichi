package com.loopermallee.moncchichi.hub.tools.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.ScanResult
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Live BLE bridge that honours the existing [BleTool] contract while delegating
 * the heavy lifting to [MoncchichiBleService] and [BleTelemetryRepository].
 */
class BleToolLiveImpl(
    context: Context,
    private val service: MoncchichiBleService,
    private val telemetry: BleTelemetryRepository,
    private val scanner: BluetoothScanner,
    private val appScope: CoroutineScope,
) : BleTool {

    private val appContext = context.applicationContext
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var scanJob: Job? = null
    private var lastConnectedMac: String? = null

    init {
        telemetry.bindToService(service, appScope)
    }

    override suspend fun scanDevices(onFound: (ScanResult) -> Unit) {
        scanJob?.cancel()
        seen.clear()
        scanner.start()
        scanJob = appScope.launch {
            scanner.devices.collectLatest { devices ->
                devices.forEach { device ->
                    val address = device.address ?: return@forEach
                    if (seen.add(address)) {
                        onFound(
                            ScanResult(
                                id = address,
                                name = device.name,
                                rssi = 0, // Scanner does not expose RSSI yet; follow-up planned.
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun stopScan() {
        scanner.stop()
        scanJob?.cancel()
        scanJob = null
        seen.clear()
    }

    override suspend fun connect(deviceId: String): Boolean {
        val device = resolveDevice(deviceId) ?: return false
        val connected = service.connect(device)
        if (connected) {
            lastConnectedMac = device.address
        }
        return connected
    }

    override suspend fun disconnect() {
        service.disconnectAll()
        telemetry.reset()
        lastConnectedMac = null
    }

    override suspend fun send(command: String): String {
        val (payload, target) = mapCommand(command)
        val ok = service.send(payload = payload, target = target)
        return if (ok) "OK" else "ERR"
    }

    override suspend fun battery(): Int? {
        val snapshot = telemetry.snapshot.value
        val preferred = when (snapshot.lastLens) {
            MoncchichiBleService.Lens.LEFT -> snapshot.left.batteryPercent
                ?: snapshot.right.batteryPercent
            MoncchichiBleService.Lens.RIGHT -> snapshot.right.batteryPercent
                ?: snapshot.left.batteryPercent
            null -> null
        }
        return preferred ?: listOfNotNull(
            snapshot.left.batteryPercent,
            snapshot.right.batteryPercent,
        ).maxOrNull()
    }

    override suspend fun caseBattery(): Int? {
        val snapshot = telemetry.snapshot.value
        return snapshot.left.caseBatteryPercent
            ?: snapshot.right.caseBatteryPercent
    }

    override suspend fun firmware(): String? = telemetry.snapshot.value.firmwareVersion

    override suspend fun macAddress(): String? = lastConnectedMac

    override suspend fun signal(): Int? {
        val state = service.state.value
        return listOfNotNull(state.left.rssi, state.right.rssi).maxOrNull()
    }

    /**
     * Helper for UI layers to surface missing permission rationale before attempting a connection.
     */
    fun requiredPermissions(): List<String> {
        val required = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        return required.filter { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun resolveDevice(mac: String): BluetoothDevice? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        return try {
            adapter?.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun mapCommand(command: String): Pair<ByteArray, MoncchichiBleService.Target> {
        val normalized = command.trim().uppercase(Locale.getDefault())
        return when (normalized) {
            "PING" -> G1Packets.ping() to MoncchichiBleService.Target.Both
            "BATTERY" -> G1Packets.batteryQuery() to MoncchichiBleService.Target.Both
            "FIRMWARE" -> G1Packets.firmwareQuery() to MoncchichiBleService.Target.Both
            "REBOOT" -> G1Packets.reboot() to MoncchichiBleService.Target.Both
            "BRIGHTNESS_UP" -> G1Packets.brightness(level = 80) to MoncchichiBleService.Target.Both
            "BRIGHTNESS_DOWN" -> G1Packets.brightness(level = 30) to MoncchichiBleService.Target.Both
            "LENS_LEFT_ON" -> G1Packets.brightness(80, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left
            "LENS_LEFT_OFF" -> G1Packets.brightness(0, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left
            "LENS_RIGHT_ON" -> G1Packets.brightness(80, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right
            "LENS_RIGHT_OFF" -> G1Packets.brightness(0, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right
            "DISPLAY_RESET" -> G1Packets.textPageUtf8("") to MoncchichiBleService.Target.Both
            else -> G1Packets.textPageUtf8(command) to MoncchichiBleService.Target.Both
        }
    }
}
