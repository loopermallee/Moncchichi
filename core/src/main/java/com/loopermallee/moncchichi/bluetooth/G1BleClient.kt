package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile

internal sealed interface AckOutcome {
    val opcode: Int?
    val status: Int?

    data class Success(
        override val opcode: Int?,
        override val status: Int?,
    ) : AckOutcome

    data class Failure(
        override val opcode: Int?,
        override val status: Int?,
    ) : AckOutcome
}

internal fun ByteArray.parseAckOutcome(): AckOutcome? {
    if (isEmpty()) return null

    val opcode = this[0].toInt() and 0xFF

    val statusByteFromHeader = getOrNull(1)?.toInt()?.and(0xFF)
    when (statusByteFromHeader) {
        0xC9 -> return AckOutcome.Success(opcode, statusByteFromHeader)
        0xCA -> return AckOutcome.Failure(opcode, statusByteFromHeader)
    }

    val headerIndicatesLength = statusByteFromHeader != null && statusByteFromHeader <= (size - 2)
    if (headerIndicatesLength) {
        var payloadStart = 2
        var payloadLength = statusByteFromHeader
        if (payloadLength >= 2 && size >= 4) {
            payloadStart = 4
            payloadLength = (payloadLength - 2).coerceAtLeast(0)
        }

        val payloadEndExclusive = minOf(size, payloadStart + payloadLength)
        for (index in (payloadEndExclusive - 1) downTo payloadStart) {
            when (this[index]) {
                0xC9.toByte() -> return AckOutcome.Success(opcode, 0xC9)
                0xCA.toByte() -> return AckOutcome.Failure(opcode, 0xCA)
            }
        }
    }

    if (size >= 2) {
        for (index in (size - 1) downTo 1) {
            when (this[index]) {
                0xC9.toByte() -> return AckOutcome.Success(opcode, 0xC9)
                0xCA.toByte() -> return AckOutcome.Failure(opcode, 0xCA)
            }
        }
    }

    val ascii = runCatching { decodeToString() }.getOrNull() ?: return null
    val trimmed = ascii.trim { it.code <= 0x20 }
    if (trimmed.equals("OK", ignoreCase = true)) {
        return AckOutcome.Success(opcode = null, status = null)
    }

    val normalized = trimmed.uppercase()
    if (trimmed == normalized && normalized.startsWith("ACK:")) {
        // These ACK:<TOKEN> strings are observed from the firmware but not part of
        // any published protocol specification. Treat them as successful ACKs so the
        // client remains resilient to keepalive and ping responses.
        return AckOutcome.Success(opcode = null, status = null)
    }

    return null
}

/**
 * Thin wrapper around [G1BleUartClient] that adds command sequencing and ACK tracking.
 */
