package com.loopermallee.moncchichi.core.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class BluetoothStateReceiver(
    private val onOff: () -> Unit,
    private val onOn: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (BluetoothAdapter.ACTION_STATE_CHANGED != intent.action) return
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_OFF -> onOff.invoke()
            BluetoothAdapter.STATE_ON -> onOn.invoke()
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
}
