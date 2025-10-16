package com.loopermallee.moncchichi

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import com.loopermallee.moncchichi.service.G1DisplayService

private val BT_PERMS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private const val REQUEST_BT_PERMS = 1001

class TestActivity : Activity() {

    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "✅ Minimal build started successfully"
        setContentView(tv)

        requestPostNotificationsIfNeeded()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasBluetoothPermissions()) {
                bindDisplayService()
            } else {
                requestPermissions(BT_PERMS, REQUEST_BT_PERMS)
            }
        } else {
            bindDisplayService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            bindDisplayService()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(connection)
        }
        super.onDestroy()
    }

    private fun hasBluetoothPermissions(): Boolean =
        BT_PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun bindDisplayService() {
        val intent = Intent(this, G1DisplayService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        if (!serviceBound) {
            serviceBound = bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }
}
