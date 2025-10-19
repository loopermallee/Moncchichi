package com.teleprompter

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.loopermallee.moncchichi.service.G1DisplayService
import io.texne.g1.basis.service.protocol.IG1DisplayService

class MainActivity : Activity() {
    private var displayService: IG1DisplayService? = null
    private var isServiceBound: Boolean = false

    private val displayServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            displayService = IG1DisplayService.Stub.asInterface(binder)
            isServiceBound = true
            Log.d(TAG, "Display service connected")
            displayService?.apply {
                setScrollSpeed(1.0f)
                displayText("Hello world")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            displayService = null
            isServiceBound = false
            Log.d(TAG, "Display service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "G1 Native Display"
            gravity = Gravity.CENTER
            textSize = 20f
        }
        setContentView(textView)

        Intent(this, G1DisplayService::class.java).also {
            bindService(it, displayServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            try {
                displayService?.stopDisplay()
                unbindService(displayServiceConnection)
            } catch (throwable: IllegalArgumentException) {
                Log.w(TAG, "Attempted to unbind display service that was not bound", throwable)
            }
            isServiceBound = false
        }
        displayService = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
