package com.loopermallee.moncchichi.hub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.loopermallee.moncchichi.client.G1ServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedBleViewModel(app: Application) : AndroidViewModel(app) {

    private val client = G1ServiceClient(app)

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _deviceName = MutableLiveData("(none)")
    val deviceName: LiveData<String> = _deviceName

    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    private fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val updated = (_logs.value ?: emptyList()) + "[$ts] $line"
        _logs.postValue(updated.takeLast(500))
    }

    init {
        client.setListener(
            onConnected = { name ->
                _isConnected.postValue(true)
                _deviceName.postValue(name ?: "Unknown")
                log("Connected to $name")
            },
            onDisconnected = {
                _isConnected.postValue(false)
                log("Disconnected")
            },
            onMessage = { bytes ->
                log("RX: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            },
            onError = { err ->
                log("Error: $err")
            }
        )
    }

    fun startScanAndConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            log("Scanning...")
            client.startScanAndConnect(
                onDeviceFound = { name, addr -> log("Found $name ($addr)") },
                onFail = { reason -> log("Scan failed: $reason") }
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            client.disconnect()
        }
    }

    fun sendDemoPing() {
        viewModelScope.launch(Dispatchers.IO) {
            val ping = byteArrayOf(0x50, 0x49, 0x4E, 0x47)
            client.write(ping)
            log("TX: ${ping.joinToString(" ") { "%02X".format(it) }}")
        }
    }
}
