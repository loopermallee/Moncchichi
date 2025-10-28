package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "BluetoothManager"
private const val PAIR_WINDOW_TIMEOUT_MS = 10_000L

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

    private data class LensObservation(
        val id: LensId,
        val result: ScanResult,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
    )

    private data class PairCorrelationWindow(
        val key: PairKey,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val lenses: Map<String, LensObservation>,
    ) {
        val isComplete: Boolean
            get() = lenses.size >= 2

        fun updatedWith(observation: LensObservation): PairCorrelationWindow {
            val updated = lenses + (observation.id.mac to observation)
            return copy(
                lastSeenAt = observation.lastSeenAt,
                lenses = updated,
            )
        }
    }

    private val pairWindows: MutableMap<PairKey, PairCorrelationWindow> = mutableMapOf()

    private var pairWindowDeadlineJob: Job? = null
    private var pairWindowExpiryAt: Long? = null

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

    fun headsetFlow(pairToken: String): StateFlow<HeadsetState>? {
        return headsets[PairKey(pairToken)]?.headset
    }

    fun connectHeadset(pairToken: String): Boolean {
        val key = PairKey(pairToken)
        val orchestrator = headsets[key] ?: return false
        scope.launch { orchestrator.connectBoth() }
        return true
    }

    fun disconnectHeadset(pairToken: String): Boolean {
        val key = PairKey(pairToken)
        val orchestrator = headsets[key] ?: return false
        scope.launch { orchestrator.disconnectBoth() }
        return true
    }

    fun startPairDiscovery() {
        if (!pairBleEnabled) {
            startLegacyScan()
            return
        }
        startPairScan()
    }

    fun stopPairDiscovery() {
        if (!pairBleEnabled) {
            stopLegacyScan()
            return
        }
        stopPairScan()
    }

    fun close() {
        stopScan()
        headsetJobs.values.forEach { it.cancel() }
        headsetJobs.clear()
        headsets.clear()
        headsetStates.value = emptyMap()
    }

    fun startScan() {
        if (pairBleEnabled) {
            startPairDiscovery()
        } else {
            startLegacyScan()
        }
    }

    fun stopScan() {
        if (pairBleEnabled) {
            stopPairDiscovery()
        } else {
            stopLegacyScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyScan() {
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
            Log.e(TAG, "startLegacyScan: failed", t)
            false
        }
        if (!started) {
            scanCallback = null
            return
        }
        scanJob = scope.launch {
            kotlinx.coroutines.delay(15_000)
            stopLegacyScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLegacyScan() {
        val callback = scanCallback ?: return
        try {
            scanner?.stopScan(callback)
        } catch (t: Throwable) {
            Log.e(TAG, "stopLegacyScan: failed", t)
        }
        scanCallback = null
        scanJob?.cancel()
        scanJob = null
    }

    @SuppressLint("MissingPermission")
    private fun startPairScan() {
        if (scanCallback != null) {
            return
        }
        resetPairCorrelation()
        writableDevices.value = emptyMap()
        writableSelectedAddress.value = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handlePairScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handlePairScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "startPairScan: failed with errorCode=$errorCode")
            }
        }
        scanCallback = callback
        val started = try {
            scanner?.startScan(callback)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startPairScan: failed", t)
            false
        }
        if (!started) {
            scanCallback = null
            return
        }
        scanJob?.cancel()
        scanJob = scope.launch {
            kotlinx.coroutines.delay(30_000)
            Log.w(TAG, "pair discovery timed out; stopping scan")
            stopPairScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopPairScan() {
        val callback = scanCallback
        if (callback != null) {
            try {
                scanner?.stopScan(callback)
            } catch (t: Throwable) {
                Log.e(TAG, "stopPairScan: failed", t)
            }
        }
        scanCallback = null
        scanJob?.cancel()
        scanJob = null
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        pairWindowExpiryAt = null
        pairWindows.clear()
    }

    private fun resetPairCorrelation() {
        pairWindows.clear()
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        pairWindowExpiryAt = null
    }

    private fun handlePairScanResult(result: ScanResult) {
        val pairKey = derivePairKey(result) ?: return
        val now = SystemClock.elapsedRealtime()
        val existingWindow = pairWindows[pairKey]
        val existingObservation = existingWindow?.lenses?.get(result.device.address)
        val observation = LensObservation(
            id = LensId(result.device.address, inferSide(result.device.name)),
            result = result,
            firstSeenAt = existingObservation?.firstSeenAt ?: now,
            lastSeenAt = now,
        )
        val window = if (existingWindow == null) {
            val created = PairCorrelationWindow(
                key = pairKey,
                firstSeenAt = now,
                lastSeenAt = now,
                lenses = mapOf(result.device.address to observation),
            )
            pairWindows[pairKey] = created
            onPairWindowStarted(created)
            created
        } else {
            val updated = existingWindow.updatedWith(observation)
            pairWindows[pairKey] = updated
            updated
        }
        updateHeadsetDiscovery(window)
        if (window.isComplete) {
            onPairWindowSatisfied(window)
        }
    }

    private fun onPairWindowStarted(window: PairCorrelationWindow) {
        val expiry = pairWindowExpiryAt
        if (expiry != null) {
            if (window.firstSeenAt < expiry - PAIR_WINDOW_TIMEOUT_MS) {
                // Earlier window detected; reschedule from earliest
                pairWindowExpiryAt = window.firstSeenAt + PAIR_WINDOW_TIMEOUT_MS
                reschedulePairWindowJob()
            }
            return
        }
        pairWindowExpiryAt = window.firstSeenAt + PAIR_WINDOW_TIMEOUT_MS
        reschedulePairWindowJob()
    }

    private fun onPairWindowSatisfied(window: PairCorrelationWindow) {
        pairWindowExpiryAt = null
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        headsetStates.update { current ->
            val base = mergeHeadsetStates(current[window.key], buildDiscoveryState(window))
            val updated = base.copy(status = HeadsetStatus.PAIRING)
            current + (window.key to updated)
        }
        scope.launch {
            headsets[window.key]?.let {
                Log.i(TAG, "Pair ${window.key.token} discovered; orchestrator ready")
            }
        }
        stopPairScan()
    }

    private fun reschedulePairWindowJob() {
        val deadline = pairWindowExpiryAt ?: return
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = scope.launch {
            val now = SystemClock.elapsedRealtime()
            val delayMillis = (deadline - now).coerceAtLeast(0L)
            kotlinx.coroutines.delay(delayMillis)
            Log.d(TAG, "pair window expired; stopping scan")
            stopPairScan()
        }
    }

    private fun derivePairKey(result: ScanResult): PairKey? {
        val name = result.device.name ?: return null
        if (!name.startsWith(BluetoothConstants.DEVICE_PREFIX)) {
            return null
        }
        val rawToken = name.removePrefix(BluetoothConstants.DEVICE_PREFIX).trim()
        if (rawToken.isEmpty()) {
            return null
        }
        val withoutSide = dropTrailingSideSuffix(rawToken)
        val sanitized = withoutSide
            .split('_', '-', ' ')
            .firstOrNull { it.isNotEmpty() }
            ?: withoutSide
        if (sanitized.isEmpty()) {
            return null
        }
        val normalized = sanitized.uppercase(Locale.US)
        val token = normalized.ifEmpty { result.device.address }
        return PairKey(token)
    }

    private fun dropTrailingSideSuffix(token: String): String {
        val trimmed = token.trim()
        val lower = trimmed.lowercase(Locale.US)
        return when {
            lower.endsWith("_l") || lower.endsWith("-l") || lower.endsWith(" l") ->
                trimmed.dropLast(2).trimEnd('_', '-', ' ')
            lower.endsWith("_r") || lower.endsWith("-r") || lower.endsWith(" r") ->
                trimmed.dropLast(2).trimEnd('_', '-', ' ')
            lower.endsWith(" left") || lower.endsWith("left") ->
                trimmed.removeSuffix("left").trimEnd('_', '-', ' ')
            lower.endsWith(" right") || lower.endsWith("right") ->
                trimmed.removeSuffix("right").trimEnd('_', '-', ' ')
            else -> trimmed
        }
    }

    private fun inferSide(name: String?): LensSide? {
        if (name.isNullOrEmpty()) {
            return null
        }
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith("_l") || lower.endsWith("-l") || lower.endsWith(" left") || lower.endsWith("left") -> LensSide.LEFT
            lower.endsWith("_r") || lower.endsWith("-r") || lower.endsWith(" right") || lower.endsWith("right") -> LensSide.RIGHT
            else -> null
        }
    }

    private fun updateHeadsetDiscovery(window: PairCorrelationWindow) {
        val discoveryState = buildDiscoveryState(window)
        headsetStates.update { current ->
            val merged = mergeHeadsetStates(current[window.key], discoveryState)
            current + (window.key to merged)
        }
    }

    private fun buildDiscoveryState(window: PairCorrelationWindow): HeadsetState {
        val observations = window.lenses.values.sortedBy { it.firstSeenAt }
        var left = observations.firstOrNull { it.id.side == LensSide.LEFT }
        var right = observations.firstOrNull { it.id.side == LensSide.RIGHT }
        val remaining = observations
            .filterNot { it == left || it == right }
            .toMutableList()
        if (left == null && remaining.isNotEmpty()) {
            left = remaining.removeAt(0)
        }
        if (right == null && remaining.isNotEmpty()) {
            right = remaining.removeAt(0)
        }
        val leftState = left?.toLensState(LensSide.LEFT)
        val rightState = right?.toLensState(LensSide.RIGHT)
        return HeadsetState(
            pair = window.key,
            left = leftState,
            right = rightState,
            status = HeadsetStatus.DISCOVERING,
        )
    }

    private fun mergeHeadsetStates(existing: HeadsetState?, discovery: HeadsetState): HeadsetState {
        if (existing == null) {
            return discovery
        }
        val preferDiscovery = existing.status == HeadsetStatus.IDLE || existing.status == HeadsetStatus.DISCOVERING
        val left = if (preferDiscovery) discovery.left ?: existing.left else existing.left ?: discovery.left
        val right = if (preferDiscovery) discovery.right ?: existing.right else existing.right ?: discovery.right
        val status = when (existing.status) {
            HeadsetStatus.IDLE, HeadsetStatus.DISCOVERING -> discovery.status
            else -> existing.status
        }
        val unifiedReady = existing.unifiedReady || discovery.unifiedReady
        val weakest = discovery.weakestBatteryPct ?: existing.weakestBatteryPct
        return HeadsetState(
            pair = discovery.pair,
            left = left,
            right = right,
            status = status,
            unifiedReady = unifiedReady,
            weakestBatteryPct = weakest,
        )
    }

    private fun LensObservation.toLensState(forceSide: LensSide): LensState {
        val assignedSide = forceSide
        val idWithSide = LensId(id.mac, assignedSide)
        return LensState(
            id = idWithSide,
            status = LinkStatus.DISCONNECTED,
            lastSeenRssi = result.rssi,
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (pairBleEnabled) {
            Log.w(TAG, "connect($address) ignored while pair mode is enabled")
            return false
        }
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
        if (pairBleEnabled) {
            Log.w(TAG, "disconnect ignored while pair mode is enabled")
            return
        }
        writableSelectedAddress.value = null
        deviceManager.disconnect()
    }

    fun isConnected(): Boolean {
        if (pairBleEnabled) {
            return false
        }
        return deviceManager.isConnected()
    }

    suspend fun sendMessage(message: String): Boolean {
        if (pairBleEnabled) {
            Log.w(TAG, "sendMessage ignored while pair mode is enabled")
            return false
        }
        return deviceManager.sendText(message)
    }

    suspend fun clearScreen(): Boolean {
        if (pairBleEnabled) {
            Log.w(TAG, "clearScreen ignored while pair mode is enabled")
            return false
        }
        return deviceManager.clearScreen()
    }

    fun selectedDevice(): BluetoothDevice? {
        if (pairBleEnabled) {
            return null
        }
        val address = writableSelectedAddress.value ?: return null
        return bluetoothAdapter?.getRemoteDevice(address)
    }
}
