package com.loopermallee.moncchichi.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.charset.StandardCharsets

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
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ENABLE_NOTIFY = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        private val ENABLE_NOTIFY_IND = byteArrayOf(0x03, 0x00)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    /** Feature flag for diagnostic warm-up after notifications arm. */
    private val enableWarmup: Boolean = false
    @Volatile private var warmupSentOnce: Boolean = false

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val notifyArmed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

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
        notifyArmed.set(false)
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        rxChar = null
        txChar = null
        connecting.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED
        _rssi.value = null
    }

    private fun maybeSendWarmupAfterNotifyArmed() {
        if (!enableWarmup) return
        if (!notifyArmed.get() || warmupSentOnce) return
        val wrote = write("ver\n".toByteArray(StandardCharsets.UTF_8), withResponse = false)
        warmupSentOnce = wrote
        logger("[BLE][NUS] warm-up 'ver\\n' sent=$wrote")
    }

    fun write(bytes: ByteArray, withResponse: Boolean = false): Boolean {
        val ch = rxChar ?: return false.also { logger("[APP][WRITE] RX not ready") }
        ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = bytes
        val ok = gatt?.writeCharacteristic(ch) == true
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting.set(false)
                    _connectionState.value = ConnectionState.CONNECTED
                    logger("[SERVICE] Connected; discovering services…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
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
            logger("[SERVICE] Found NUS RX/TX; arming TX notifications…")
            armNotificationsWithRetry(g, txChar!!)
        }

        // Android 13+ variant
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX) {
                logger("[DEVICE][NOTIFY] ${characteristic.uuid} (${value.size} bytes)")
                _incoming.tryEmit(value)
            }
        }

        // Legacy 2-arg variant
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onCharacteristicChanged(g, c, it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD && d.characteristic.uuid == NUS_TX) {
                logger("[SERVICE][CCCD] Write status=$status value=${d.value?.toHex()}")
                val armed = status == BluetoothGatt.GATT_SUCCESS
                notifyArmed.set(armed)
                if (armed) {
                    maybeSendWarmupAfterNotifyArmed()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logger("[BLE][MTU] onMtuChanged mtu=$mtu status=$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }

    private fun armNotificationsWithRetry(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
        scope.launch {
            val attempts = listOf(ENABLE_NOTIFY, ENABLE_NOTIFY_IND)
            var ok = false
            for (mode in attempts) {
                ok = enableNotifyOnce(g, tx, mode)
                if (ok) break
                delay(200)
            }
            if (!ok) repeat(3) {
                if (enableNotifyOnce(g, tx, ENABLE_NOTIFY)) return@launch
                delay(300)
            }
            logger(if (notifyArmed.get()) "[SERVICE] TX notifications armed ✔" else "[ERROR] Failed to arm TX notifications ❌")
        }
    }

    private fun enableNotifyOnce(g: BluetoothGatt, tx: BluetoothGattCharacteristic, mode: ByteArray): Boolean {
        val setOk = g.setCharacteristicNotification(tx, true)
        logger("[SERVICE][CCCD] setCharacteristicNotification=$setOk")
        val cccd = tx.getDescriptor(CCCD) ?: return false.also { logger("[ERROR] CCCD not found") }
        cccd.value = mode
        val writeOk = g.writeDescriptor(cccd)
        logger("[SERVICE][CCCD] writeDescriptor queued=$writeOk value=${mode.toHex()}")
        repeat(10) {
            if (notifyArmed.get()) return true
            Thread.sleep(50)
        }
        return notifyArmed.get()
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
