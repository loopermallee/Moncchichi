package com.loopermallee.moncchichi.bluetooth

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.loopermallee.moncchichi.MoncchichiLogger
import com.loopermallee.moncchichi.ble.G1BleUartClient
import com.loopermallee.moncchichi.bluetooth.refreshCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile

// --------------------------------------------------------------------
//  Compatibility constants for pre-API 31 bond failure reasons
// --------------------------------------------------------------------
private const val UNBOND_REASON_AUTH_FAILED = 1
private const val UNBOND_REASON_AUTH_REJECTED = 2
private const val UNBOND_REASON_AUTH_CANCELED = 3
private const val UNBOND_REASON_REMOTE_DEVICE_DOWN = 4
private const val UNBOND_REASON_REMOVED = 5
private const val UNBOND_REASON_OPERATION_CANCELED = 6
private const val UNBOND_REASON_REPEATED_ATTEMPTS = 7
private const val UNBOND_REASON_REMOTE_AUTH_CANCELED = 8
private const val UNBOND_REASON_UNKNOWN = 9
private const val BOND_FAILURE_UNKNOWN = 10
private const val EXTRA_REASON = "android.bluetooth.device.extra.REASON"
private const val OPCODE_SET_MTU = G1Protocols.CMD_HELLO

internal sealed interface AckOutcome {
    val opcode: Int?
    val status: Int?

    data class Success(
        override val opcode: Int?,
        override val status: Int?,
        val warmupPrompt: Boolean = false,
        val keepAlivePrompt: Boolean = false,
    ) : AckOutcome

    data class Failure(
        override val opcode: Int?,
        override val status: Int?,
    ) : AckOutcome
}

enum class BondResult {
    Unknown,
    Success,
    Failed,
    Timeout,
}

internal sealed class BondAwaitResult {
    data object Success : BondAwaitResult()
    data object Timeout : BondAwaitResult()
    data class Failed(val reason: Int) : BondAwaitResult()
}

internal class BondRetryDecider(
    private val maxAttempts: Int,
    private val retryWindowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        fun isTransientReason(reason: Int): Boolean {
            return when (reason) {
                UNBOND_REASON_AUTH_FAILED,
                UNBOND_REASON_AUTH_REJECTED,
                UNBOND_REASON_AUTH_CANCELED,
                UNBOND_REASON_REMOTE_AUTH_CANCELED,
                UNBOND_REASON_REMOTE_DEVICE_DOWN,
                UNBOND_REASON_REMOVED,
                BOND_FAILURE_UNKNOWN,
                -> true
                else -> false
            }
        }
    }

    private var attemptCount = 0
    private var windowStartMs = 0L

    fun reset() {
        attemptCount = 0
        windowStartMs = 0L
    }

    fun nextRetryAttempt(reason: Int): Int? {
        if (!isTransientReason(reason)) {
            return null
        }
        val now = clock()
        if (attemptCount == 0 || now - windowStartMs > retryWindowMs) {
            attemptCount = 0
            windowStartMs = now
        }
        if (attemptCount >= maxAttempts) {
            return null
        }
        attemptCount += 1
        return attemptCount
    }
}

internal fun shouldAttemptRebondAfterLoss(
    previousBondState: Int,
    newBondState: Int,
    reason: Int,
): Boolean {
    if (previousBondState != BluetoothDevice.BOND_BONDED) return false
    if (newBondState != BluetoothDevice.BOND_NONE) return false
    if (reason == UNBOND_REASON_REPEATED_ATTEMPTS) return false
    return true
}

internal fun Int.toBondReasonString(): String {
    return when (this) {
        UNBOND_REASON_AUTH_FAILED -> "UNBOND_REASON_AUTH_FAILED"
        UNBOND_REASON_AUTH_REJECTED -> "UNBOND_REASON_AUTH_REJECTED"
        UNBOND_REASON_AUTH_CANCELED -> "UNBOND_REASON_AUTH_CANCELED"
        UNBOND_REASON_REMOTE_DEVICE_DOWN -> "UNBOND_REASON_REMOTE_DEVICE_DOWN"
        UNBOND_REASON_REMOVED -> "UNBOND_REASON_REMOVED"
        UNBOND_REASON_OPERATION_CANCELED -> "UNBOND_REASON_OPERATION_CANCELED"
        UNBOND_REASON_REPEATED_ATTEMPTS -> "UNBOND_REASON_REPEATED_ATTEMPTS"
        UNBOND_REASON_REMOTE_AUTH_CANCELED -> "UNBOND_REASON_REMOTE_AUTH_CANCELED"
        BOND_FAILURE_UNKNOWN -> "BOND_FAILURE_UNKNOWN"
        UNBOND_REASON_UNKNOWN -> "UNBOND_REASON_UNKNOWN"
        else -> toString()
    }
}

internal fun ByteArray.parseAckOutcome(): AckOutcome? {
    if (isEmpty()) return null

    val opcode = this[0].toInt() and 0xFF

    val statusByteFromHeader = getOrNull(1)?.toInt()?.and(0xFF)
    when (statusByteFromHeader) {
        G1Protocols.STATUS_OK -> return AckOutcome.Success(opcode, statusByteFromHeader)
        G1Protocols.STATUS_FAIL -> return AckOutcome.Failure(opcode, statusByteFromHeader)
    }

    statusByteFromHeader
        ?.takeIf { it <= (size - 2) }
        ?.let { length ->
            var payloadStart = 2
            var payloadLength = length
            if (payloadLength >= 2 && size >= 4) {
                payloadStart = 4
                payloadLength = (payloadLength - 2).coerceAtLeast(0)
            }

            val payloadEndExclusive = minOf(size, payloadStart + payloadLength)
            for (index in (payloadEndExclusive - 1) downTo payloadStart) {
                when (this[index]) {
                    G1Protocols.STATUS_OK.toByte() -> return AckOutcome.Success(opcode, G1Protocols.STATUS_OK)
                    G1Protocols.STATUS_FAIL.toByte() -> return AckOutcome.Failure(opcode, G1Protocols.STATUS_FAIL)
                }
            }
        }

    if (size >= 2) {
        for (index in (size - 1) downTo 1) {
            when (this[index]) {
                G1Protocols.STATUS_OK.toByte() -> return AckOutcome.Success(opcode, G1Protocols.STATUS_OK)
                G1Protocols.STATUS_FAIL.toByte() -> return AckOutcome.Failure(opcode, G1Protocols.STATUS_FAIL)
            }
        }
    }

    val ascii = runCatching { decodeToString() }.getOrNull() ?: return null

    ascii
        .split('\r', '\n')
        .asSequence()
        .map { it.trim { char -> char.code <= 0x20 } }
        .filter { it.isNotEmpty() }
        .forEach { candidate ->
            val withoutPrompt = candidate
                .trimStart { it == '>' }
                .trim { char -> char.code <= 0x20 }
            if (withoutPrompt.isEmpty()) {
                return@forEach
            }
            val uppercase = withoutPrompt.uppercase()
            if (
                withoutPrompt.equals("OK", ignoreCase = true) ||
                uppercase.startsWith("READY") ||
                uppercase.startsWith("HELLO") ||
                uppercase.startsWith("WELCOME")
            ) {
                return AckOutcome.Success(opcode = null, status = null, warmupPrompt = true)
            }
        }

    val trimmed = ascii.trim { it.code <= 0x20 }
    val normalized = trimmed.uppercase()
    if (trimmed == normalized && normalized.startsWith("ACK:")) {
        // These ACK:<TOKEN> strings are observed from the firmware but not part of
        // any published protocol specification. Treat them as successful ACKs so the
        // client remains resilient to keepalive and ping responses.
        val keepAlivePrompt = normalized == "ACK:KEEPALIVE"
        return AckOutcome.Success(
            opcode = null,
            status = null,
            keepAlivePrompt = keepAlivePrompt,
        )
    }

    return null
}

