package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredDevice(val name: String?, val address: String)

class BluetoothScanner(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices
    private val scanning = AtomicBoolean(false)

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    }
    private val leScanner: BluetoothLeScanner? get() = btAdapter?.bluetoothLeScanner

    private val seen = LinkedHashMap<String, DiscoveredDevice>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val key = device.address
            val value = DiscoveredDevice(device.name, key)
            if (seen.put(key, value) == null) {
                scope.launch { _devices.emit(seen.values.toList()) }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(0, it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning.getAndSet(true)) return
        seen.clear()
        val scanner = leScanner
        if (scanner == null) {
            scanning.set(false)
            scope.launch { _devices.emit(emptyList()) }
            return
        }
        scanner.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning.getAndSet(false)) return
        leScanner?.stopScan(callback)
    }

    fun clear() {
        seen.clear()
        scope.launch { _devices.emit(emptyList()) }
    }

    fun isBluetoothOn(): Boolean = btAdapter?.isEnabled == true

    fun close() {
        stop()
        scope.cancel()
    }
}
