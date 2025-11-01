package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.core.MicControlPacket
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
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
import kotlin.coroutines.coroutineContext
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
        val attMtu: Int? = null,
        val lastKeepAliveAt: Long? = null,
        val keepAliveRttMs: Long? = null,
        val consecutiveKeepAliveFailures: Int = 0,
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

    data class EvenAiEvent(val lens: Lens, val event: G1ReplyParser.EvenAiEvent)
    data class AudioFrame(val lens: Lens, val sequence: Int, val payload: ByteArray)

    data class AckEvent(
        val lens: Lens,
        val opcode: Int?,
        val status: Int?,
        val success: Boolean,
        val timestampMs: Long,
    )

    sealed interface MoncchichiEvent {
        data object ConnectionFailed : MoncchichiEvent
    }

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
    private val _evenAiEvents = MutableSharedFlow<EvenAiEvent>(extraBufferCapacity = 32)
    val evenAiEvents: SharedFlow<EvenAiEvent> = _evenAiEvents.asSharedFlow()
    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 128)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()
    private val _ackEvents = MutableSharedFlow<AckEvent>(extraBufferCapacity = 32)
    val ackEvents: SharedFlow<AckEvent> = _ackEvents.asSharedFlow()
    private val _events = MutableSharedFlow<MoncchichiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MoncchichiEvent> = _events.asSharedFlow()

    private val clientRecords: MutableMap<Lens, ClientRecord> = ConcurrentHashMap()
    private val sendMutex = Mutex()

    private var heartbeatJob: Job? = null
    private val heartbeatSeq = mutableMapOf<Lens, Int>()
    private var consecutiveConnectionFailures = 0

    suspend fun connect(device: BluetoothDevice, lensOverride: Lens? = null): Boolean {
        val lens = lensOverride ?: inferLens(device)
        log("Connecting ${device.address} as $lens")
        val record = buildClientRecord(lens, device)
        clientRecords[lens]?.dispose()
        clientRecords[lens] = record
        updateLens(lens) { it.copy(state = G1BleClient.ConnectionState.CONNECTING, rssi = null) }
        val readyResult = try {
            record.client.connect()
            record.client.awaitReady(CONNECT_TIMEOUT_MS)
        } catch (error: Throwable) {
            logWarn("Connection failed for ${device.address}: ${error.message ?: "unknown error"}")
            null
        }
        when (readyResult) {
            G1BleClient.AwaitReadyResult.Ready -> Unit
            G1BleClient.AwaitReadyResult.Timeout -> {
                logWarn("Ready wait timed out for ${device.address} (${lens.name.lowercase(Locale.US)})")
                handleConnectionFailure(lens, record)
                return false
            }
            G1BleClient.AwaitReadyResult.Disconnected -> {
                logWarn("Link disconnected before ready for ${device.address} (${lens.name.lowercase(Locale.US)})")
                handleConnectionFailure(lens, record)
                return false
            }
            null -> {
                handleConnectionFailure(lens, record)
                return false
            }
        }
        consecutiveConnectionFailures = 0
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

    suspend fun setMicEnabled(
        lens: Lens,
        enabled: Boolean,
        ackTimeoutMs: Long = ACK_TIMEOUT_MS,
        retries: Int = COMMAND_RETRY_COUNT,
        retryDelayMs: Long = COMMAND_RETRY_DELAY_MS,
    ): Boolean {
        val packet = MicControlPacket(enabled)
        return send(packet.bytes, lens.toTarget(), ackTimeoutMs, retries, retryDelayMs)
    }

    suspend fun sendEvenAiStop(
        lens: Lens,
        ackTimeoutMs: Long = ACK_TIMEOUT_MS,
        retries: Int = COMMAND_RETRY_COUNT,
        retryDelayMs: Long = COMMAND_RETRY_DELAY_MS,
    ): Boolean {
        val payload = byteArrayOf(0xF5.toByte(), 0x24)
        return send(payload, lens.toTarget(), ackTimeoutMs, retries, retryDelayMs)
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
                        attMtu = state.attMtu,
                    )
                }
            }
        }
        jobs += scope.launch {
            client.incoming.collect { payload ->
                _incoming.tryEmit(IncomingFrame(lens, payload))
                when (val parsed = G1ReplyParser.parseNotify(payload)) {
                    is G1ReplyParser.Parsed.EvenAi -> {
                        val event = EvenAiEvent(lens, parsed.event)
                        _evenAiEvents.tryEmit(event)
                        log("Even AI event from $lens -> ${describeEvenAi(parsed.event)}")
                    }
                    else -> Unit
                }
            }
        }
        jobs += scope.launch {
            client.ackEvents.collect { event ->
                if (event.success) {
                    updateLens(lens) {
                        it.copy(lastAckAt = event.timestampMs, degraded = false)
                    }
                    log("ACK success on $lens opcode=${event.opcode.toHex()} status=${event.status.toHex()}")
                } else {
                    updateLens(lens) { it.copy(degraded = true) }
                    logWarn(
                        "ACK failure on $lens opcode=${event.opcode.toHex()} status=${event.status.toHex()}"
                    )
                }
                _ackEvents.tryEmit(
                    AckEvent(
                        lens = lens,
                        opcode = event.opcode,
                        status = event.status,
                        success = event.success,
                        timestampMs = event.timestampMs,
                    )
                )
            }
        }
        jobs += scope.launch {
            client.audioFrames.collect { frame ->
                _audioFrames.tryEmit(AudioFrame(lens, frame.sequence, frame.payload))
            }
        }
        jobs += scope.launch {
            client.keepAlivePrompts.collect { prompt ->
                val result = client.respondToKeepAlivePrompt(prompt)
                handleKeepAliveResult(lens, result)
            }
        }
        jobs += scope.launch {
            client.keepAliveResults.collect { result ->
                logKeepAliveTelemetry(lens, result)
            }
        }
        return ClientRecord(lens, client, jobs)
    }

    private fun ClientRecord.clientState(): LensStatus = when (lens) {
        Lens.LEFT -> state.value.left
        Lens.RIGHT -> state.value.right
    }

    private suspend fun handleKeepAliveResult(
        lens: Lens,
        result: G1BleClient.KeepAliveResult,
    ) {
        updateLens(lens) { status ->
            val previousFailures = status.consecutiveKeepAliveFailures
            val nextFailures = if (result.success) 0 else previousFailures + 1
            val threshold = G1BleClient.KEEP_ALIVE_MAX_ATTEMPTS
            val degraded = when {
                result.success && previousFailures >= threshold -> false
                result.success -> status.degraded
                else -> status.degraded || nextFailures >= threshold
            }
            status.copy(
                lastKeepAliveAt = if (result.success) result.completedTimestampMs else status.lastKeepAliveAt,
                keepAliveRttMs = result.rttMs ?: status.keepAliveRttMs,
                consecutiveKeepAliveFailures = nextFailures,
                degraded = degraded,
            )
        }
    }

    private fun logKeepAliveTelemetry(lens: Lens, result: G1BleClient.KeepAliveResult) {
        val lensLabel = lens.name.lowercase(Locale.US)
        val seqLabel = String.format("0x%02X", result.sequence)
        val rttLabel = result.rttMs?.let { "${it}ms" } ?: "n/a"
        val attemptsLabel = result.attemptCount
        val promptLatency = result.completedTimestampMs - result.promptTimestampMs
        val message = "[KEEPALIVE][$lensLabel] seq=$seqLabel rtt=$rttLabel attempts=$attemptsLabel latency=${promptLatency}ms"
        if (result.success) {
            log(message)
        } else {
            logWarn(message)
        }
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
        while (coroutineContext.isActive) {
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

    private fun handleConnectionFailure(lens: Lens, record: ClientRecord) {
        record.dispose()
        clientRecords.remove(lens)
        updateLens(lens) { LensStatus() }
        consecutiveConnectionFailures += 1
        if (consecutiveConnectionFailures == CONNECTION_FAILURE_THRESHOLD) {
            logger.i(
                TAG,
                "${tt()} [MONCCHICHI][TROUBLESHOOT] Dialog displayed – connection failed twice"
            )
            _events.tryEmit(MoncchichiEvent.ConnectionFailed)
        }
    }

    private fun log(message: String) {
        logger.i(TAG, "${tt()} $message")
    }

    private fun logWarn(message: String) {
        logger.w(TAG, "${tt()} $message")
    }

    private fun tt(): String = "[${Thread.currentThread().name}]"

    private fun Int?.toHex(): String = this?.let { String.format("0x%02X", it) } ?: "n/a"

    private fun Lens.toTarget(): Target = when (this) {
        Lens.LEFT -> Target.Left
        Lens.RIGHT -> Target.Right
    }

    private fun describeEvenAi(event: G1ReplyParser.EvenAiEvent): String = when (event) {
        is G1ReplyParser.EvenAiEvent.ActivationRequested -> "activation"
        is G1ReplyParser.EvenAiEvent.ManualExit -> "manual exit"
        is G1ReplyParser.EvenAiEvent.ManualPaging -> "manual page"
        is G1ReplyParser.EvenAiEvent.RecordingStopped -> "recording stopped"
        is G1ReplyParser.EvenAiEvent.SilentModeToggle -> "silent toggle"
        is G1ReplyParser.EvenAiEvent.Unknown -> "unknown(${"0x%02X".format(event.subcommand)})"
    }

    companion object {
        private const val TAG = "[MoncchichiBle]"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ACK_TIMEOUT_MS = 1_500L
        private const val COMMAND_RETRY_COUNT = 3
        private const val COMMAND_RETRY_DELAY_MS = 150L
        private const val CHANNEL_STAGGER_DELAY_MS = 5L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private val ALL_LENSES = Lens.values()
        private const val CONNECTION_FAILURE_THRESHOLD = 2
    }
}