class G1BleClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val label: String,
    private val logger: MoncchichiLogger,
    private val uartClientFactory: (
        Context,
        BluetoothDevice,
        (String) -> Unit,
        CoroutineScope,
    ) -> G1BleUartClient = ::G1BleUartClient,
) {

    private companion object {
        private const val MTU_COMMAND_ACK_TIMEOUT_MS = 1_500L
        private const val MTU_COMMAND_RETRY_COUNT = 3
        private const val MTU_COMMAND_RETRY_DELAY_MS = 200L
        private const val POST_BOND_CONNECT_DELAY_MS = 1_000L
        private const val BOND_STATE_REMOVED = 9
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    data class AudioFrame(val sequence: Int, val payload: ByteArray)

    data class State(
        val status: ConnectionState = ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
        val bonded: Boolean = false,
        val attMtu: Int? = null,
    )

    private val uartClient = uartClientFactory(
        context,
        device,
        { message -> logger.i(label, "[BLE] $message") },
        scope,
    )
    private val ackSignals = Channel<AckOutcome>(capacity = Channel.CONFLATED)
    data class AckEvent(
        val timestampMs: Long,
        val opcode: Int?,
        val status: Int?,
        val success: Boolean,
    )
    private val _ackEvents = MutableSharedFlow<AckEvent>(extraBufferCapacity = 8)
    val ackEvents: SharedFlow<AckEvent> = _ackEvents.asSharedFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()
    private val _state = MutableStateFlow(
        State(bonded = device.bondState == BluetoothDevice.BOND_BONDED)
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val writeMutex = Mutex()
    private var monitorJob: Job? = null
    private var rssiJob: Job? = null
    private var mtuJob: Job? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionInitiated = false
    private var bondConnectJob: Job? = null

    private val lastAckTimestamp = AtomicLong(0L)
    private val mtuCommandMutex = Mutex()
    @Volatile private var lastAckedMtu: Int? = null

    fun connect() {
        lastAckedMtu = null
        monitorJob?.cancel()
        monitorJob = scope.launch {
            uartClient.connectionState.collectLatest { connection ->
                val next = when (connection) {
                    G1BleUartClient.ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                    G1BleUartClient.ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                    G1BleUartClient.ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                }
                _state.value = _state.value.copy(status = next)
            }
        }

        rssiJob?.cancel()
        rssiJob = scope.launch {
            uartClient.rssi.collectLatest { value ->
                _state.value = _state.value.copy(rssi = value)
            }
        }

        mtuJob?.cancel()
        mtuJob = scope.launch {
            var previousMtu: Int? = null
            var previousArmed = false
            combine(
                uartClient.connectionState,
                uartClient.mtu,
                uartClient.notificationsArmed,
            ) { state, mtu, armed -> Triple(state, mtu, armed) }
                .collect { (connectionState, mtu, armed) ->
                    if (connectionState != G1BleUartClient.ConnectionState.CONNECTED) {
                        lastAckedMtu = null
                        previousMtu = null
                        previousArmed = false
                        _state.value = _state.value.copy(attMtu = null)
                        return@collect
                    }

                    val mtuChanged = previousMtu?.let { it != mtu } ?: false
                    val armedBecameTrue = armed && !previousArmed
                    previousMtu = mtu
                    previousArmed = armed

                    val alreadyAcked = lastAckedMtu == mtu
                    if (!alreadyAcked && (mtuChanged || armedBecameTrue)) {
                        sendMtuCommandIfNeeded(mtu)
                    } else if (alreadyAcked) {
                        _state.value = _state.value.copy(attMtu = mtu)
                    }
                }
        }

        scope.launch {
            uartClient.observeNotifications { payload ->
                payload.parseAckOutcome()?.let { ack ->
                    val now = System.currentTimeMillis()
                    if (ack is AckOutcome.Success) {
                        lastAckTimestamp.set(now)
                    }
                    val event = AckEvent(
                        timestampMs = now,
                        opcode = ack.opcode,
                        status = ack.status,
                        success = ack is AckOutcome.Success,
                    )
                    _ackEvents.tryEmit(event)
                    ackSignals.trySend(ack)
                }
                val copy = payload.copyOf()
                copy.toAudioFrameOrNull()?.let { frame -> _audioFrames.tryEmit(frame) }
                _incoming.tryEmit(copy)
            }
        }

        connectionInitiated = false
        registerBondReceiverIfNeeded()
        updateBondState(device.bondState)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                uartClient.requestWarmupOnNextNotify()
                scheduleGattConnection("already bonded")
            }
            BluetoothDevice.BOND_NONE -> {
                val name = device.name ?: device.address ?: "unknown"
                logger.i(label, "${tt()} [PAIRING] Requesting bond with $name")
                val bonded = device.createBond()
                logger.i(label, "${tt()} Initiating bond=${bonded}")
            }
            BluetoothDevice.BOND_BONDING -> logger.i(label, "${tt()} Awaiting ongoing bond")
        }
    }

    fun close() {
        monitorJob?.cancel()
        rssiJob?.cancel()
        mtuJob?.cancel()
        monitorJob = null
        rssiJob = null
        mtuJob = null
        unregisterBondReceiver()
        connectionInitiated = false
        bondConnectJob?.cancel()
        bondConnectJob = null
        ackSignals.trySend(AckOutcome.Failure(opcode = null, status = null)) // unblock waiters before closing
        uartClient.close()
        lastAckedMtu = null
        _state.value = State(
            status = ConnectionState.DISCONNECTED,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            attMtu = null,
        )
    }

    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val target = withTimeoutOrNull(timeoutMs) {
            state.filter { it.status != ConnectionState.CONNECTING }
                .first { it.status != ConnectionState.CONNECTING }
        }
        return target?.status == ConnectionState.CONNECTED
    }

    suspend fun sendCommand(
        payload: ByteArray,
        ackTimeoutMs: Long,
        retries: Int,
        retryDelayMs: Long,
    ): Boolean {
        return writeMutex.withLock {
            val opcode = payload.firstOrNull()?.toInt()?.and(0xFF)
            repeat(retries) { attempt ->
                // Clear any stale ACK before writing.
                while (ackSignals.tryReceive().isSuccess) {
                    // Drain the channel.
                }
                val queued = uartClient.write(payload)
                if (!queued) {
                    logger.w(
                        label,
                        "${tt()} Failed to enqueue write opcode=${opcode.toHexString()} (attempt ${attempt + 1})",
                    )
                    delay(retryDelayMs)
                    return@repeat
                }
                val ackResult = withTimeoutOrNull(ackTimeoutMs) {
                    ackSignals.receive()
                }
                when (ackResult) {
                    is AckOutcome.Success -> {
                        return true
                    }
                    is AckOutcome.Failure -> {
                        logger.w(
                            label,
                            "${tt()} ACK failure opcode=${ackResult.opcode.toHexString()} " +
                                "status=${ackResult.status.toHexString()} (attempt ${attempt + 1})",
                        )
                    }
                    null -> {
                        logger.w(
                            label,
                            "${tt()} ACK timeout opcode=${opcode.toHexString()} (attempt ${attempt + 1})",
                        )
                    }
                }
                if (attempt < retries - 1) {
                    delay(retryDelayMs)
                }
            }
            false
        }
    }

    fun readRemoteRssi(): Boolean = uartClient.readRemoteRssi()

    fun lastAckTimestamp(): Long = lastAckTimestamp.get()

    private suspend fun sendMtuCommandIfNeeded(mtu: Int) {
        if (lastAckedMtu == mtu) return
        mtuCommandMutex.withLock {
            if (lastAckedMtu == mtu) return
            logger.i(label, "${tt()} Sending MTU command mtu=$mtu")
            val acked = sendMtuCommand(mtu)
            if (acked) {
                lastAckedMtu = mtu
                logger.i(label, "${tt()} MTU command acknowledged mtu=$mtu")
                _state.value = _state.value.copy(attMtu = mtu)
            } else {
                logger.w(label, "${tt()} MTU command failed mtu=$mtu")
            }
        }
    }

    private suspend fun sendMtuCommand(mtu: Int): Boolean {
        val mtuLow = (mtu and 0xFF).toByte()
        val mtuHigh = ((mtu ushr 8) and 0xFF).toByte()
        val payload = byteArrayOf(0x4D, mtuLow, mtuHigh)
        return sendCommand(
            payload = payload,
            ackTimeoutMs = MTU_COMMAND_ACK_TIMEOUT_MS,
            retries = MTU_COMMAND_RETRY_COUNT,
            retryDelayMs = MTU_COMMAND_RETRY_DELAY_MS,
        )
    }

    private fun ByteArray.toAudioFrameOrNull(): AudioFrame? {
        if (isEmpty()) return null
        val opcode = this[0].toInt() and 0xFF
        if (opcode != 0xF1) return null
        val sequence = this.getOrNull(1)?.toUByte()?.toInt() ?: 0
        val payload = if (size > 2) copyOfRange(2, size) else ByteArray(0)
        return AudioFrame(sequence, payload)
    }

    private fun Int?.toHexString(): String = this?.let { String.format("0x%02X", it) } ?: "n/a"

    private fun tt(): String = "[${Thread.currentThread().name}]"

    private fun registerBondReceiverIfNeeded() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changedDevice?.address != device.address) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                logger.i(label, "${tt()} Bond state changed=$bondState")
                updateBondState(bondState)
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> scheduleGattConnection("bond receiver")
                    BluetoothDevice.BOND_NONE -> {
                        logger.i(label, "${tt()} Bond cleared; refreshing GATT cache")
                        uartClient.refresh()
                    }
                    BOND_STATE_REMOVED -> {
                        logger.w(label, "${tt()} Bond removed; refreshing GATT cache")
                        uartClient.refresh()
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION_BOND_STATE_CHANGED))
        bondReceiver = receiver
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        bondReceiver = null
    }

    private fun maybeStartGattConnection() {
        if (connectionInitiated) return
        connectionInitiated = true
        uartClient.connect()
    }

    private fun updateBondState(state: Int) {
        val wasBonded = _state.value.bonded
        val isBonded = state == BluetoothDevice.BOND_BONDED
        _state.value = _state.value.copy(bonded = isBonded)
        if (isBonded && !wasBonded) {
            logger.i(label, "${tt()} [PAIRING] Bonded ✅")
            uartClient.requestWarmupOnNextNotify()
            scheduleGattConnection("bond complete")
        } else if (!isBonded) {
            bondConnectJob?.cancel()
            bondConnectJob = null
        }
    }

    private fun scheduleGattConnection(reason: String) {
        if (_state.value.bonded.not()) {
            logger.i(label, "${tt()} Skipping GATT connect schedule; bond incomplete ($reason)")
            return
        }
        bondConnectJob?.cancel()
        bondConnectJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(POST_BOND_CONNECT_DELAY_MS)
                if (!_state.value.bonded) {
                    logger.i(label, "${tt()} Bond lost before connect could start ($reason)")
                    break
                }
                logger.i(label, "${tt()} [PAIRING] Launching GATT connect after bond ($reason, waited=${System.currentTimeMillis() - startedAt}ms)")
                maybeStartGattConnection()
                break
            }
        }
    }

}
