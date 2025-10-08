package com.loopermallee.moncchichi.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.loopermallee.moncchichi.service.protocol.G1Glasses
import com.loopermallee.moncchichi.service.protocol.IG1DisplayService

/**
 * Binder backed service that accepts teleprompter text commands from external clients
 * and updates the in-app renderer state.
 */
class G1DisplayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    // Simulated state - in a real implementation these would be wired to the actual glass device.
    private var currentId: String = ""
    private var currentName: String = "G1 Glasses"
    private var currentState: Int = G1Glasses.STATE_DISCONNECTED
    private var battery: Int = 100
    private var currentText: String = ""
    private var scrollSpeed: Float = G1Glasses.DEFAULT_SCROLL_SPEED
    private var isPaused: Boolean = false

    private val binder = G1DisplayServiceImpl()

    override fun onCreate() {
        super.onCreate()
        synchronized(stateLock) {
            currentId = "G1-${'$'}{System.currentTimeMillis()}"
            currentState = G1Glasses.STATE_CONNECTED
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.post { stopScrollingInternal(clearText = true) }
        return super.onUnbind(intent)
    }

    private inner class G1DisplayServiceImpl : IG1DisplayService.Stub() {
        override fun displayText(text: String) {
            val safeText = text.ifEmpty { "" }
            mainHandler.post {
                synchronized(stateLock) {
                    currentText = safeText
                    isPaused = false
                    currentState = if (safeText.isEmpty()) {
                        G1Glasses.STATE_CONNECTED
                    } else {
                        G1Glasses.STATE_DISPLAYING
                    }
                }
                Log.d(TAG, "displayText invoked with ${'$'}{safeText.length} characters")
                // TODO: Hook into actual UI rendering once the teleprompter view is available.
            }
        }

        override fun stopDisplay() {
            mainHandler.post {
                stopScrollingInternal(clearText = true)
                Log.d(TAG, "stopDisplay invoked")
            }
        }

        override fun pauseDisplay() {
            mainHandler.post {
                synchronized(stateLock) {
                    if (currentText.isNotEmpty()) {
                        isPaused = true
                        currentState = G1Glasses.STATE_PAUSED
                    }
                }
                Log.d(TAG, "pauseDisplay invoked")
            }
        }

        override fun resumeDisplay() {
            mainHandler.post {
                synchronized(stateLock) {
                    if (currentText.isNotEmpty()) {
                        isPaused = false
                        currentState = G1Glasses.STATE_DISPLAYING
                    }
                }
                Log.d(TAG, "resumeDisplay invoked")
            }
        }

        override fun setScrollSpeed(speed: Float) {
            val sanitized = if (speed.isNaN() || speed <= 0f) {
                G1Glasses.DEFAULT_SCROLL_SPEED
            } else {
                speed
            }
            mainHandler.post {
                synchronized(stateLock) {
                    scrollSpeed = sanitized
                }
                Log.d(TAG, "setScrollSpeed invoked with ${'$'}sanitized")
            }
        }

        override fun getGlassesInfo(): G1Glasses {
            val snapshot = synchronized(stateLock) {
                G1Glasses().apply {
                    id = currentId
                    name = currentName
                    connectionState = currentState
                    batteryPercentage = battery
                    isDisplaying = currentState == G1Glasses.STATE_DISPLAYING
                    isPaused = this@G1DisplayService.isPaused
                    scrollSpeed = this@G1DisplayService.scrollSpeed
                    currentText = this@G1DisplayService.currentText
                }
            }
            Log.d(TAG, "getGlassesInfo invoked")
            return snapshot
        }
    }

    private fun stopScrollingInternal(clearText: Boolean) {
        synchronized(stateLock) {
            isPaused = false
            currentState = G1Glasses.STATE_CONNECTED
            if (clearText) {
                currentText = ""
            }
        }
    }

    companion object {
        private const val TAG = "G1DisplayService"
    }
}
