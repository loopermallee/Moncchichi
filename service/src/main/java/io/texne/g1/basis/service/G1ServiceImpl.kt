package io.texne.g1.basis.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import io.texne.g1.basis.service.protocol.IG1Service
import java.util.UUID

class G1ServiceImpl : IG1Service.Stub() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var gatt: BluetoothGatt? = null

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

    override fun connectGlasses(deviceAddress: String?) {
        Log.d(TAG, "connectGlasses(deviceAddress=$deviceAddress)")
    }

    override fun disconnectGlasses() {
        Log.d(TAG, "disconnectGlasses()")
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

    override fun observeState() {
        val charUuid = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(charUuid)
        if (characteristic != null) {
            gatt?.setCharacteristicNotification(characteristic, true)
        } else {
            Log.w(TAG, "State characteristic not available")
        }
        Log.d(TAG, "Observing state...")
    }

    override fun displayTextPage(text: String?) {
        val charUuid = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(charUuid)
        if (characteristic != null && text != null) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            characteristic.value = bytes
            gatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "Displaying text: $text")
        } else {
            Log.w(TAG, "Display characteristic not available or text null")
        }
    }

    override fun stopDisplaying() {
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

    private companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private const val TAG = "G1ServiceImpl"
    }
}
