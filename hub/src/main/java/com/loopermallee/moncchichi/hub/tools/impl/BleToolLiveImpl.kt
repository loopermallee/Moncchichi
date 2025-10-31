package com.loopermallee.moncchichi.hub.tools.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.bluetooth.BluetoothConstants
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.DiscoveredDevice
import com.loopermallee.moncchichi.bluetooth.G1Packets
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import com.loopermallee.moncchichi.core.BleNameParser
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirements
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BleToolLive"

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
    private val pairInventory = ConcurrentHashMap<String, PairInventory>()
    private val addressToPairToken = ConcurrentHashMap<String, String>()
    private val addressToSlot = ConcurrentHashMap<String, LensSlot>()
    private val addressIsEvenPair = ConcurrentHashMap<String, Boolean>()

    override suspend fun scanDevices(onFound: (ScanResult) -> Unit) {
        scanJob?.cancel()
        seen.clear()
        val hasBluetooth = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasBluetooth || !hasLocation) {
            Toast.makeText(
                appContext,
                "🧸 Moncchichi needs Bluetooth and Location access to find your glasses.",
                Toast.LENGTH_LONG,
            ).show()
            Log.w(TAG, "Scan aborted — missing BLE or Location permissions")
            return
        }

        try {
            scanner.start()
        } catch (e: SecurityException) {
            Toast.makeText(
                appContext,
                "🧸 Moncchichi can’t start scanning — please enable Bluetooth permissions.",
                Toast.LENGTH_LONG,
            ).show()
            Log.e(TAG, "Failed to start scanner", e)
            return
        }
        scanJob = appScope.launch {
            scanner.devices.collectLatest { devices ->
                devices.forEach { dev ->
                    val descriptor = describeDevice(dev.name, dev.address)
                    updateInventory(dev, descriptor)
                    val addr = dev.address
                    if (seen.add(addr)) {
                        val pairToken = descriptor.token
                        val slot = descriptor.slot ?: addressToSlot[addr]
                        val lens = slot?.toLens()
                        onFound(
                            ScanResult(
                                id = addr,
                                name = dev.name,
                                rssi = dev.rssi,
                                pairToken = pairToken,
                                lens = lens,
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
        val descriptor = describeDevice(device.name, device.address)
        cacheDescriptor(device.address, descriptor)
        val pairKey = addressToPairToken[device.address] ?: descriptor.token
        val primarySlot = determineSlot(descriptor, pairInventory[pairKey], device.address)
        addressToSlot[device.address] = primarySlot
        ensurePairRecord(pairKey, primarySlot, device.name, device.address)

        val expectsCompanion = addressIsEvenPair[device.address] == true
        val companionSlot = primarySlot.opposite()
        var companionRecord = pairInventory[pairKey]?.lens(companionSlot)
        if (companionRecord == null && expectsCompanion) {
            companionRecord = awaitCompanion(pairKey, companionSlot, COMPANION_SCAN_TIMEOUT_MS)
        }
        val companionMissing = expectsCompanion && companionRecord == null

        val leftAddress = when (primarySlot) {
            LensSlot.LEFT -> device.address
            LensSlot.RIGHT -> companionRecord?.address
        }
        val rightAddress = when (primarySlot) {
            LensSlot.LEFT -> companionRecord?.address
            LensSlot.RIGHT -> device.address
        }

        val leftDevice = leftAddress?.let { resolveDevice(it) }
        val rightDevice = rightAddress?.let { resolveDevice(it) }

        val connectionOrder = if (primarySlot == LensSlot.RIGHT) {
            listOfNotNull(rightDevice?.let { it to LensSlot.RIGHT }, leftDevice?.let { it to LensSlot.LEFT })
        } else {
            listOfNotNull(leftDevice?.let { it to LensSlot.LEFT }, rightDevice?.let { it to LensSlot.RIGHT })
        }

        val outcomes = mutableMapOf<LensSlot, Boolean>()
        connectionOrder.forEach { (targetDevice, slot) ->
            val ok = connectLens(targetDevice, slot)
            outcomes[slot] = ok
        }

        val leftConnected = outcomes[LensSlot.LEFT] == true
        val rightConnected = outcomes[LensSlot.RIGHT] == true

        return when {
            leftDevice != null && rightDevice != null -> leftConnected && rightConnected
            companionMissing -> false
            primarySlot == LensSlot.LEFT -> leftConnected
            primarySlot == LensSlot.RIGHT -> rightConnected
            else -> leftConnected || rightConnected
        }
    }

    override suspend fun disconnect() {
        service.disconnectAll()
        telemetry.reset()
        lastConnectedMac = null
    }

    override suspend fun send(command: String): String {
        val frames = Companion.mapCommand(command)
        frames.forEach { (payload, target) ->
            if (!service.send(payload, target)) {
                return "ERR"
            }
        }
        return "OK"
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

    internal companion object {
        private const val COMPANION_SCAN_TIMEOUT_MS = 8000L

        fun mapCommand(cmd: String): List<Pair<ByteArray, MoncchichiBleService.Target>> {
            val c = cmd.trim().uppercase(Locale.getDefault())
            return when (c) {
                "PING" -> listOf(G1Packets.ping() to MoncchichiBleService.Target.Both)
                "BATTERY" -> listOf(G1Packets.batteryQuery() to MoncchichiBleService.Target.Both)
                "FIRMWARE" -> listOf(G1Packets.firmwareQuery() to MoncchichiBleService.Target.Both)
                "REBOOT" -> listOf(G1Packets.reboot() to MoncchichiBleService.Target.Both)
                "BRIGHTNESS_UP" ->
                    listOf(
                        G1Packets.brightness(80, G1Packets.BrightnessTarget.RIGHT) to
                            MoncchichiBleService.Target.Right
                    )
                "BRIGHTNESS_DOWN" ->
                    listOf(
                        G1Packets.brightness(30, G1Packets.BrightnessTarget.RIGHT) to
                            MoncchichiBleService.Target.Right
                    )
                "LENS_LEFT_ON" -> listOf(G1Packets.brightness(80, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left)
                "LENS_LEFT_OFF" -> listOf(G1Packets.brightness(0, G1Packets.BrightnessTarget.LEFT) to MoncchichiBleService.Target.Left)
                "LENS_RIGHT_ON" -> listOf(G1Packets.brightness(80, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right)
                "LENS_RIGHT_OFF" -> listOf(G1Packets.brightness(0, G1Packets.BrightnessTarget.RIGHT) to MoncchichiBleService.Target.Right)
                "DISPLAY_RESET" -> G1Packets.textPagesUtf8("").map { it to MoncchichiBleService.Target.Both }
                else -> G1Packets.textPagesUtf8(cmd).map { it to MoncchichiBleService.Target.Both }
            }
        }
    }

    private suspend fun awaitCompanion(
        pairToken: String,
        slot: LensSlot,
        timeoutMs: Long,
    ): LensRecord? {
        val shouldStopAfter = scanJob == null
        if (shouldStopAfter) {
            scanner.start()
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                scanner.devices
                    .onEach { list -> list.forEach { updateInventory(it) } }
                    .map { pairInventory[pairToken]?.lens(slot) }
                    .filterNotNull()
                    .first()
            }
        } finally {
            if (shouldStopAfter) {
                scanner.stop()
            }
        }
    }

    private suspend fun connectLens(device: BluetoothDevice, slot: LensSlot): Boolean {
        val connected = service.connect(device, slot.toLens())
        if (connected) {
            lastConnectedMac = device.address
        }
        return connected
    }

    private fun updateInventory(
        device: DiscoveredDevice,
        descriptorOverride: PairDescriptor? = null,
    ) {
        val now = System.currentTimeMillis()
        val descriptor = descriptorOverride ?: describeDevice(device.name, device.address)
        cacheDescriptor(device.address, descriptor)
        val record = LensRecord(
            name = device.name,
            address = device.address,
            rssi = device.rssi,
            timestampNanos = device.timestampNanos,
            lastSeenMillis = now,
        )
        pairInventory.compute(descriptor.token) { _, existing ->
            val current = existing ?: PairInventory(descriptor.token)
            val slot = determineSlot(descriptor, current, device.address)
            addressToSlot[device.address] = slot
            current.withLens(slot, record, now)
        }
    }

    private fun ensurePairRecord(
        pairToken: String,
        slot: LensSlot,
        name: String?,
        address: String,
    ) {
        pairInventory.compute(pairToken) { _, existing ->
            val current = existing ?: PairInventory(pairToken)
            if (current.lens(slot) != null) {
                current
            } else {
                val now = System.currentTimeMillis()
                val record = LensRecord(
                    name = name,
                    address = address,
                    rssi = 0,
                    timestampNanos = null,
                    lastSeenMillis = now,
                )
                current.withLens(slot, record, now)
            }
        }
    }

    private fun describeDevice(name: String?, address: String): PairDescriptor {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed.startsWith(BluetoothConstants.DEVICE_PREFIX, ignoreCase = true)) {
            val token = BleNameParser.derivePairToken(trimmed).ifBlank { address.uppercase(Locale.US) }
            val lens = BleNameParser.inferLensSide(trimmed)
            Log.i(TAG, "[PAIR] Token=$token | Side=${lens.toLogLabel()} | MAC=$address")
            return PairDescriptor(token, lens.toSlot(), isEvenPair = true)
        }
        val lens = trimmed.takeIf { it.isNotEmpty() }?.let(BleNameParser::inferLensSide) ?: BleNameParser.Lens.UNKNOWN
        val slot = lens.toSlot()
        if (lens != BleNameParser.Lens.UNKNOWN) {
            Log.i(TAG, "[PAIR] Token=${address.uppercase(Locale.US)} | Side=${lens.toLogLabel()} | MAC=$address")
        }
        val token = address.uppercase(Locale.US)
        return PairDescriptor(token, slot, isEvenPair = false)
    }

    private fun cacheDescriptor(address: String, descriptor: PairDescriptor) {
        addressToPairToken[address] = descriptor.token
        addressIsEvenPair[address] = descriptor.isEvenPair
        descriptor.slot?.let { addressToSlot[address] = it }
    }

    private fun determineSlot(
        descriptor: PairDescriptor,
        inventory: PairInventory?,
        address: String,
    ): LensSlot {
        descriptor.slot?.let { return it }
        addressToSlot[address]?.let { return it }
        inventory?.left?.takeIf { it.address == address }?.let { return LensSlot.LEFT }
        inventory?.right?.takeIf { it.address == address }?.let { return LensSlot.RIGHT }
        if (inventory?.left == null) return LensSlot.LEFT
        if (inventory.right == null) return LensSlot.RIGHT
        return LensSlot.LEFT
    }

    private fun LensSlot.toLens(): Lens = when (this) {
        LensSlot.LEFT -> Lens.LEFT
        LensSlot.RIGHT -> Lens.RIGHT
    }

    private fun BleNameParser.Lens.toSlot(): LensSlot? = when (this) {
        BleNameParser.Lens.LEFT -> LensSlot.LEFT
        BleNameParser.Lens.RIGHT -> LensSlot.RIGHT
        BleNameParser.Lens.UNKNOWN -> null
    }

    private fun BleNameParser.Lens.toLogLabel(): String = when (this) {
        BleNameParser.Lens.LEFT -> "L"
        BleNameParser.Lens.RIGHT -> "R"
        BleNameParser.Lens.UNKNOWN -> "?"
    }

    private fun LensSlot.opposite(): LensSlot = when (this) {
        LensSlot.LEFT -> LensSlot.RIGHT
        LensSlot.RIGHT -> LensSlot.LEFT
    }

    private enum class LensSlot {
        LEFT,
        RIGHT,
    }

    private data class PairDescriptor(
        val token: String,
        val slot: LensSlot?,
        val isEvenPair: Boolean,
    )

    private data class LensRecord(
        val name: String?,
        val address: String,
        val rssi: Int,
        val timestampNanos: Long?,
        val lastSeenMillis: Long,
    )

    private data class PairInventory(
        val token: String,
        val left: LensRecord? = null,
        val right: LensRecord? = null,
        val lastUpdatedMillis: Long = 0L,
    ) {
        fun withLens(slot: LensSlot, lens: LensRecord, now: Long): PairInventory = when (slot) {
            LensSlot.LEFT -> copy(left = lens, lastUpdatedMillis = now)
            LensSlot.RIGHT -> copy(right = lens, lastUpdatedMillis = now)
        }

        fun lens(slot: LensSlot): LensRecord? = when (slot) {
            LensSlot.LEFT -> left
            LensSlot.RIGHT -> right
        }
    }
}
