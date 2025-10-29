package com.loopermallee.moncchichi.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

private fun Data.toByteArray(): ByteArray {
    val size = size()
    val array = ByteArray(size)
    for (index in 0 until size) {
        array[index] = getByte(index) ?: 0
    }
    return array
}

@SuppressLint("MissingPermission")
internal class G1BLEManager(
    private val deviceName: String,
    private val deviceAddress: String,
    context: Context,
    private val coroutineScope: CoroutineScope,
): BleManager(context) {

    private val writableConnectionState = MutableStateFlow<G1.ConnectionState>(G1.ConnectionState.CONNECTING)
    val connectionState = writableConnectionState.asStateFlow()
    private val writableIncoming = MutableSharedFlow<IncomingPacket>()
    val incoming = writableIncoming.asSharedFlow()

    private var deviceGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    override fun initialize() {
        createBondInsecure()
        requestMtu(251)
            .enqueue()
        setConnectionObserver(object: ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTING
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                // EMPTY
            }

            override fun onDeviceFailedToConnect(
                device: BluetoothDevice,
                reason: Int
            ) {
                writableConnectionState.value = G1.ConnectionState.ERROR
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.CONNECTED
                SendTextPacket.resetSequence()
                HeartbeatPacket.resetSequence(deviceAddress)
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTING
            }

            override fun onDeviceDisconnected(
                device: BluetoothDevice,
                reason: Int
            ) {
                writableConnectionState.value = G1.ConnectionState.DISCONNECTED
                SendTextPacket.resetSequence()
                HeartbeatPacket.resetSequence(deviceAddress)
            }
        })
        val notificationCharacteristic = readCharacteristic
        if (notificationCharacteristic == null) {
            Log.w("G1BLEManager", "initialize: read characteristic unavailable")
            return
        }
        setNotificationCallback(notificationCharacteristic).with { device, data ->
            val identifier = device.name?.split('_')?.getOrNull(2) ?: device.address ?: "G1"
            val packet = IncomingPacket.fromBytes(data.toByteArray())
            if(packet == null) {
                Log.d("G1BLEManager", "TRAFFIC_LOG $identifier - $packet")
            } else {
                Log.d("G1BLEManager", "TRAFFIC_LOG $identifier - $packet")
                coroutineScope.launch {
                    writableIncoming.emit(packet)
                }
            }
        }
        enableNotifications(notificationCharacteristic)
    }

    //

    fun send(packet: OutgoingPacket): Boolean {
        Log.d("G1BLEManager", "G1_TRAFFIC_SEND ${packet.bytes.map { String.format("%02x", it) }.joinToString(" ")}")

        var attemptsRemaining = 3
        var success: Boolean = false
        val characteristic = writeCharacteristic
        if (characteristic == null) {
            Log.w("G1BLEManager", "send: write characteristic unavailable")
            return false
        }

        while(!success && attemptsRemaining > 0) {
            if(--attemptsRemaining != 2) {
                Log.d("G1BLEManager", "G1_TRAFFIC_SEND retrying, attempt ${3-attemptsRemaining}")
            }
            success = try {
                writeCharacteristic(
                    characteristic,
                    packet.bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ).await()
                true
            } catch (e: Exception) {
                // the request failed
                false
            }
        }
        return success
    }

    //

    override fun getMinLogPriority(): Int {
        return Log.VERBOSE
    }
    override fun log(priority: Int, message: String) {
        Log.println(priority, "G1BLEManager", message)
    }

    //

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID.fromString(UART_SERVICE_UUID))
        if(service != null) {
            val write = service.getCharacteristic(UUID.fromString(UART_WRITE_CHARACTERISTIC_UUID))
            if(write != null) {
                val read = service.getCharacteristic(UUID.fromString(UART_READ_CHARACTERISTIC_UUID))
                if(read != null) {
                    writeCharacteristic = write
                    readCharacteristic = read
                    deviceGatt = gatt
                    gatt.setCharacteristicNotification(read, true)
                    return true
                }
            }
        }
        return false
    }


    override fun onServicesInvalidated() {
        writeCharacteristic = null
        readCharacteristic = null
        writableConnectionState.value = G1.ConnectionState.DISCONNECTED
    }
}
