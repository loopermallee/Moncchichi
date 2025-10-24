package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level BLE orchestration layer that manages the dual-lens Even Realities G1 glasses.
 */
class MoncchichiBleService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logger: MoncchichiLogger = MoncchichiLogger(context),
) {

    enum class Lens { LEFT, RIGHT }

    data class LensStatus(
        val state: G1BleClient.ConnectionState = G1BleClient.ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
        val lastAckAt: Long? = null,
        val degraded: Boolean = false,
    ) {
        val isConnected: Boolean get() = state == G1BleClient.ConnectionState.CONNECTED
    }

    data class ServiceState(
        val left: LensStatus = LensStatus(),
        val right: LensStatus = LensStatus(),
    )

    sealed interface Target {
        data object Left : Target
        data object Right : Target
        data object Both : Target
    }

    data class IncomingFrame(val lens: Lens, val payload: ByteArray)

    private data class ClientRecord(
        val lens: Lens,
        val client: G1BleClient,
        val jobs: MutableList<Job>,
    ) {
        fun dispose() {
            jobs.forEach { it.cancel() }
            jobs.clear()
            client.close()
        }
    }

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingFrame> = _incoming.asSharedFlow()

    private val clientRecords: MutableMap<Lens, ClientRecord> = ConcurrentHashMap()
    private val sendMutex = Mutex()

    private var heartbeatJob: Job? = null
    private val heartbeatSeq = mutableMapOf<Lens, Int>()

    suspend fun connect(device: BluetoothDevice, lensOverride: Lens? = null): Boolean {
        val lens = lensOverride ?: inferLens(device)
        log("Connecting ${device.address} as $lens")
        val record = buildClientRecord(lens, device)
        clientRecords[lens]?.dispose()
        clientRecords[lens] = record
        updateLens(lens) { it.copy(state = G1BleClient.ConnectionState.CONNECTING, rssi = null) }
        record.client.connect()
        val ok = record.client.awaitConnected(CONNECT_TIMEOUT_MS)
        if (!ok) {
            logWarn("Connection timed out for ${device.address}")
            record.dispose()
            clientRecords.remove(lens)
            updateLens(lens) { LensStatus() }
            return false
        }
        ensureHeartbeatLoop()
        log("Connected ${device.address} on $lens")
        return true
    }

    fun disconnect(lens: Lens) {
        clientRecords.remove(lens)?.let { record ->
            log("Disconnecting $lens")
            record.dispose()
        }
        updateLens(lens) { LensStatus() }
        ensureHeartbeatLoop()
    }

    fun disconnectAll() {
        ALL_LENSES.forEach { disconnect(it) }
    }

    suspend fun send(
        payload: ByteArray,
        target: Target = Target.Both,
        ackTimeoutMs: Long = ACK_TIMEOUT_MS,
        retries: Int = COMMAND_RETRY_COUNT,
        retryDelayMs: Long = COMMAND_RETRY_DELAY_MS,
    ): Boolean {
        val records = when (target) {
            Target.Left -> listOfNotNull(clientRecords[Lens.LEFT]?.takeIf { it.clientState().isConnected })
            Target.Right -> listOfNotNull(clientRecords[Lens.RIGHT]?.takeIf { it.clientState().isConnected })
            Target.Both -> ALL_LENSES.mapNotNull { lens ->
                clientRecords[lens]?.takeIf { it.clientState().isConnected }
            }
        }
        if (records.isEmpty()) {
            logWarn("No connected lenses for $target")
            return false
        }
        var success = true
        sendMutex.withLock {
            records.forEachIndexed { index, record ->
                val ok = record.client.sendCommand(payload, ackTimeoutMs, retries, retryDelayMs)
                if (!ok) {
                    success = false
                    updateLens(record.lens) { it.copy(degraded = true) }
                    logWarn("Command failed on ${record.lens}")
                } else {
                    updateLens(record.lens) {
                        it.copy(
                            degraded = false,
                            lastAckAt = record.client.lastAckTimestamp(),
                        )
                    }
                }
                if (index < records.lastIndex) {
                    delay(CHANNEL_STAGGER_DELAY_MS)
                }
            }
        }
        return success
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        clientRecords.values.forEach { it.dispose() }
        clientRecords.clear()
        ALL_LENSES.forEach { updateLens(it) { LensStatus() } }
    }

    private fun buildClientRecord(lens: Lens, device: BluetoothDevice): ClientRecord {
        val client = G1BleClient(
            context = context,
            device = device,
            scope = scope,
            label = "$TAG[$lens]",
            logger = logger,
        )
        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            client.state.collectLatest { state ->
                updateLens(lens) {
                    it.copy(
                        state = state.status,
                        rssi = state.rssi,
                    )
                }
            }
        }
        jobs += scope.launch {
            client.incoming.collect { payload ->
                _incoming.tryEmit(IncomingFrame(lens, payload))
            }
        }
        jobs += scope.launch {
            client.ackEvents.collect { timestamp ->
                updateLens(lens) { it.copy(lastAckAt = timestamp, degraded = false) }
            }
        }
        return ClientRecord(lens, client, jobs)
    }

    private fun ClientRecord.clientState(): LensStatus = when (lens) {
        Lens.LEFT -> state.value.left
        Lens.RIGHT -> state.value.right
    }

    private fun inferLens(device: BluetoothDevice): Lens {
        val name = device.name?.lowercase(Locale.US).orEmpty()
        return when {
            name.contains("left") || name.endsWith("_l") || name.endsWith("-l") -> Lens.LEFT
            name.contains("right") || name.endsWith("_r") || name.endsWith("-r") -> Lens.RIGHT
            else -> if (state.value.left.isConnected) Lens.RIGHT else Lens.LEFT
        }
    }

    private fun ensureHeartbeatLoop() {
        val anyConnected = state.value.left.isConnected || state.value.right.isConnected
        if (!anyConnected) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    private suspend fun heartbeatLoop() {
        while (isActive) {
            val records = ALL_LENSES.mapNotNull { lens ->
                clientRecords[lens]?.takeIf { it.clientState().isConnected }
            }
            if (records.isEmpty()) {
                break
            }
            records.forEachIndexed { index, record ->
                val nextSeq = ((heartbeatSeq[record.lens] ?: 0) + 1) and 0xFF
                heartbeatSeq[record.lens] = nextSeq
                val payload = byteArrayOf(0x25, nextSeq.toByte())
                val ok = record.client.sendCommand(payload, ACK_TIMEOUT_MS, COMMAND_RETRY_COUNT, COMMAND_RETRY_DELAY_MS)
                if (!ok) {
                    logWarn("Heartbeat timeout on ${record.lens}")
                    updateLens(record.lens) { it.copy(degraded = true) }
                } else {
                    updateLens(record.lens) {
                        it.copy(
                            degraded = false,
                            lastAckAt = record.client.lastAckTimestamp(),
                        )
                    }
                }
                if (index < records.lastIndex) {
                    delay(CHANNEL_STAGGER_DELAY_MS)
                }
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun updateLens(lens: Lens, reducer: (LensStatus) -> LensStatus) {
        _state.value = when (lens) {
            Lens.LEFT -> _state.value.copy(left = reducer(_state.value.left))
            Lens.RIGHT -> _state.value.copy(right = reducer(_state.value.right))
        }
        ensureHeartbeatLoop()
    }

    private fun log(message: String) {
        logger.i(TAG, "${tt()} $message")
    }

    private fun logWarn(message: String) {
        logger.w(TAG, "${tt()} $message")
    }

    private fun tt(): String = "[${Thread.currentThread().name}]"

    companion object {
        private const val TAG = "[MoncchichiBle]"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ACK_TIMEOUT_MS = 1_500L
        private const val COMMAND_RETRY_COUNT = 3
        private const val COMMAND_RETRY_DELAY_MS = 150L
        private const val CHANNEL_STAGGER_DELAY_MS = 5L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private val ALL_LENSES = Lens.values()
    }
}
