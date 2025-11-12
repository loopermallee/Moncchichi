package com.loopermallee.moncchichi.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.loopermallee.moncchichi.bluetooth.G1Protocols
import com.loopermallee.moncchichi.bluetooth.refreshCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust Nordic UART (NUS) client for Even G1 smart glasses.
 * Handles connection, notification arming, and incoming data flow.
 */
class G1BleUartClient(
    private val context: Context,
    private val bluetoothDevice: BluetoothDevice,
    private val logger: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val SMP_SERVICE: UUID = UUID.fromString("8D53DC1D-1DB7-4CD3-868B-8A527460AA84")
        val SMP_CHAR: UUID = UUID.fromString("8D53DC1D-1DB7-4CD3-868B-8A527460AA85")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ENABLE_NOTIFY = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        private val ENABLE_NOTIFY_IND = byteArrayOf(0x03, 0x00)
        private const val DEFAULT_ATT_MTU = 23
        private const val DESIRED_ATT_MTU = 498
        private const val NOTIFY_ARM_TIMEOUT_MS = 1_000L
        private const val EVEN_STEP_DELAY_MS = 200L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var smpChar: BluetoothGattCharacteristic? = null

    /** Indicates whether we should issue a diagnostic warm-up once notifications arm. */
    @Volatile private var warmupPending: Boolean = false
    @Volatile private var warmupSentOnce: Boolean = false

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    data class ConnectionEvent(val status: Int, val newState: Int)

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    private val _smpNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val smpNotifications: SharedFlow<ByteArray> = _smpNotifications.asSharedFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()
    private val _mtu = MutableStateFlow(DEFAULT_ATT_MTU)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()
    private val _notificationsArmed = MutableStateFlow(false)
    val notificationsArmed: StateFlow<Boolean> = _notificationsArmed.asStateFlow()

    private val notifyArmed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    var onConnectAction: ((BluetoothGatt) -> Unit)? = null

    fun connect() {
        if (connecting.getAndSet(true)) return
        logger("[SERVICE][CONNECT] Connecting to ${bluetoothDevice.address}")
        _connectionState.value = ConnectionState.CONNECTING
        gatt = bluetoothDevice.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            logger("[ERROR] connectGatt returned null")
            connecting.set(false)
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun close() {
        warmupSentOnce = false
        warmupPending = false
        notifyArmed.set(false)
        _notificationsArmed.value = false
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        rxChar = null
        txChar = null
        smpChar = null
        connecting.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED
        _rssi.value = null
        _mtu.value = DEFAULT_ATT_MTU
        if (_notificationsArmed.value) {
            _notificationsArmed.value = false
            logger("[BLE][CCCD] Notifications disarmed (client closed)")
        }
    }

    private fun maybeSendWarmupAfterNotifyArmed() {
        if (!warmupPending) return
        if (!notifyArmed.get() || warmupSentOnce) return
        val wrote = write("ver\n".toByteArray(StandardCharsets.UTF_8), withResponse = false)
        warmupSentOnce = wrote
        warmupPending = false
        logger("[BLE][NUS] warm-up 'ver\\n' sent=$wrote")
    }

    fun requestWarmupOnNextNotify() {
        warmupPending = true
        maybeSendWarmupAfterNotifyArmed()
    }

    fun currentGatt(): BluetoothGatt? = gatt

    suspend fun refreshGattCache(log: (String) -> Unit): Boolean {
        val existing = gatt
        if (existing == null) {
            log("[GATT] refresh() skipped – no active connection")
            return false
        }
        val refreshed = withContext(Dispatchers.IO) {
            existing.refreshCompat(log)
        }
        close()
        return refreshed
    }

    fun write(bytes: ByteArray, withResponse: Boolean = false): Boolean {
        val ch = rxChar ?: return false.also { logger("[APP][WRITE] RX not ready") }
        ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        val characteristic = ch.apply { value = bytes }
        @Suppress("DEPRECATION")
        val ok = gatt?.writeCharacteristic(characteristic) == true
        logger("[APP][WRITE] ${if (ok) "Queued" else "FAILED"} (${bytes.size} bytes)")
        return ok
    }

    fun readRemoteRssi(): Boolean {
        return gatt?.readRemoteRssi() == true
    }

    suspend fun observeNotifications(collector: FlowCollector<ByteArray>) {
        incoming.collect(collector)
    }

    // ----------- Callbacks -----------
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logger("[GATT] Connection state change status=$status newState=$newState")
            _connectionEvents.tryEmit(ConnectionEvent(status, newState))
            if (status != BluetoothGatt.GATT_SUCCESS) {
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting.set(false)
                    _connectionState.value = ConnectionState.CONNECTED
                    val action = onConnectAction
                    if (action != null) {
                        action(g)
                    } else {
                        logger("[SERVICE] Connected; discovering services…")
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger("[SERVICE] Disconnected with status=$status")
                    warmupSentOnce = false
                    notifyArmed.set(false)
                    _connectionState.value = ConnectionState.DISCONNECTED
                    close()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connectionState.value = ConnectionState.CONNECTING
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logger("[GATT] Services discovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger("[SERVICE] Service discovery failed with status=$status")
                return
            }
            val svc = g.getService(NUS_SERVICE)
            if (svc == null) {
                logger("[ERROR] NUS service not found")
                return
            }
            rxChar = svc.getCharacteristic(NUS_RX)
            txChar = svc.getCharacteristic(NUS_TX)
            if (rxChar == null || txChar == null) {
                logger("[ERROR] Missing RX/TX chars")
                return
            }
            val smpService = g.getService(SMP_SERVICE)
            smpChar = smpService?.getCharacteristic(SMP_CHAR)
            scope.launch { performEvenBringUp(g) }
        }

        // Android 13+ variant
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX) {
                val ackSuffix = if (isAck(value)) " [ACK]" else ""
                logger("[DEVICE][NOTIFY] ${characteristic.uuid} (${value.size} bytes)$ackSuffix")
                _incoming.tryEmit(value)
            } else if (characteristic.uuid == SMP_CHAR) {
                val opcode = value.firstOrNull()?.toInt()?.and(0xFF)
                val opcodeLabel = opcode?.let { String.format("0x%02X", it) } ?: "n/a"
                logger("[SMP][NOTIFY] ${value.size} bytes opcode=$opcodeLabel")
                if (opcode == 0x01) {
                    logger("[SMP] Pairing Request detected; relying on Android bonding stack")
                }
                _smpNotifications.tryEmit(value)
            }
        }

        // Legacy 2-arg variant
        @Deprecated("Upstream API deprecated; kept for compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onCharacteristicChanged(g, c, it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD && d.characteristic.uuid == NUS_TX) {
                @Suppress("DEPRECATION")
                val descriptorValue = d.value
                logger("[SERVICE][CCCD] Write status=$status value=${descriptorValue?.toHex()}")
                val armed = status == BluetoothGatt.GATT_SUCCESS
                notifyArmed.set(armed)
                if (_notificationsArmed.value != armed) {
                    _notificationsArmed.value = armed
                    val stateLabel = if (armed) "armed" else "disarmed"
                    logger("[SERVICE][CCCD] Notifications $stateLabel (status=$status)")
                }
                if (armed) {
                    maybeSendWarmupAfterNotifyArmed()
                }
            } else if (d.uuid == CCCD && d.characteristic.uuid == SMP_CHAR) {
                @Suppress("DEPRECATION")
                val descriptorValue = d.value
                logger("[SMP][CCCD] Write status=$status value=${descriptorValue?.toHex()}")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logger("[BLE][MTU] onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtu.value = mtu
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }

    suspend fun armNotificationsWithRetry(retries: Int = 3, delayMs: Long = 300): Boolean {
        val gattRef = gatt ?: return false.also {
            logger("[SERVICE][CCCD] armNotifications skipped – no active GATT")
        }
        val tx = txChar ?: return false.also {
            logger("[SERVICE][CCCD] armNotifications skipped – RX characteristic missing")
        }
        if (_notificationsArmed.value) {
            logger("[SERVICE][CCCD] Notifications already armed")
            return true
        }
        var attempt = 0
        while (attempt <= retries) {
            val modes = if (attempt == 0) {
                listOf(ENABLE_NOTIFY, ENABLE_NOTIFY_IND)
            } else {
                listOf(ENABLE_NOTIFY)
            }
            for (mode in modes) {
                if (tryArmNotifications(gattRef, tx, mode)) {
                    logger("[SERVICE][CCCD] Notifications armed via mode=${mode.toHex()}")
                    return true
                }
            }
            attempt += 1
            if (attempt <= retries) {
                delay(delayMs)
            }
        }
        val armed = _notificationsArmed.value
        if (!armed) {
            logger("[ERROR] Failed to arm TX notifications after ${retries + 1} attempts")
        }
        return armed
    }

    private suspend fun tryArmNotifications(
        g: BluetoothGatt,
        tx: BluetoothGattCharacteristic,
        mode: ByteArray,
    ): Boolean {
        val setOk = g.setCharacteristicNotification(tx, true)
        logger("[SERVICE][CCCD] setCharacteristicNotification=$setOk")
        if (!setOk) {
            return false
        }
        val cccd = tx.getDescriptor(CCCD) ?: run {
            logger("[ERROR] CCCD not found")
            return false
        }
        @Suppress("DEPRECATION")
        cccd.value = mode
        @Suppress("DEPRECATION")
        val queued = g.writeDescriptor(cccd)
        @Suppress("DEPRECATION")
        val loggedValue = cccd.value
        logger("[SERVICE][CCCD] writeDescriptor queued=$queued value=${loggedValue?.toHex() ?: mode.toHex()}")
        if (!queued) {
            return false
        }
        val armed = withTimeoutOrNull(NOTIFY_ARM_TIMEOUT_MS) {
            notificationsArmed.filter { armed -> armed }.first()
        } ?: false
        if (!armed) {
            logger("[SERVICE][CCCD] Arm attempt timed out after ${NOTIFY_ARM_TIMEOUT_MS}ms")
        }
        return armed
    }

    private suspend fun performEvenBringUp(gatt: BluetoothGatt) {
        delay(EVEN_STEP_DELAY_MS)
        val requested = withContext(Dispatchers.Main) { gatt.requestMtu(DESIRED_ATT_MTU) }
        logger("[BLE][MTU] requestMtu($DESIRED_ATT_MTU) queued=$requested")
        delay(EVEN_STEP_DELAY_MS)
        logger("[SERVICE] Found NUS RX/TX; arming TX notifications…")
        val armed = armNotificationsWithRetry()
        if (!armed) {
            return
        }
        delay(EVEN_STEP_DELAY_MS)
        val smp = smpChar
        if (smp != null) {
            logger("[SMP] Service discovered; enabling notifications")
            enableSmpNotifications(gatt, smp)
        } else {
            logger("[SMP] Service not present on peripheral")
        }
    }

    private fun enableSmpNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        scope.launch {
            val enabled = g.setCharacteristicNotification(characteristic, true)
            logger("[SMP][CCCD] setCharacteristicNotification=$enabled")
            if (enabled) {
                val descriptor = characteristic.getDescriptor(CCCD)
                if (descriptor != null) {
                    @Suppress("DEPRECATION")
                    descriptor.value = ENABLE_NOTIFY
                    @Suppress("DEPRECATION")
                    val queued = g.writeDescriptor(descriptor)
                    @Suppress("DEPRECATION")
                    val descriptorValue = descriptor.value
                    logger("[SMP][CCCD] writeDescriptor queued=$queued value=${descriptorValue?.toHex()}")
                } else {
                    logger("[SMP] CCCD missing; notifications unavailable")
                }
            }
        }
    }

    private fun isAck(packet: ByteArray): Boolean {
        if (packet.isNotEmpty()) {
            val head = packet[0]
            if (head == G1Protocols.STATUS_OK.toByte() || head == 0x04.toByte()) {
                return true
            }
        }
        val ascii = runCatching { String(packet, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        return ascii.trim() == "OK"
    }
}

private val HEX = "0123456789ABCDEF".toCharArray()
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val i = b.toInt()
        out.append(HEX[(i ushr 4) and 0x0F])
        out.append(HEX[i and 0x0F])
    }
    return out.toString()
}