private fun AckOutcome.Success.satisfiesWarmupAck(): Boolean {
    val opcode = opcode ?: return warmupPrompt
    return opcode == OPCODE_SET_MTU
}

private fun AckOutcome.matchesOpcode(expectedOpcode: Int?): Boolean {
    val ackOpcode = opcode
    if (expectedOpcode == null) {
        return ackOpcode == null
    }
    if (expectedOpcode == OPCODE_SET_MTU) {
        if (ackOpcode == OPCODE_SET_MTU) {
            return true
        }
        if (this is AckOutcome.Success) {
            return warmupPrompt && ackOpcode == null
        }
        return false
    }
    return ackOpcode == expectedOpcode
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

    companion object {
        private const val MTU_COMMAND_ACK_TIMEOUT_MS = G1Protocols.MTU_ACK_TIMEOUT_MS
        private const val MTU_COMMAND_WARMUP_GRACE_MS = G1Protocols.MTU_WARMUP_GRACE_MS
        private const val MTU_COMMAND_RETRY_COUNT = G1Protocols.MAX_RETRIES
        private const val MTU_COMMAND_RETRY_DELAY_MS = G1Protocols.MTU_RETRY_DELAY_MS
        private const val POST_BOND_CONNECT_DELAY_MS = 1_000L
        private const val BOND_STATE_REMOVED = 9
        private const val BOND_RETRY_DELAY_MS = 750L
        private const val BOND_RETRY_WINDOW_MS = 30_000L
        private const val BOND_RETRY_MAX_ATTEMPTS = 3
        private const val BOND_DIALOG_GRACE_MS = 5_000L
        private const val PAIRING_NOTIFICATION_CHANNEL_ID = "moncchichi_ble_pairing"
        private const val PAIRING_NOTIFICATION_CHANNEL_NAME = "Bonding prompts"
        private const val PAIRING_NOTIFICATION_ID = 0xB10
        private const val PAIRING_NOTIFICATION_REQUEST_CODE = 0x192
        private const val GATT_RESET_DELAY_MS = 250L
        internal const val KEEP_ALIVE_ACK_TIMEOUT_MS = G1Protocols.DEVICE_KEEPALIVE_ACK_TIMEOUT_MS
        internal const val KEEP_ALIVE_RETRY_BACKOFF_MS = G1Protocols.RETRY_BACKOFF_MS
        internal const val KEEP_ALIVE_LOCK_POLL_INTERVAL_MS = 20L
        // Handshake capture shows firmware initiating keep-alive at sequence 0x01.
        private const val KEEP_ALIVE_INITIAL_SEQUENCE = 0x01
        internal const val KEEP_ALIVE_MAX_ATTEMPTS = G1Protocols.MAX_RETRIES
        internal const val KEEP_ALIVE_OPCODE = G1Protocols.CMD_KEEPALIVE
        private const val HELLO_WATCHDOG_TIMEOUT_MS = 12_000L
        private const val HELLO_RECOVERY_RECONNECT_DELAY_MS = 750L
        private val AUTH_FAILURE_STATUS_CODES = setOf(0x85, 0x13)
        private val GATT_RECONNECT_BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    data class AudioFrame(val sequence: Int, val payload: ByteArray)

    enum class AwaitReadyResult {
        Ready,
        Disconnected,
        Timeout,
    }

    data class State(
        val status: ConnectionState = ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
        val bonded: Boolean = false,
        val attMtu: Int? = null,
        val warmupOk: Boolean = false,
        val lastDisconnectStatus: Int? = null,
        val bondTransitionCount: Int = 0,
        val bondTimeoutCount: Int = 0,
        val bondAttemptCount: Int = 0,
        val lastBondResult: BondResult = BondResult.Unknown,
        val pairingDialogsShown: Int = 0,
        val refreshCount: Int = 0,
        val smpFrameCount: Int = 0,
        val lastSmpOpcode: Int? = null,
        val bondResetCount: Int = 0,
        val lastBondState: Int = BluetoothDevice.BOND_NONE,
        val lastBondReason: Int? = null,
        val lastBondEventAt: Long? = null,
    )

    private val uartClient = uartClientFactory(
        context,
        device,
        { message -> logger.i(label, "[BLE] $message") },
        scope,
    )
    private val ackSignals = Channel<AckOutcome>(capacity = Channel.CONFLATED)
    private val bondMutex = Mutex()
    data class BondEvent(val state: Int, val reason: Int, val timestampMs: Long)
    private val bondEvents = MutableSharedFlow<BondEvent>(replay = 1, extraBufferCapacity = 8)
    data class AckEvent(
        val timestampMs: Long,
        val opcode: Int?,
        val status: Int?,
        val success: Boolean,
        val warmup: Boolean,
    )
    private val _ackEvents = MutableSharedFlow<AckEvent>(extraBufferCapacity = 8)
    val ackEvents: SharedFlow<AckEvent> = _ackEvents.asSharedFlow()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pairingDialogRunnable: Runnable? = null
    @Volatile private var settingsLaunchedThisSession: Boolean = false

    data class KeepAlivePrompt(
        val timestampMs: Long,
        val source: Source,
    ) {
        enum class Source { Opcode, Token }
    }

    data class KeepAliveResult(
        val promptTimestampMs: Long,
        val completedTimestampMs: Long,
        val success: Boolean,
        val attemptCount: Int,
        val sequence: Int,
        val rttMs: Long?,
        val lockContentionCount: Int,
        val ackTimeoutCount: Int,
    )

    data class CommandAttemptTelemetry(
        val attemptIndex: Int,
        val success: Boolean,
        val ackTimedOut: Boolean,
        val ackFailed: Boolean,
        val queueFailed: Boolean,
    )

    private val _keepAlivePrompts = MutableSharedFlow<KeepAlivePrompt>(extraBufferCapacity = 8)
    val keepAlivePrompts: SharedFlow<KeepAlivePrompt> = _keepAlivePrompts.asSharedFlow()
    private val _keepAliveResults = MutableSharedFlow<KeepAliveResult>(extraBufferCapacity = 8)
    val keepAliveResults: SharedFlow<KeepAliveResult> = _keepAliveResults.asSharedFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames.asSharedFlow()
    private val _state = MutableStateFlow(
        State(
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            lastBondState = device.bondState,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val writeMutex = Mutex()
    private var monitorJob: Job? = null
    private var rssiJob: Job? = null
    private var mtuJob: Job? = null
    private var gattEventJob: Job? = null
    private var smpJob: Job? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionInitiated = false
    private var bondConnectJob: Job? = null
    private var bondRetryJob: Job? = null
    private val bondRetryDecider = BondRetryDecider(
        maxAttempts = BOND_RETRY_MAX_ATTEMPTS,
        retryWindowMs = BOND_RETRY_WINDOW_MS,
    )

    private var lastBondState: Int = device.bondState

    private val lastAckTimestamp = AtomicLong(0L)
    private val mtuCommandMutex = Mutex()
    @Volatile private var lastAckedMtu: Int? = null
    @Volatile private var warmupExpected: Boolean = false
    private val keepAliveSequence = AtomicInteger(KEEP_ALIVE_INITIAL_SEQUENCE)
    private val keepAliveInFlight = AtomicInteger(0)
    private var helloWatchdogJob: Job? = null
    private var gattReconnectJob: Job? = null
    private var gattReconnectAttempts = 0
    @Volatile private var bondRemovalInFlight: Boolean = false

    fun connect() {
        lastAckedMtu = null
        warmupExpected = false
        keepAliveSequence.set(KEEP_ALIVE_INITIAL_SEQUENCE)
        keepAliveInFlight.set(0)
        settingsLaunchedThisSession = false
        dismissPairingNotification()
        cancelHelloWatchdog()
        gattReconnectJob?.cancel()
        gattReconnectJob = null
        gattReconnectAttempts = 0
        bondEvents.tryEmit(
            BondEvent(
                state = device.bondState,
                reason = BOND_FAILURE_UNKNOWN,
                timestampMs = System.currentTimeMillis(),
            )
        )
        if (_state.value.warmupOk) {
            _state.value = _state.value.copy(warmupOk = false)
        }
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
                        warmupExpected = false
                        _state.value = _state.value.copy(attMtu = null, warmupOk = false)
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

        gattEventJob?.cancel()
        gattEventJob = scope.launch {
            uartClient.connectionEvents.collect { event ->
                when (event.newState) {
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _state.value = _state.value.copy(lastDisconnectStatus = event.status)
                        val statusLabel = String.format("0x%02X", event.status and 0xFF)
                        cancelHelloWatchdog("disconnect $statusLabel")
                        handleGattDisconnect(event.status)
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (_state.value.lastDisconnectStatus != null) {
                            _state.value = _state.value.copy(lastDisconnectStatus = null)
                        }
                        startInvalidKeyWatchdog()
                    }
                }
            }
        }

        smpJob?.cancel()
        smpJob = scope.launch {
            uartClient.smpNotifications.collect { frame ->
                val opcode = frame.firstOrNull()?.toInt()?.and(0xFF)
                logger.i(
                    label,
                    "${tt()} [SMP] Notification opcode=${opcode?.let { String.format("0x%02X", it) } ?: "n/a"} size=${frame.size}",
                )
                val current = _state.value
                _state.value = current.copy(
                    smpFrameCount = current.smpFrameCount + 1,
                    lastSmpOpcode = opcode,
                )
            }
        }

        scope.launch {
            uartClient.observeNotifications { payload ->
                payload.parseAckOutcome()?.let { ack ->
                    val now = System.currentTimeMillis()
                    var deliverAck = true
                    var keepAlivePrompt: KeepAlivePrompt? = null
                    val warmupAck = ack is AckOutcome.Success && ack.satisfiesWarmupAck()
                    if (ack is AckOutcome.Success) {
                        lastAckTimestamp.set(now)
                        if (warmupExpected && ack.satisfiesWarmupAck()) {
                            warmupExpected = false
                            val negotiatedMtu = runCatching { uartClient.mtu.value }.getOrNull()
                            if (lastAckedMtu == null && negotiatedMtu != null) {
                                lastAckedMtu = negotiatedMtu
                            }
                            val current = _state.value
                            val attMtuCandidate = lastAckedMtu ?: negotiatedMtu ?: current.attMtu
                            _state.value = current.copy(
                                attMtu = attMtuCandidate,
                                warmupOk = true,
                            )
                        }
                        if (warmupAck) {
                            val source = when {
                                ack.opcode == G1Protocols.CMD_HELLO -> "opcode"
                                ack.opcode == null && ack.warmupPrompt -> "text"
                                else -> "signal"
                            }
                            logger.i(label, "${tt()} [BLE][HELLO] Warm-up acknowledged via $source")
                        }
                        when {
                            ack.keepAlivePrompt -> {
                                keepAlivePrompt = KeepAlivePrompt(now, KeepAlivePrompt.Source.Token)
                                deliverAck = false
                            }
                            ack.opcode == KEEP_ALIVE_OPCODE -> {
                                val previous = decrementKeepAliveInFlight()
                                if (previous <= 0) {
                                    keepAlivePrompt = KeepAlivePrompt(now, KeepAlivePrompt.Source.Opcode)
                                    deliverAck = false
                                }
                            }
                        }
                    } else if (ack is AckOutcome.Failure && ack.opcode == KEEP_ALIVE_OPCODE) {
                        val previous = decrementKeepAliveInFlight()
                        if (previous <= 0) {
                            keepAlivePrompt = KeepAlivePrompt(now, KeepAlivePrompt.Source.Opcode)
                            deliverAck = false
                        }
                    }
                    val event = AckEvent(
                        timestampMs = now,
                        opcode = ack.opcode,
                        status = ack.status,
                        success = ack is AckOutcome.Success,
                        warmup = warmupAck,
                    )
                    _ackEvents.tryEmit(event)
                    keepAlivePrompt?.let { prompt -> _keepAlivePrompts.tryEmit(prompt) }
                    if (deliverAck) {
                        ackSignals.trySend(ack)
                    }
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
                recordBondResult(BondResult.Success)
                cancelPairingDialogWatchdog()
                requestWarmupOnNextNotify()
                scheduleGattConnection("already bonded")
            }
            BluetoothDevice.BOND_NONE -> {
                scope.launch { requestBond("connect() initial") }
            }
            BluetoothDevice.BOND_BONDING -> {
                cancelPairingDialogWatchdog()
                logger.i(label, "${tt()} Awaiting ongoing bond")
            }
        }
    }

    fun close() {
        monitorJob?.cancel()
        rssiJob?.cancel()
        mtuJob?.cancel()
        gattEventJob?.cancel()
        smpJob?.cancel()
        monitorJob = null
        rssiJob = null
        mtuJob = null
        gattEventJob = null
        smpJob = null
        unregisterBondReceiver()
        connectionInitiated = false
        cancelHelloWatchdog()
        gattReconnectJob?.cancel()
        gattReconnectJob = null
        gattReconnectAttempts = 0
        bondConnectJob?.cancel()
        bondConnectJob = null
        bondRetryJob?.cancel()
        bondRetryJob = null
        bondRetryDecider.reset()
        ackSignals.trySend(AckOutcome.Failure(opcode = null, status = null)) // unblock waiters before closing
        uartClient.close()
        lastAckedMtu = null
        warmupExpected = false
        keepAliveSequence.set(KEEP_ALIVE_INITIAL_SEQUENCE)
        keepAliveInFlight.set(0)
        cancelPairingDialogWatchdog()
        dismissPairingNotification()
        _state.value = State(
            status = ConnectionState.DISCONNECTED,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            attMtu = null,
            warmupOk = false,
            lastDisconnectStatus = null,
        )
    }

    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val target = withTimeoutOrNull(timeoutMs) {
            state.drop(1)
                .first { it.status != ConnectionState.CONNECTING }
        }
        return target?.status == ConnectionState.CONNECTED
    }

    internal suspend fun awaitBonded(timeoutMs: Long): BondAwaitResult {
        if (state.value.bonded) {
            recordBondResult(BondResult.Success)
            return BondAwaitResult.Success
        }
        var sawBonding = device.bondState == BluetoothDevice.BOND_BONDING
        val event = withTimeoutOrNull(timeoutMs) {
            bondEvents
                .onEach { event ->
                    when (event.state) {
                        BluetoothDevice.BOND_BONDING -> {
                            sawBonding = true
                            cancelPairingDialogWatchdog()
                        }
                        BluetoothDevice.BOND_BONDED -> cancelPairingDialogWatchdog()
                        BluetoothDevice.BOND_NONE -> if (sawBonding) cancelPairingDialogWatchdog()
                    }
                }
                .first { event ->
                    when (event.state) {
                        BluetoothDevice.BOND_BONDED -> true
                        BluetoothDevice.BOND_NONE -> sawBonding
                        else -> false
                    }
                }
        }
        val result = when {
            event == null -> BondAwaitResult.Timeout
            event.state == BluetoothDevice.BOND_BONDED -> BondAwaitResult.Success
            else -> BondAwaitResult.Failed(event.reason)
        }
        when (result) {
            BondAwaitResult.Success -> recordBondResult(BondResult.Success)
            BondAwaitResult.Timeout -> {
                incrementBondTimeout()
                recordBondResult(BondResult.Timeout)
            }
            is BondAwaitResult.Failed -> recordBondResult(BondResult.Failed)
        }
        return result
    }

    suspend fun awaitHelloAck(timeoutMs: Long): Boolean = awaitHelloAckEvent(timeoutMs) != null

    private suspend fun awaitHelloAckEvent(timeoutMs: Long): AckEvent? {
        val current = state.value
        if (current.attMtu != null || current.warmupOk) {
            return AckEvent(
                timestampMs = System.currentTimeMillis(),
                opcode = current.attMtu?.let { G1Protocols.CMD_HELLO },
                status = null,
                success = true,
                warmup = current.warmupOk || current.attMtu != null,
            )
        }
        return withTimeoutOrNull(timeoutMs) {
            ackEvents
                .filter { event ->
                    event.success && (event.opcode == G1Protocols.CMD_HELLO || event.warmup)
                }
                .first()
        }
    }

    suspend fun awaitReady(timeoutMs: Long): AwaitReadyResult {
        if (state.value.isReady()) {
            return AwaitReadyResult.Ready
        }

        val target = withTimeoutOrNull(timeoutMs) {
            state.drop(1)
                .first { candidate ->
                    candidate.status == ConnectionState.DISCONNECTED || candidate.isReady()
                }
        } ?: return AwaitReadyResult.Timeout

        return if (target.isReady()) {
            AwaitReadyResult.Ready
        } else {
            AwaitReadyResult.Disconnected
        }
    }

    suspend fun sendCommand(
        payload: ByteArray,
        ackTimeoutMs: Long,
        retries: Int,
        retryDelayMs: Long,
        expectAck: Boolean = true,
        onAttemptResult: ((CommandAttemptTelemetry) -> Unit)? = null,
    ): Boolean {
        return writeMutex.withLock {
            sendCommandLocked(
                payload = payload,
                ackTimeoutMs = ackTimeoutMs,
                retries = retries,
                retryDelayMs = retryDelayMs,
                expectAck = expectAck,
                onAttemptResult = onAttemptResult,
            )
        }
    }

    private suspend fun sendCommandLocked(
        payload: ByteArray,
        ackTimeoutMs: Long,
        retries: Int,
        retryDelayMs: Long,
        expectAck: Boolean = true,
        onAttemptResult: ((CommandAttemptTelemetry) -> Unit)? = null,
    ): Boolean {
        val opcode = payload.firstOrNull()?.toInt()?.and(0xFF)
        repeat(retries) { attempt ->
            if (expectAck) {
                // Clear any stale ACK before writing.
                while (ackSignals.tryReceive().isSuccess) {
                    // Drain the channel.
                }
            }
            val queued = uartClient.write(payload)
            if (!queued) {
                logger.w(
                    label,
                    "${tt()} Failed to enqueue write opcode=${opcode.toLabel()} (attempt ${attempt + 1})",
                )
                onAttemptResult?.invoke(
                    CommandAttemptTelemetry(
                        attemptIndex = attempt + 1,
                        success = false,
                        ackTimedOut = false,
                        ackFailed = false,
                        queueFailed = true,
                    )
                )
                delay(retryDelayMs)
                return@repeat
            }
            if (!expectAck) {
                onAttemptResult?.invoke(
                    CommandAttemptTelemetry(
                        attemptIndex = attempt + 1,
                        success = true,
                        ackTimedOut = false,
                        ackFailed = false,
                        queueFailed = false,
                    )
                )
                return true
            }
            val ackResult = withTimeoutOrNull(ackTimeoutMs) {
                while (true) {
                    val ack = ackSignals.receive()
                    if (ack.matchesOpcode(opcode)) {
                        return@withTimeoutOrNull ack
                    }
                    when (ack) {
                        is AckOutcome.Success -> {
                            logger.i(
                                label,
                                "${tt()} Ignoring ACK success opcode=${ack.opcode.toLabel()} " +
                                    "while awaiting ${opcode.toLabel()}",
                            )
                        }
                        is AckOutcome.Failure -> {
                            logger.w(
                                label,
                                "${tt()} Ignoring ACK failure opcode=${ack.opcode.toLabel()} " +
                                    "status=${ack.status.toHexString()} while awaiting ${opcode.toLabel()}",
                            )
                        }
                    }
                }
            }
            when (ackResult) {
                is AckOutcome.Success -> {
                    onAttemptResult?.invoke(
                        CommandAttemptTelemetry(
                            attemptIndex = attempt + 1,
                            success = true,
                            ackTimedOut = false,
                            ackFailed = false,
                            queueFailed = false,
                        )
                    )
                    return true
                }
                is AckOutcome.Failure -> {
                    logger.w(
                        label,
                        "${tt()} ACK failure opcode=${ackResult.opcode.toLabel()} " +
                            "status=${ackResult.status.toHexString()} (attempt ${attempt + 1})",
                    )
                    onAttemptResult?.invoke(
                        CommandAttemptTelemetry(
                            attemptIndex = attempt + 1,
                            success = false,
                            ackTimedOut = false,
                            ackFailed = true,
                            queueFailed = false,
                        )
                    )
                }
                null -> {
                    logger.w(
                        label,
                        "${tt()} ACK timeout opcode=${opcode.toLabel()} (attempt ${attempt + 1})",
                    )
                    onAttemptResult?.invoke(
                        CommandAttemptTelemetry(
                            attemptIndex = attempt + 1,
                            success = false,
                            ackTimedOut = true,
                            ackFailed = false,
                            queueFailed = false,
                        )
                    )
                }
            }
            if (attempt < retries - 1) {
                delay(retryDelayMs)
            }
        }
        return false
    }

    fun readRemoteRssi(): Boolean = uartClient.readRemoteRssi()

    fun lastAckTimestamp(): Long = lastAckTimestamp.get()

    suspend fun respondToKeepAlivePrompt(prompt: KeepAlivePrompt): KeepAliveResult {
        var attempt = 0
        var success = false
        var rttMs: Long? = null
        val sequence = nextKeepAliveSequence()
        val payload = buildKeepAlivePayload(sequence)
        var lockContentionCount = 0
        var ackTimeoutCount = 0
        while (attempt < KEEP_ALIVE_MAX_ATTEMPTS && scope.isActive) {
            val attemptIndex = attempt + 1
            val attemptStart = System.currentTimeMillis()
            val attemptDeadline = attemptStart + KEEP_ALIVE_ACK_TIMEOUT_MS
            val lockAcquired = acquireKeepAliveLock(attemptDeadline)
            val waitedForLockMs = System.currentTimeMillis() - attemptStart
            if (!lockAcquired) {
                lockContentionCount += 1
                attempt = attemptIndex
                logger.w(
                    label,
                    "${tt()} Keepalive lock contention seq=${sequence.toByteHex()} attempt=$attemptIndex " +
                        "waited=${waitedForLockMs}ms",
                )
                if (attempt < KEEP_ALIVE_MAX_ATTEMPTS && scope.isActive) {
                    delay(KEEP_ALIVE_RETRY_BACKOFF_MS * attempt)
                }
                continue
            }

            var acked = false
            var sentAt: Long? = null
            keepAliveInFlight.incrementAndGet()
            attempt = attemptIndex
            try {
                val remainingAckBudget = (attemptDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                logger.i(
                    label,
                    "${tt()} Responding to keepalive seq=${sequence.toByteHex()} attempt=$attempt " +
                        "source=${prompt.source} remainingBudget=${remainingAckBudget}ms",
                )
                if (remainingAckBudget > 0) {
                    sentAt = System.currentTimeMillis()
                    var attemptAckTimeouts = 0
                    acked = sendCommandLocked(
                        payload = payload,
                        ackTimeoutMs = remainingAckBudget,
                        retries = 1,
                        retryDelayMs = KEEP_ALIVE_RETRY_BACKOFF_MS,
                        onAttemptResult = { telemetry ->
                            if (telemetry.ackTimedOut) {
                                attemptAckTimeouts += 1
                            }
                        },
                    )
                    ackTimeoutCount += attemptAckTimeouts
                } else {
                    logger.w(
                        label,
                        "${tt()} Keepalive budget exhausted before write seq=${sequence.toByteHex()} " +
                            "attempt=$attempt",
                    )
                }
            } finally {
                if (!acked) {
                    decrementKeepAliveInFlight()
                }
                writeMutex.unlock()
            }
            if (acked) {
                success = true
                rttMs = sentAt?.let { System.currentTimeMillis() - it }
                break
            }
            if (attempt < KEEP_ALIVE_MAX_ATTEMPTS && scope.isActive) {
                delay(KEEP_ALIVE_RETRY_BACKOFF_MS * attempt)
            }
        }
        val completedTimestamp = System.currentTimeMillis()
        if (!success && keepAliveInFlight.get() > 0) {
            keepAliveInFlight.set(0)
        }
        val result = KeepAliveResult(
            promptTimestampMs = prompt.timestampMs,
            completedTimestampMs = completedTimestamp,
            success = success,
            attemptCount = attempt,
            sequence = sequence,
            rttMs = rttMs,
            lockContentionCount = lockContentionCount,
            ackTimeoutCount = ackTimeoutCount,
        )
        if (success) {
            logger.i(
                label,
                "${tt()} Keepalive acknowledged seq=${sequence.toByteHex()} rtt=${rttMs ?: -1}ms attempts=$attempt",
            )
            logger.i(
                label,
                "${tt()} [BLE][KEEPALIVE] Responded seq=${sequence.toByteHex()} rtt=${rttMs ?: -1}ms",
            )
        } else {
            logger.w(
                label,
                "${tt()} Keepalive failed seq=${sequence.toByteHex()} attempts=$attempt",
            )
        }
        _keepAliveResults.emit(result)
        return result
    }

    private suspend fun sendMtuCommandIfNeeded(mtu: Int) {
        if (lastAckedMtu == mtu) return
        var awaitWarmup = false
        mtuCommandMutex.withLock {
            if (lastAckedMtu == mtu) return
            logger.i(label, "${tt()} Sending MTU command mtu=$mtu")
            val warmupInProgress = warmupExpected || !_state.value.warmupOk
            val acked = sendMtuCommand(mtu, warmupInProgress)
            val warmupSatisfied = warmupInProgress && _state.value.warmupOk
            if (acked || warmupSatisfied) {
                lastAckedMtu = mtu
                logger.i(label, "${tt()} MTU command acknowledged mtu=$mtu")
                _state.value = _state.value.copy(attMtu = mtu)
            } else {
                val shouldWaitForWarmup = warmupInProgress
                if (shouldWaitForWarmup) {
                    awaitWarmup = true
                    logger.w(
                        label,
                        "${tt()} MTU command timed out mtu=$mtu; awaiting warm-up prompt",
                    )
                } else {
                    logger.w(label, "${tt()} MTU command failed mtu=$mtu")
                }
            }
        }
        if (awaitWarmup) {
            val warmed = awaitWarmupPrompt()
            if (warmed) {
                logger.i(label, "${tt()} Warm-up prompt received after MTU timeout mtu=$mtu")
            } else {
                logger.w(
                    label,
                    "${tt()} Warm-up prompt missing after ${MTU_COMMAND_WARMUP_GRACE_MS}ms mtu=$mtu",
                )
            }
        }
    }

    private suspend fun sendMtuCommand(mtu: Int, warmupInProgress: Boolean): Boolean {
        val mtuLow = (mtu and 0xFF).toByte()
        val mtuHigh = ((mtu ushr 8) and 0xFF).toByte()
        val payload = byteArrayOf(G1Protocols.CMD_HELLO.toByte(), mtuLow, mtuHigh)
        val ackTimeout = if (warmupInProgress) {
            MTU_COMMAND_WARMUP_GRACE_MS
        } else {
            MTU_COMMAND_ACK_TIMEOUT_MS
        }
        val retryCount = if (warmupInProgress) {
            1
        } else {
            MTU_COMMAND_RETRY_COUNT
        }
        return sendCommand(
            payload = payload,
            ackTimeoutMs = ackTimeout,
            retries = retryCount,
            retryDelayMs = MTU_COMMAND_RETRY_DELAY_MS,
        )
    }

    private fun cancelHelloWatchdog(reason: String? = null) {
        val job = helloWatchdogJob ?: return
        job.cancel()
        helloWatchdogJob = null
        reason?.let {
            logger.i(label, "${tt()} [PAIRING] HELLO watchdog cancelled ($it)")
        }
    }

    private fun startHelloWatchdog() {
        cancelHelloWatchdog()
        val currentState = _state.value
        if (!currentState.bonded || currentState.isReady()) {
            return
        }
        val job = scope.launch {
            logger.i(
                label,
                "${tt()} [PAIRING] HELLO watchdog armed (timeout=${HELLO_WATCHDOG_TIMEOUT_MS}ms)",
            )
            val event = awaitHelloAckEvent(HELLO_WATCHDOG_TIMEOUT_MS)
            if (event != null) {
                val source = describeHelloAck(event)
                logger.i(label, "${tt()} [PAIRING] HELLO watchdog satisfied ($source)")
                return@launch
            }
            if (state.value.bonded) {
                logger.w(label, "${tt()} [PAIRING] HELLO watchdog expired; reconnecting GATT")
                val recovered = attemptHelloRecovery()
                if (!recovered) {
                    scheduleGattReconnect("HELLO timeout", HELLO_RECOVERY_RECONNECT_DELAY_MS)
                }
            }
        }
        job.invokeOnCompletion {
            if (helloWatchdogJob === job) {
                helloWatchdogJob = null
            }
        }
        helloWatchdogJob = job
    }

    private fun handleGattDisconnect(status: Int) {
        val bonded = state.value.bonded || device.bondState == BluetoothDevice.BOND_BONDED
        if (!bonded) return
        if (status in AUTH_FAILURE_STATUS_CODES) {
            scope.launch {
                removeBondForAuthFailure("GATT status ${String.format("0x%02X", status and 0xFF)}")
            }
            return
        }
        scheduleGattReconnect("disconnect status=${String.format("0x%02X", status and 0xFF)}")
    }

    private suspend fun attemptHelloRecovery(): Boolean {
        val notificationsArmed = runCatching { uartClient.notificationsArmed.value }.getOrNull() ?: false
        val mtu = runCatching { uartClient.mtu.value }.getOrNull()
        if (!notificationsArmed || mtu == null) {
            logger.w(
                label,
                "${tt()} [PAIRING] HELLO recovery skipped (notificationsArmed=$notificationsArmed mtu=${mtu ?: "n/a"})",
            )
            return false
        }
        logger.i(label, "${tt()} [PAIRING] HELLO recovery retrying HELLO mtu=$mtu")
        warmupExpected = true
        sendMtuCommandIfNeeded(mtu)
        val event = awaitHelloAckEvent(G1Protocols.MTU_WARMUP_GRACE_MS)
        if (event != null) {
            val source = describeHelloAck(event)
            logger.i(label, "${tt()} [PAIRING] HELLO recovery succeeded ($source)")
            return true
        }
        logger.w(label, "${tt()} [PAIRING] HELLO recovery retry timed out")
        return false
    }

    private fun scheduleGattReconnect(reason: String, delayMs: Long? = null) {
        if (!_state.value.bonded) {
            logger.i(label, "${tt()} [GATT] Reconnect skipped; bond missing ($reason)")
            return
        }
        if (delayMs == null && gattReconnectAttempts >= GATT_RECONNECT_BACKOFF_MS.size) {
            logger.w(label, "${tt()} [GATT] Reconnect attempt limit reached; skipping ($reason)")
            return
        }
        val nextAttempt = (gattReconnectAttempts + 1).coerceAtMost(GATT_RECONNECT_BACKOFF_MS.size)
        val reconnectDelay = delayMs ?: GATT_RECONNECT_BACKOFF_MS[nextAttempt - 1]
        gattReconnectAttempts = nextAttempt
        gattReconnectJob?.cancel()
        val job = scope.launch {
            disconnectGattForRecovery(reason)
            delay(reconnectDelay)
            if (_state.value.bonded) {
                logger.i(
                    label,
                    "${tt()} [GATT] Reconnecting after $reason (attempt=$nextAttempt delay=${reconnectDelay}ms)",
                )
                connectionInitiated = false
                maybeStartGattConnection()
            } else {
                logger.w(label, "${tt()} [GATT] Bond lost before reconnect could start ($reason)")
            }
        }
        job.invokeOnCompletion {
            if (gattReconnectJob === job) {
                gattReconnectJob = null
            }
        }
        gattReconnectJob = job
    }

    private suspend fun disconnectGattForRecovery(reason: String) {
        val existing = uartClient.currentGatt()
        if (existing == null) {
            logger.i(label, "${tt()} [GATT] No active connection to disconnect ($reason)")
            return
        }
        logger.w(label, "${tt()} [GATT] Disconnecting for recovery ($reason)")
        runCatching { existing.disconnect() }
            .onFailure { logger.w(label, "${tt()} GATT disconnect failed: ${it.message}") }
    }

    suspend fun removeBondForAuthFailure(reason: String) {
        performBondRemoval(reason = reason, source = "AUTH_FAILED", rebondDelayMs = BOND_RETRY_DELAY_MS)
    }

    fun removeBondManual(reason: String = "manual") {
        scope.launch {
            performBondRemoval(reason = reason, source = "MANUAL", rebondDelayMs = BOND_RETRY_DELAY_MS * 2)
        }
    }

    private suspend fun performBondRemoval(reason: String, source: String, rebondDelayMs: Long) {
        val timestamp = System.currentTimeMillis()
        bondMutex.withLock {
            if (bondRemovalInFlight) {
                logger.i(label, "${tt()} [PAIRING] Bond removal already in flight; skipping ($source)")
                return
            }
            val currentlyBonded = state.value.bonded || device.bondState == BluetoothDevice.BOND_BONDED
            if (!currentlyBonded) {
                logger.i(label, "${tt()} [PAIRING] Bond removal skipped; already cleared ($source)")
                return
            }
            bondRemovalInFlight = true
            try {
                logger.w(
                    label,
                    "${tt()} [PAIRING] Removing bond ($source reason=$reason at=${timestamp})",
                )
                uartClient.currentGatt()?.let { existing ->
                    runCatching { existing.disconnect() }
                        .onFailure {
                            logger.w(label, "${tt()} GATT disconnect before bond removal failed: ${it.message}")
                        }
                }
                val removed = device.removeBondCompat()
                logger.i(label, "${tt()} [PAIRING] removeBondCompat() -> $removed ($source)")
                if (removed) {
                    recordBondReset()
                }
            } finally {
                bondRemovalInFlight = false
            }
        }
        if (rebondDelayMs >= 0) {
            scope.launch {
                delay(rebondDelayMs)
                requestBond("rebond after $source ($reason)")
            }
        }
    }

    private fun describeHelloAck(event: AckEvent): String {
        return when {
            event.opcode == G1Protocols.CMD_HELLO -> "ack"
            event.warmup -> "warmup"
            else -> "signal"
        }
    }

    private fun recordBondReset() {
        val current = _state.value
        _state.value = current.copy(bondResetCount = current.bondResetCount + 1, bonded = false)
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.removeBondCompat(): Boolean {
        return runCatching {
            val method = this.javaClass.getMethod("removeBond")
            method.invoke(this) as? Boolean ?: false
        }.onFailure {
            logger.w(label, "${tt()} removeBond() reflection failed: ${it.message}")
        }.getOrElse { false }
    }

    private fun nextKeepAliveSequence(): Int {
        return keepAliveSequence.getAndUpdate { current ->
            val next = (current + 1) and 0xFF
            if (next == 0) KEEP_ALIVE_INITIAL_SEQUENCE else next
        }.and(0xFF)
    }

    private fun buildKeepAlivePayload(sequence: Int): ByteArray {
        val seqByte = (sequence and 0xFF).toByte()
        return byteArrayOf(KEEP_ALIVE_OPCODE.toByte(), seqByte)
    }

    private suspend fun acquireKeepAliveLock(deadlineMs: Long): Boolean {
        while (scope.isActive && System.currentTimeMillis() < deadlineMs) {
            if (writeMutex.tryLock()) {
                return true
            }
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }
            delay(minOf(KEEP_ALIVE_LOCK_POLL_INTERVAL_MS, remaining))
        }
        return false
    }

    private fun decrementKeepAliveInFlight(): Int {
        return keepAliveInFlight.getAndUpdate { current ->
            if (current <= 0) 0 else current - 1
        }
    }

    private fun ByteArray.toAudioFrameOrNull(): AudioFrame? {
        if (isEmpty()) return null
        val opcode = this[0].toInt() and 0xFF
        if (opcode != G1Protocols.CMD_KEEPALIVE) return null
        val sequence = this.getOrNull(1)?.toUByte()?.toInt() ?: 0
        val payload = if (size > 2) copyOfRange(2, size) else ByteArray(0)
        return AudioFrame(sequence, payload)
    }

    private fun Int?.toLabel(): String = this?.let { "${G1Protocols.opcodeName(it)}(${String.format("0x%02X", it)})" } ?: "unknown"
    private fun Int?.toHexString(): String = this?.let { String.format("0x%02X", it) } ?: "n/a"

    private fun Int.toByteHex(): String = String.format("0x%02X", this and 0xFF)

    private fun requestWarmupOnNextNotify() {
        warmupExpected = true
        if (_state.value.warmupOk) {
            _state.value = _state.value.copy(warmupOk = false)
        }
        uartClient.requestWarmupOnNextNotify()
    }

    private suspend fun awaitWarmupPrompt(): Boolean {
        if (_state.value.warmupOk) {
            return true
        }
        val target = withTimeoutOrNull(MTU_COMMAND_WARMUP_GRACE_MS) {
            state.first { candidate -> candidate.warmupOk }
        }
        return target != null
    }

    private fun State.isReady(): Boolean =
        bonded && (attMtu != null || warmupOk)

    private fun tt(): String = "[${Thread.currentThread().name}]"

    private fun handleBondRetry(reason: Int) {
        if (!BondRetryDecider.isTransientReason(reason)) {
            if (reason != UNBOND_REASON_UNKNOWN) {
                logger.i(
                    label,
                    "${tt()} Bond retry skipped for non-transient reason=${reason.toBondReasonString()}",
                )
            }
            bondRetryDecider.reset()
            bondRetryJob?.cancel()
            bondRetryJob = null
            return
        }
        val attempt = bondRetryDecider.nextRetryAttempt(reason)
        if (attempt == null) {
            logger.w(
                label,
                "${tt()} Bond retry limit reached for reason=${reason.toBondReasonString()}",
            )
            return
        }
        bondRetryJob?.cancel()
        bondRetryJob = scope.launch {
            delay(BOND_RETRY_DELAY_MS * attempt)
            logger.i(
                label,
                "${tt()} Scheduling createBond retry attempt=$attempt reason=${reason.toBondReasonString()}",
            )
            requestBond("retry #$attempt (${reason.toBondReasonString()})")
        }
    }

    private fun registerBondReceiverIfNeeded() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changedDevice?.address != device.address) return
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val reason = intent.getIntExtra(
                    EXTRA_REASON,
                    BOND_FAILURE_UNKNOWN,
                )
                logger.i(
                    label,
                    "${tt()} Bond state changed=$bondState reason=${reason.toBondReasonString()}",
                )
                val previousBondState = lastBondState
                updateBondState(bondState, reason)
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> scheduleGattConnection("bond receiver")
                    BluetoothDevice.BOND_NONE -> {
                        val reasonLabel = reason.toBondReasonString()
                        logger.i(label, "${tt()} Bond cleared; refreshing GATT cache (reason=$reasonLabel)")
                        scope.launch {
                            val refreshed = refreshGattCache()
                            logger.i(label, "${tt()} [GATT] Cache refresh result=$refreshed")
                        }
                        if (shouldAttemptRebondAfterLoss(previousBondState, bondState, reason)) {
                            scope.launch {
                                logger.i(
                                    label,
                                    "${tt()} Bond lost from BONDED; requesting rebond (reason=$reasonLabel)",
                                )
                                requestBond("rebond after loss ($reasonLabel)")
                            }
                        } else if (reason == UNBOND_REASON_REMOVED) {
                            scope.launch {
                                logger.w(
                                    label,
                                    "${tt()} Bond removed by system; requesting new bond (reason=$reasonLabel)",
                                )
                                requestBond("rebond after removal ($reasonLabel)")
                            }
                        }
                        handleBondRetry(reason)
                    }
                    BOND_STATE_REMOVED -> {
                        val reasonLabel = reason.toBondReasonString()
                        logger.w(
                            label,
                            "${tt()} Bond removed; refreshing GATT cache (reason=$reasonLabel)",
                        )
                        scope.launch {
                            val refreshed = refreshGattCache()
                            logger.i(label, "${tt()} [GATT] Cache refresh result=$refreshed")
                        }
                        scope.launch {
                            logger.w(
                                label,
                                "${tt()} Bond entry removed by system; requesting new bond (reason=$reasonLabel)",
                            )
                            requestBond("rebond after removal ($reasonLabel)")
                        }
                        handleBondRetry(reason)
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

    private fun emitBondEvent(state: Int, reason: Int) {
        bondEvents.tryEmit(BondEvent(state, reason, System.currentTimeMillis()))
    }

    private fun incrementBondTimeout() {
        val current = _state.value
        val nextCount = current.bondTimeoutCount + 1
        _state.value = current.copy(bondTimeoutCount = nextCount)
        logger.w(label, "${tt()} Bond await timed out (count=$nextCount)")
    }

    private fun recordRefreshInvocation() {
        val current = _state.value
        _state.value = current.copy(refreshCount = current.refreshCount + 1)
    }

    private fun recordBondAttempt() {
        val current = _state.value
        _state.value = current.copy(bondAttemptCount = current.bondAttemptCount + 1)
    }

    private fun recordBondResult(result: BondResult) {
        val current = _state.value
        if (current.lastBondResult == result) return
        _state.value = current.copy(lastBondResult = result)
    }

    private fun recordPairingDialogShown() {
        val current = _state.value
        _state.value = current.copy(pairingDialogsShown = current.pairingDialogsShown + 1)
    }

    private fun schedulePairingDialogWatchdog(reason: String) {
        cancelPairingDialogWatchdog()
        val runnable = Runnable {
            if (_state.value.bonded || device.bondState == BluetoothDevice.BOND_BONDED) {
                return@Runnable
            }
            if (settingsLaunchedThisSession) {
                logger.i(label, "${tt()} [PAIRING] Settings prompt already issued; skipping (reason=$reason)")
                return@Runnable
            }
            if (appIsInForeground()) {
                logger.w(label, "${tt()} [PAIRING] Showing Bluetooth settings (reason=$reason)")
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                    .onSuccess {
                        settingsLaunchedThisSession = true
                        recordPairingDialogShown()
                    }
                    .onFailure {
                        logger.w(label, "${tt()} Failed to launch Bluetooth settings: ${it.message}")
                        showPairingNotification(reason)
                    }
            } else {
                logger.w(label, "${tt()} [PAIRING] App backgrounded; posting notification (reason=$reason)")
                showPairingNotification(reason)
            }
        }
        pairingDialogRunnable = runnable
        mainHandler.postDelayed(runnable, BOND_DIALOG_GRACE_MS)
    }

    private fun cancelPairingDialogWatchdog() {
        pairingDialogRunnable?.let { mainHandler.removeCallbacks(it) }
        pairingDialogRunnable = null
        dismissPairingNotification()
    }

    private fun showPairingNotification(reason: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PAIRING_NOTIFICATION_CHANNEL_ID,
                PAIRING_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PAIRING_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, PAIRING_NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Complete Bluetooth pairing")
            .setContentText("Tap to continue pairing ${device.name ?: "your lens"}")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)

        settingsLaunchedThisSession = true
        manager.notify(PAIRING_NOTIFICATION_ID, builder.build())
        recordPairingDialogShown()
    }

    private fun dismissPairingNotification() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(PAIRING_NOTIFICATION_ID)
    }

    private fun appIsInForeground(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = manager.runningAppProcesses ?: return false
        val myPid = Process.myPid()
        return processes.any { process ->
            process.pid == myPid &&
                (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        }
    }

    private suspend fun resetGattBeforeBond(reason: String) {
        val existing = uartClient.currentGatt() ?: return
        logger.i(label, "${tt()} [PAIRING] Resetting cached GATT before bond ($reason)")
        runCatching { existing.disconnect() }
            .onFailure { logger.w(label, "${tt()} GATT disconnect before bond failed: ${it.message}") }
        val refreshed = withContext(Dispatchers.IO) {
            existing.refreshCompat { message ->
                logger.i(label, "${tt()} $message")
            }
        }
        runCatching { existing.close() }
            .onFailure { logger.w(label, "${tt()} GATT close before bond failed: ${it.message}") }
        uartClient.close()
        if (refreshed) {
            recordRefreshInvocation()
        }
        delay(GATT_RESET_DELAY_MS)
    }

    private suspend fun requestBond(reason: String) {
        bondMutex.withLock {
            if (_state.value.bonded) {
                logger.i(label, "${tt()} [PAIRING] Bond already completed; skipping request ($reason)")
                return
            }
            resetGattBeforeBond(reason)
            recordBondAttempt()
            val name = device.name ?: device.address ?: "unknown"
            logger.i(label, "${tt()} [PAIRING] Requesting bond with $name ($reason)")
            val initiated = withContext(Dispatchers.Main) {
                device.createBondCompat()
            }
            logger.i(label, "${tt()} Initiating bond (transport LE) queued=$initiated")
            if (initiated) {
                schedulePairingDialogWatchdog(reason)
                return
            }
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    recordBondResult(BondResult.Success)
                    logger.i(label, "${tt()} Bond already completed prior to request")
                    scheduleGattConnection("pre-bonded")
                }
                BluetoothDevice.BOND_BONDING -> {
                    cancelPairingDialogWatchdog()
                    logger.i(label, "${tt()} Bond already in progress; awaiting broadcast")
                }
                else -> {
                    recordBondResult(BondResult.Failed)
                    logger.w(label, "${tt()} createBond() returned false; emitting current state")
                    emitBondEvent(device.bondState, BOND_FAILURE_UNKNOWN)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.createBondCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return runCatching {
                val method = BluetoothDevice::class.java.getMethod(
                    "createBond",
                    Int::class.javaPrimitiveType,
                )
                method.invoke(this, BluetoothDevice.TRANSPORT_LE) as? Boolean ?: false
            }.onFailure {
                logger.w(label, "${tt()} createBondCompat reflection failed: ${it.message}")
            }.getOrElse { false }
        }
        return createBond()
    }

    private fun maybeStartGattConnection() {
        if (connectionInitiated) return
        connectionInitiated = true
        uartClient.connect()
    }

    private fun updateBondState(state: Int, reason: Int = BOND_FAILURE_UNKNOWN) {
        val previousBondState = lastBondState
        emitBondEvent(state, reason)
        val current = _state.value
        val wasBonded = current.bonded
        val isBonded = state == BluetoothDevice.BOND_BONDED
        val eventTimestamp = System.currentTimeMillis()
        var next = current.copy(
            bonded = isBonded,
            lastBondState = state,
            lastBondReason = reason.takeUnless { it == BOND_FAILURE_UNKNOWN },
            lastBondEventAt = eventTimestamp,
        )
        if (state != previousBondState) {
            next = next.copy(bondTransitionCount = next.bondTransitionCount + 1)
        }
        _state.value = next
        lastBondState = state
        if (isBonded && !wasBonded) {
            logger.i(label, "${tt()} [PAIRING] Bonded ✅")
            requestWarmupOnNextNotify()
            scheduleGattConnection("bond complete")
            bondRetryDecider.reset()
            bondRetryJob?.cancel()
            bondRetryJob = null
            cancelPairingDialogWatchdog()
            recordBondResult(BondResult.Success)
        } else if (!isBonded) {
            warmupExpected = false
            if (_state.value.warmupOk) {
                _state.value = _state.value.copy(warmupOk = false)
            }
            bondConnectJob?.cancel()
            bondConnectJob = null
            if (state != BluetoothDevice.BOND_BONDING) {
                bondRetryJob?.cancel()
                bondRetryJob = null
            }
            if (state == BluetoothDevice.BOND_NONE && previousBondState == BluetoothDevice.BOND_BONDING) {
                recordBondResult(BondResult.Failed)
            }
            if (state == BluetoothDevice.BOND_BONDING) {
                cancelPairingDialogWatchdog()
            } else if (state == BluetoothDevice.BOND_NONE) {
                cancelPairingDialogWatchdog()
            }
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

    suspend fun refreshGattCache(loggerOverride: ((String) -> Unit)? = null): Boolean {
        val log = loggerOverride ?: { message: String ->
            logger.i(label, "${tt()} $message")
        }
        val refreshed = runCatching {
            uartClient.refreshGattCache(log)
        }.onFailure {
            logger.w(label, "${tt()} GATT refresh failed: ${it.message}")
        }.getOrElse { false }
        recordRefreshInvocation()
        return refreshed
    }

    fun refreshDeviceCache() {
        scope.launch {
            val refreshed = refreshGattCache()
            logger.i(label, "${tt()} [GATT] Manual refresh result=$refreshed")
        }
    }

}
