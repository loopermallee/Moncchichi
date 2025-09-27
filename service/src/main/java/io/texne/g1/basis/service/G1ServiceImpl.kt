package io.texne.g1.basis.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import io.texne.g1.basis.service.protocol.IG1Service
import io.texne.g1.basis.service.protocol.IG1StateCallback
import io.texne.g1.basis.service.protocol.G1ServiceState
import io.texne.g1.basis.service.protocol.OperationCallback
import java.util.UUID

class G1ServiceImpl : IG1Service.Stub() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var gatt: BluetoothGatt? = null
    private var stateCallback: IG1StateCallback? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            Log.d(TAG, "Scan result: $result")
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    override fun connectGlasses(id: String?, callback: OperationCallback?) {
        Log.d(TAG, "connectGlasses(id=$id, callback=$callback)")
        connectGlassesById(id)
        callback?.onResult(false)
    }

    override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
        Log.d(TAG, "disconnectGlasses(id=$id, callback=$callback)")
        disconnectPreferredGlasses()
        callback?.onResult(true)
    }

    override fun displayTextPage(
        id: String?,
        page: Array<out String?>?,
        callback: OperationCallback?,
    ) {
        val message = page?.filterNotNull()?.joinToString("\n")
        if (message != null) {
            writeDisplayText(message)
            callback?.onResult(true)
        } else {
            Log.w(TAG, "displayTextPage(page=$page) missing text content")
            callback?.onResult(false)
        }
    }

    override fun stopDisplaying(id: String?, callback: OperationCallback?) {
        sendStopDisplayingCommand()
        callback?.onResult(true)
    }

    override fun connectGlassesById(deviceAddress: String?) {
        Log.d(TAG, "connectGlassesById(deviceAddress=$deviceAddress)")
    }

    override fun disconnectGlassesById(deviceAddress: String?) {
        Log.d(TAG, "disconnectGlassesById(deviceAddress=$deviceAddress)")
        disconnectPreferredGlasses()
    }

    override fun connectPreferredGlasses() {
        Log.d(TAG, "connectPreferredGlasses()")
    }

    override fun disconnectPreferredGlasses() {
        Log.d(TAG, "disconnectPreferredGlasses()")
    }

    override fun isConnected(): Boolean {
        val connected = gatt != null
        Log.d(TAG, "isConnected() -> $connected")
        return connected
    }

    override fun sendMessage(msg: String?) {
        Log.d(TAG, "sendMessage(msg=$msg)")
    }

    override fun lookForGlasses() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter not available")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Bluetooth LE scanner not available")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Looking for glasses...")
    }

    override fun observeState(callback: IG1StateCallback?) {
        stateCallback = callback
        val charUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(charUuid)
        if (characteristic != null) {
            gatt?.setCharacteristicNotification(characteristic, true)
            Log.d(TAG, "Observing state...")
        } else {
            Log.w(TAG, "State characteristic not available")
        }
    }

    override fun displayLegacyTextPage(text: String?, page: Int, flags: Int) {
        Log.d(TAG, "displayLegacyTextPage(text=$text, page=$page, flags=$flags)")
        if (text != null) {
            writeDisplayText(text)
        }
    }

    override fun stopDisplayingWithFlags(flags: Int) {
        Log.d(TAG, "stopDisplayingWithFlags(flags=$flags)")
        sendStopDisplayingCommand()
    }

    private fun writeDisplayText(text: String) {
        val charUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(charUuid)
        if (characteristic != null) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            characteristic.value = bytes
            gatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "Displaying text: $text")
        } else {
            Log.w(TAG, "Display characteristic not available")
        }
    }

    private fun sendStopDisplayingCommand() {
        val charUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(charUuid)
        if (characteristic != null) {
            characteristic.value = byteArrayOf(0x00)
            gatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "Stopping display")
        } else {
            Log.w(TAG, "Display characteristic not available")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid.toString().equals(STATE_CHAR_UUID, ignoreCase = true)) {
                val data = characteristic.value
                if (data.size >= 2) {
                    val connected = data[0].toInt() == 1
                    val battery = data[1].toInt() and 0xFF
                    val status = if (connected) G1ServiceState.LOOKED else G1ServiceState.READY
                    Log.d(TAG, "State update: connected=$connected, battery=$battery")
                    stateCallback?.onStateChanged(status, null)
                } else {
                    Log.w(TAG, "State update payload too short: ${'$'}{data.size}")
                }
            }
        }
    }

    private companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private const val STATE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
        private const val TAG = "G1ServiceImpl"
    }
}
