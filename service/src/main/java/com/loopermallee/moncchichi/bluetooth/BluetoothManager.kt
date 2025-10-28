package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "BluetoothManager"

internal class BluetoothManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val systemBluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? SystemBluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = systemBluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val deviceManager = DeviceManager(context, scope)
    private val pairBleEnabled = true // TODO: gate via BuildConfig if needed
    private val headsets: MutableMap<PairKey, HeadsetOrchestrator> = mutableMapOf()
    private val headsetJobs: MutableMap<PairKey, Job> = mutableMapOf()

    private val headsetStates = MutableStateFlow<Map<PairKey, HeadsetState>>(emptyMap())
    val headsetsFlow: StateFlow<Map<PairKey, HeadsetState>> = headsetStates.asStateFlow()

    private val writableDevices = MutableStateFlow<Map<String, ScanResult>>(emptyMap())
    private val readableDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val devices: StateFlow<List<ScanResult>> = readableDevices.asStateFlow()

    init {
        scope.launch {
            writableDevices.collect { map ->
                readableDevices.value = map.values.sortedBy { it.device.name ?: it.device.address }
            }
        }
    }

    private val writableSelectedAddress = MutableStateFlow<String?>(null)
    val selectedAddress: StateFlow<String?> = writableSelectedAddress.asStateFlow()

    val connectionState = deviceManager.connectionState

    private var scanCallback: ScanCallback? = null
    private var scanJob: Job? = null

    fun registerHeadset(orchestrator: HeadsetOrchestrator) {
        val key = orchestrator.headset.value.pair
        headsets[key] = orchestrator
        headsetJobs.remove(key)?.cancel()
        headsetStates.update { current -> current + (key to orchestrator.headset.value) }
        val job = scope.launch {
            orchestrator.headset.collectLatest { state ->
                headsetStates.update { current -> current + (key to state) }
            }
        }
        headsetJobs[key] = job
    }

    fun unregisterHeadset(pairKey: PairKey) {
        headsets.remove(pairKey)
        headsetJobs.remove(pairKey)?.cancel()
        headsetStates.update { current -> current - pairKey }
    }

    fun getHeadsetState(pairToken: String): HeadsetState? {
        return headsetStates.value[PairKey(pairToken)]
    }

    fun startPairDiscovery() {
        if (!pairBleEnabled) {
            startScan()
            return
        }
        startScan()
    }

    fun stopPairDiscovery() {
        if (!pairBleEnabled) {
            stopScan()
            return
        }
        stopScan()
    }

    fun close() {
        stopScan()
        headsetJobs.values.forEach { it.cancel() }
        headsetJobs.clear()
        headsets.clear()
        headsetStates.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanCallback != null) {
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.name.isNullOrEmpty() &&
                    result.device.name.startsWith(BluetoothConstants.DEVICE_PREFIX)
                ) {
                    writableDevices.value = writableDevices.value.plus(result.device.address to result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "startScan: failed with errorCode=$errorCode")
            }
        }
        scanCallback = callback
        val started = try {
            scanner?.startScan(callback)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startScan: failed", t)
            false
        }
        if (!started) {
            scanCallback = null
            return
        }
        scanJob = scope.launch {
            kotlinx.coroutines.delay(15_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        try {
            scanner?.stopScan(callback)
        } catch (t: Throwable) {
            Log.e(TAG, "stopScan: failed", t)
        }
        scanCallback = null
        scanJob?.cancel()
        scanJob = null
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.w(TAG, "connect: no device for $address")
            return false
        }
        writableSelectedAddress.value = address
        deviceManager.connect(device)
        return true
    }

    fun disconnect() {
        writableSelectedAddress.value = null
        deviceManager.disconnect()
    }

    fun isConnected(): Boolean = deviceManager.isConnected()

    suspend fun sendMessage(message: String): Boolean {
        return deviceManager.sendText(message)
    }

    suspend fun clearScreen(): Boolean {
        return deviceManager.clearScreen()
    }

    fun selectedDevice(): BluetoothDevice? {
        val address = writableSelectedAddress.value ?: return null
        return bluetoothAdapter?.getRemoteDevice(address)
    }
}
