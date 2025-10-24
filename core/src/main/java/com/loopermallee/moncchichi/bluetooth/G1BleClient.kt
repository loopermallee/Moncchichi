package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
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
) {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    data class State(
        val status: ConnectionState = ConnectionState.DISCONNECTED,
        val rssi: Int? = null,
    )

    private val uartClient = G1BleUartClient(context, device, logger::i, scope)
    private val ackSignals = Channel<Unit>(capacity = Channel.CONFLATED)
    private val _ackEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val ackEvents: SharedFlow<Long> = _ackEvents.asSharedFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val writeMutex = Mutex()
    private var monitorJob: Job? = null
    private var rssiJob: Job? = null

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

        uartClient.connect()
    }

    fun close() {
        monitorJob?.cancel()
        rssiJob?.cancel()
        monitorJob = null
        rssiJob = null
        ackSignals.trySend(Unit) // unblock waiters before closing
        uartClient.close()
        _state.value = State(status = ConnectionState.DISCONNECTED)
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

    private fun ByteArray.detectAck(): Boolean {
        if (isEmpty()) return false
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            if (value == 0xC9 || value == 0x04) {
                return true
            }
        }
        return false
    }

    private fun tt(): String = "[${Thread.currentThread().name}]"
}
