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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.service.G1DisplayService

class TestActivity : Activity() {

    private var serviceBound = false
    private var statusView: TextView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            statusView?.text = "✅ Connected to G1DisplayService"
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            statusView?.text = "⚠️ Service disconnected"
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            statusView = TextView(this@TestActivity).apply {
                text = "🚀 Starting Moncchichi..."
                textSize = 20f
                setPadding(30, 200, 30, 30)
            }
            addView(statusView)
        }
        setContentView(layout)
        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startDisplayServiceSafely()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startDisplayServiceSafely()
        } else {
            statusView?.text = "❌ Missing permissions — exiting"
            finish()
        }
    }

    private fun startDisplayServiceSafely() {
        statusView?.text = "🔄 Starting G1DisplayService..."
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

    override fun onDestroy() {
        if (serviceBound) unbindService(connection)
        super.onDestroy()
    }
}
