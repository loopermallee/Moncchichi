package com.loopermallee.moncchichi.hub.tools.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirements
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    override suspend fun scanDevices(onFound: (ScanResult) -> Unit) {
        scanJob?.cancel()
        seen.clear()
        scanner.start()
        scanJob = appScope.launch {
            scanner.devices.collectLatest { devices ->
                devices.forEach { dev ->
                    val addr = dev.address
                    if (seen.add(addr)) {
                        onFound(
                            ScanResult(
                                id = addr,
                                name = dev.name,
                                rssi = dev.rssi,
                                timestampNanos = dev.timestampNanos,
                            )
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
        return if (service.send(payload, target)) "OK" else "ERR"
    }

    override suspend fun battery(): Int? = telemetry.snapshot.value.left.batteryPercent
        ?: telemetry.snapshot.value.right.batteryPercent

    override suspend fun caseBattery(): Int? = telemetry.snapshot.value.left.caseBatteryPercent
        ?: telemetry.snapshot.value.right.caseBatteryPercent

    override suspend fun firmware(): String? {
        val snapshot = telemetry.snapshot.value
        return snapshot.left.firmwareVersion ?: snapshot.right.firmwareVersion
    }

    override suspend fun macAddress(): String? = lastConnectedMac

    override suspend fun signal(): Int? {
        val st = service.state.value
        return listOfNotNull(st.left.rssi, st.right.rssi).maxOrNull()
    }

    fun requiredPermissions(): List<String> {
        return PermissionRequirements.forDevice()
            .map { it.permission }
            .filter {
                ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
            }
    }

    private fun resolveDevice(mac: String): BluetoothDevice? {
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        return try {
            adapter?.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun mapCommand(cmd: String): Pair<ByteArray, MoncchichiBleService.Target> {
        val c = cmd.trim().uppercase(Locale.getDefault())
        return when (c) {
            "PING" -> G1Packets.ping() to MoncchichiBleService.Target.Both
            "BATTERY" -> G1Packets.batteryQuery() to MoncchichiBleService.Target.Both
            "FIRMWARE" -> G1Packets.firmwareQuery() to MoncchichiBleService.Target.Both
            "REBOOT" -> G1Packets.reboot() to MoncchichiBleService.Target.Both
            "BRIGHTNESS_UP" -> G1Packets.brightness(80) to MoncchichiBleService.Target.Both
            "BRIGHTNESS_DOWN" -> G1Packets.brightness(30) to MoncchichiBleService.Target.Both
            "LENS_LEFT_ON" -> G1Packets.brightness(80, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left
            "LENS_LEFT_OFF" -> G1Packets.brightness(0, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left
            "LENS_RIGHT_ON" -> G1Packets.brightness(80, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right
            "LENS_RIGHT_OFF" -> G1Packets.brightness(0, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right
            "DISPLAY_RESET" -> G1Packets.textPageUtf8("") to MoncchichiBleService.Target.Both
            else -> G1Packets.textPageUtf8(cmd) to MoncchichiBleService.Target.Both
        }
    }
}
