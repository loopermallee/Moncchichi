package com.loopermallee.moncchichi.subtitles.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState

/**
 * Lightweight overlay HUD for G1 Glasses — shows connection status and signal strength.
 * Appears as subtle corner text within subtitles layer.
 */
class G1HudOverlay(context: Context) : FrameLayout(context) {

    private val hudText = TextView(context).apply {
        text = "Soul Tether: Initializing..."
        setTextColor(Color.CYAN)
        textSize = 12f
        gravity = Gravity.END
        setPadding(0, 8, 12, 8)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP)
    }

    private var bindJob: Job? = null

    init {
        addView(hudText)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun bind(connectionFlow: StateFlow<G1ConnectionState>, rssiFlow: StateFlow<Int?>) {
        bindJob?.cancel()
        bindJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            combine(connectionFlow, rssiFlow) { state, rssi -> state to rssi }.collectLatest { (state, rssi) ->
                val status = when (state) {
                    G1ConnectionState.CONNECTED -> "🟩 Connected"
                    G1ConnectionState.CONNECTING -> "🟨 Connecting"
                    G1ConnectionState.RECONNECTING -> "🟧 Reconnecting"
                    else -> "⬜ Disconnected"
                }
                val signal = rssi?.let {
                    when {
                        it > -60 -> "Strong"
                        it > -75 -> "Medium"
                        it > -90 -> "Weak"
                        else -> "None"
                    }
                } ?: "--"
                hudText.text = "Soul Tether: $status · $signal"
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bindJob?.cancel()
        bindJob = null
    }
}
