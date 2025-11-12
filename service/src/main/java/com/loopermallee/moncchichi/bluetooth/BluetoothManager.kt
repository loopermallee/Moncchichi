package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as SystemBluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
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
import com.loopermallee.moncchichi.core.BleNameParser
import java.util.Locale

private const val TAG = "BluetoothManager"
private const val PAIR_WINDOW_TIMEOUT_MS = 10_000L
private const val STABILITY_SAMPLE_RETENTION_MS = 5_000L
private const val STABILITY_MIN_SAMPLE_SPAN_MS = 1_500L
private const val STABILITY_MIN_SAMPLES_PER_LENS = 3
private const val STABILITY_MAX_RSSI_VARIANCE_DB = 8
private const val MAX_RSSI_SAMPLES_PER_LENS = 12

internal class BluetoothManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val systemBluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? SystemBluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = systemBluetoothManager?.adapter
    private fun bluetoothScanner(): BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val deviceManager = DeviceManager(context, scope)
    private val pairBleEnabled = true // TODO: gate via BuildConfig if needed
    private data class RegisteredHeadset(
        val orchestrator: DualLensConnectionOrchestrator,
        val leftMac: String,
        val rightMac: String,
    )

    private val headsets: MutableMap<PairKey, RegisteredHeadset> = mutableMapOf()
    private val headsetJobs: MutableMap<PairKey, Job> = mutableMapOf()

    private data class RssiSample(
        val timestamp: Long,
        val rssi: Int,
    )

    private data class LensObservation(
        val id: LensId,
        val result: ScanResult,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val rssiSamples: List<RssiSample>,
    ) {
        fun updatedWith(result: ScanResult, timestamp: Long): LensObservation {
            val appended = rssiSamples + RssiSample(timestamp, result.rssi)
            val filtered = appended.filter { timestamp - it.timestamp <= STABILITY_SAMPLE_RETENTION_MS }
            val limited = if (filtered.size > MAX_RSSI_SAMPLES_PER_LENS) {
                filtered.takeLast(MAX_RSSI_SAMPLES_PER_LENS)
            } else {
                filtered
            }
            return copy(
                result = result,
                lastSeenAt = timestamp,
                rssiSamples = limited,
            )
        }

        fun isStable(now: Long): Boolean {
            val recent = rssiSamples.filter { now - it.timestamp <= STABILITY_SAMPLE_RETENTION_MS }
            if (recent.size < STABILITY_MIN_SAMPLES_PER_LENS) {
                return false
            }
            val earliest = recent.minByOrNull { it.timestamp } ?: return false
            val latest = recent.maxByOrNull { it.timestamp } ?: return false
            if (latest.timestamp - earliest.timestamp < STABILITY_MIN_SAMPLE_SPAN_MS) {
                return false
            }
            val minRssi = recent.minOf { it.rssi }
            val maxRssi = recent.maxOf { it.rssi }
            if (maxRssi - minRssi > STABILITY_MAX_RSSI_VARIANCE_DB) {
                return false
            }
            return true
        }
    }

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

        fun isStable(now: Long): Boolean {
            if (!isComplete) {
                return false
            }
            return lenses.values.all { it.isStable(now) }
        }
    }

    private val pairWindows: MutableMap<PairKey, PairCorrelationWindow> = mutableMapOf()

    private var pairWindowDeadlineJob: Job? = null
    private var pairWindowExpiryAt: Long? = null

    private val pendingPairConnections: MutableSet<PairKey> = mutableSetOf()

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

    fun registerHeadset(pairKey: PairKey, leftMac: String, rightMac: String, orchestrator: DualLensConnectionOrchestrator) {
        headsets[pairKey] = RegisteredHeadset(orchestrator, leftMac, rightMac)
        headsetJobs.remove(pairKey)?.cancel()
        headsetStates.update { current ->
            val base = orchestrator.headset.value
            val merged = current[pairKey]?.let { existing ->
                mergeHeadsetStates(existing, base)
            } ?: base
            current + (pairKey to merged)
        }
        val job = scope.launch {
            orchestrator.headset.collectLatest { state ->
                headsetStates.update { current -> current + (pairKey to state) }
            }
        }
        headsetJobs[pairKey] = job
    }

    fun unregisterHeadset(pairKey: PairKey) {
        headsets.remove(pairKey)
        headsetJobs.remove(pairKey)?.cancel()
        headsetStates.update { current -> current - pairKey }
    }

    fun headsetFlow(pairToken: String): StateFlow<HeadsetState>? {
        return headsets[PairKey(pairToken)]?.orchestrator?.headset
    }

    fun connectHeadset(pairToken: String): Boolean {
        val key = PairKey(pairToken)
        val registered = headsets[key] ?: return false
        scope.launch { registered.orchestrator.connectHeadset(key, registered.leftMac, registered.rightMac) }
        return true
    }

    fun disconnectHeadset(pairToken: String): Boolean {
        val key = PairKey(pairToken)
        val registered = headsets[key] ?: return false
        scope.launch { registered.orchestrator.disconnectHeadset() }
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
        headsets.values.forEach { it.orchestrator.close() }
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
            val scanner = bluetoothScanner()
            if (scanner == null) {
                logScannerUnavailable("startLegacyScan")
                false
            } else {
                scanner.startScan(callback)
                true
            }
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
        val scanner = bluetoothScanner()
        if (scanner == null) {
            logScannerUnavailable("stopLegacyScan")
        } else {
            try {
                scanner.stopScan(callback)
            } catch (t: Throwable) {
                Log.e(TAG, "stopLegacyScan: failed", t)
            }
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
            val scanner = bluetoothScanner()
            if (scanner == null) {
                logScannerUnavailable("startPairScan")
                false
            } else {
                val filters = listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BluetoothConstants.UART_SERVICE_UUID))
                        .build(),
                )
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(filters, settings, callback)
                true
            }
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
            val scanner = bluetoothScanner()
            if (scanner == null) {
                logScannerUnavailable("stopPairScan")
            } else {
                try {
                    scanner.stopScan(callback)
                } catch (t: Throwable) {
                    Log.e(TAG, "stopPairScan: failed", t)
                }
            }
        }
        scanCallback = null
        scanJob?.cancel()
        scanJob = null
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        pairWindowExpiryAt = null
        pairWindows.clear()
        pendingPairConnections.clear()
    }

    private fun logScannerUnavailable(operation: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "$operation: bluetooth adapter unavailable; cannot access scanner")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "$operation: bluetooth adapter disabled; cannot access scanner")
            return
        }
        Log.w(TAG, "$operation: bluetooth scanner unavailable despite enabled adapter")
    }

    private fun resetPairCorrelation() {
        pairWindows.clear()
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        pairWindowExpiryAt = null
        pendingPairConnections.clear()
    }

    private fun handlePairScanResult(result: ScanResult) {
        val pairKey = derivePairKey(result) ?: return
        if (pendingPairConnections.contains(pairKey)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val existingWindow = pairWindows[pairKey]
        val existingObservation = existingWindow?.lenses?.get(result.device.address)
        val observation = if (existingObservation == null) {
            LensObservation(
                id = LensId(result.device.address, inferSide(result.device.name)),
                result = result,
                firstSeenAt = now,
                lastSeenAt = now,
                rssiSamples = listOf(RssiSample(now, result.rssi)),
            )
        } else {
            existingObservation.updatedWith(result, now)
        }
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
        headsetStates.update { current ->
            val base = mergeHeadsetStates(current[window.key], buildDiscoveryState(window))
            val updated = base.copy(status = HeadsetStatus.PAIRING)
            current + (window.key to updated)
        }
        val now = SystemClock.elapsedRealtime()
        if (!window.isStable(now)) {
            return
        }
        if (headsets.containsKey(window.key)) {
            Log.i(TAG, "Pair ${window.key.token} already registered; skipping duplicate discovery")
            stopPairScan()
            return
        }
        val (leftObservation, rightObservation) = selectLensObservations(window)
        if (leftObservation == null || rightObservation == null) {
            Log.w(TAG, "Pair ${window.key.token} discovered but could not resolve both lenses; ignoring")
            return
        }
        Log.i(
            TAG,
            "[PAIR] Matched headset token ${window.key.token} with L=${leftObservation.id.mac}, R=${rightObservation.id.mac}",
        )
        if (!pendingPairConnections.add(window.key)) {
            return
        }
        pairWindowExpiryAt = null
        pairWindowDeadlineJob?.cancel()
        pairWindowDeadlineJob = null
        val leftMac = leftObservation.id.mac
        val rightMac = rightObservation.id.mac
        val orchestrator = DualLensConnectionOrchestrator(
            pairKey = window.key,
            bleFactory = { lensId -> BleClientImpl(lensId) },
            scope = scope,
        )
        registerHeadset(window.key, leftMac, rightMac, orchestrator)
        headsetStates.update { current ->
            val base = mergeHeadsetStates(current[window.key], buildDiscoveryState(window))
            val updated = base.copy(status = HeadsetStatus.CONNECTING)
            current + (window.key to updated)
        }
        scope.launch {
            orchestrator.connectHeadset(window.key, leftMac, rightMac)
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
        if (!name.startsWith(BluetoothConstants.DEVICE_PREFIX, ignoreCase = true)) {
            return null
        }
        val token = BleNameParser.derivePairToken(name).ifBlank {
            result.device.address.uppercase(Locale.US)
        }
        val lens = BleNameParser.inferLensSide(name)
        Log.i(
            TAG,
            "[PAIR] Token=$token | Side=${lens.toLogLabel()} | MAC=${result.device.address}",
        )
        return PairKey(token)
    }

    private fun inferSide(name: String?): LensSide? {
        if (name.isNullOrEmpty()) {
            return null
        }
        return when (BleNameParser.inferLensSide(name)) {
            BleNameParser.Lens.LEFT -> LensSide.LEFT
            BleNameParser.Lens.RIGHT -> LensSide.RIGHT
            BleNameParser.Lens.UNKNOWN -> null
        }
    }

    private fun BleNameParser.Lens.toLogLabel(): String = when (this) {
        BleNameParser.Lens.LEFT -> "L"
        BleNameParser.Lens.RIGHT -> "R"
        BleNameParser.Lens.UNKNOWN -> "?"
    }

    private fun updateHeadsetDiscovery(window: PairCorrelationWindow) {
        val discoveryState = buildDiscoveryState(window)
        headsetStates.update { current ->
            val merged = mergeHeadsetStates(current[window.key], discoveryState)
            current + (window.key to merged)
        }
    }

    private fun buildDiscoveryState(window: PairCorrelationWindow): HeadsetState {
        val (left, right) = selectLensObservations(window)
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

    private fun selectLensObservations(window: PairCorrelationWindow): Pair<LensObservation?, LensObservation?> {
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
        return left to right
    }
}

