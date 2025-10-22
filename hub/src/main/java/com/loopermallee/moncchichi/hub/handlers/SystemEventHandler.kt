package com.loopermallee.moncchichi.hub.handlers

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableSharedFlow

class SystemEventHandler(private val ctx: Context) {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 20)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val state = intent.getBooleanExtra("state", false)
                    events.tryEmit("[SYS] ✈ Airplane mode ${if (state) "ON" else "OFF"}")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> events.tryEmit("[SYS] 🔴 Bluetooth OFF")
                        BluetoothAdapter.STATE_ON -> events.tryEmit("[SYS] 🟢 Bluetooth ON")
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ctx.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { ctx.unregisterReceiver(receiver) }
    }
}
