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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

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

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    data class State(
        val status: ConnectionState = ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
        val bonded: Boolean = false,
    )

    private val uartClient = uartClientFactory(
        context,
        device,
        { message -> logger.i(label, "[BLE] $message") },
        scope,
    )
    private val ackSignals = Channel<Unit>(capacity = Channel.CONFLATED)
    private val _ackEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val ackEvents: SharedFlow<Long> = _ackEvents.asSharedFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    private val _state = MutableStateFlow(
        State(bonded = device.bondState == BluetoothDevice.BOND_BONDED)
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val writeMutex = Mutex()
    private var monitorJob: Job? = null
    private var rssiJob: Job? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionInitiated = false

    private val lastAckTimestamp = AtomicLong(0L)

    fun connect() {
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

        scope.launch {
            uartClient.observeNotifications { payload ->
                if (payload.detectAck()) {
                    lastAckTimestamp.set(System.currentTimeMillis())
                    _ackEvents.tryEmit(lastAckTimestamp.get())
                    ackSignals.trySend(Unit)
                }
                _incoming.tryEmit(payload.copyOf())
            }
        }

        connectionInitiated = false
        registerBondReceiverIfNeeded()
        updateBondState(device.bondState)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                uartClient.requestWarmupOnNextNotify()
                maybeStartGattConnection()
            }
            BluetoothDevice.BOND_NONE -> {
                val bonded = device.createBond()
                logger.i(label, "${tt()} Initiating bond=${bonded}")
            }
            BluetoothDevice.BOND_BONDING -> logger.i(label, "${tt()} Awaiting ongoing bond")
        }
    }

    fun close() {
        monitorJob?.cancel()
        rssiJob?.cancel()
        monitorJob = null
        rssiJob = null
        unregisterBondReceiver()
        connectionInitiated = false
        ackSignals.trySend(Unit) // unblock waiters before closing
        uartClient.close()
        _state.value = State(
            status = ConnectionState.DISCONNECTED,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
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
            repeat(retries) { attempt ->
                // Clear any stale ACK before writing.
                while (ackSignals.tryReceive().isSuccess) {
                    // Drain the channel.
                }
                val queued = uartClient.write(payload)
                if (!queued) {
                    logger.w(label, "${tt()} Failed to enqueue write (attempt ${attempt + 1})")
                    delay(retryDelayMs)
                    return@repeat
                }
                val acked = withTimeoutOrNull(ackTimeoutMs) {
                    ackSignals.receive()
                } != null
                if (acked) {
                    return true
                }
                logger.w(label, "${tt()} ACK timeout (attempt ${attempt + 1})")
                delay(retryDelayMs)
            }
            false
        }
    }

    fun readRemoteRssi(): Boolean = uartClient.readRemoteRssi()

    fun lastAckTimestamp(): Long = lastAckTimestamp.get()

    internal fun ByteArray.detectAck(): Boolean {
        if (size >= 2) {
            for (index in 0 until size - 1) {
                val first = this[index].toInt() and 0xFF
                val second = this[index + 1].toInt() and 0xFF
                if ((first == 0xC9 && second == 0x04) || (first == 0x04 && second == 0xCA)) {
                    return true
                }
            }
        }

        val ascii = runCatching { decodeToString() }.getOrNull() ?: return false
        val trimmed = ascii.trim { it.code <= 0x20 }
        if (trimmed.equals("OK", ignoreCase = true)) {
            return true
        }

        val normalized = trimmed.uppercase()
        if (trimmed == normalized && normalized.startsWith("ACK:")) {
            // These ACK:<TOKEN> strings are observed from the firmware but not part of
            // any published protocol specification. Treat them as successful ACKs so the
            // client remains resilient to keepalive and ping responses.
            return true
        }

        return false
    }

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
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    maybeStartGattConnection()
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
            uartClient.requestWarmupOnNextNotify()
        }
    }
}
