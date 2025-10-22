package com.loopermallee.moncchichi.core.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.AssistantConnState
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnState

class StatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val assistantCard: MaterialCardView
    private val assistantText: TextView
    private val deviceCard: MaterialCardView
    private val deviceText: TextView

    init {
        orientation = VERTICAL
        assistantCard = createChipView()
        assistantText = assistantCard.getChildAt(0) as TextView
        addView(assistantCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val spacer = View(context)
        spacer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(6f))
        addView(spacer)

        deviceCard = createChipView()
        deviceText = deviceCard.getChildAt(0) as TextView
        addView(deviceCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(assistant: AssistantConnInfo, device: DeviceConnInfo) {
        val (assistantLabel, assistantColor, assistantEmoji) = when (assistant.state) {
            AssistantConnState.ONLINE -> Triple(
                "Online – ${assistant.model ?: "GPT"} connected",
                ONLINE_COLOR,
                "🌐"
            )
            AssistantConnState.OFFLINE -> Triple(
                "Offline Mode – local fallback active",
                OFFLINE_COLOR,
                "⚡"
            )
            AssistantConnState.ERROR -> Triple(
                "Assistant error – ${assistant.reason ?: "check API or network"}",
                ERROR_COLOR,
                "❌"
            )
        }
        setChipState(assistantCard, assistantText, assistantEmoji, assistantLabel, assistantColor)

        val (deviceLabel, deviceColor, deviceEmoji) = when (device.state) {
            DeviceConnState.CONNECTED -> Triple(
                "Connected – ${device.deviceName ?: "Moncchichi G1"} | RSSI ${device.rssi ?: "?"} | Battery ${device.batteryPct ?: "?"}% | FW ${device.firmware ?: "?"}",
                DEVICE_CONNECTED_COLOR,
                "🔗"
            )
            DeviceConnState.DISCONNECTED -> Triple(
                "No device connected",
                ERROR_COLOR,
                "❌"
            )
        }
        setChipState(deviceCard, deviceText, deviceEmoji, deviceLabel, deviceColor)
    }

    private fun createChipView(): MaterialCardView {
        val card = MaterialCardView(context)
        card.radius = dpToPx(10f).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = 0
        val text = TextView(context)
        text.setPadding(dpToPx(10f))
        text.textSize = 14f
        card.addView(text)
        return card
    }

    private fun setChipState(
        card: MaterialCardView,
        text: TextView,
        emoji: String,
        label: String,
        color: Int
    ) {
        card.setCardBackgroundColor(ColorStateList.valueOf(applyAlpha(color, 0.1f)))
        text.setTextColor(color)
        text.text = "$emoji $label"
        text.contentDescription = label
    }

    private fun dpToPx(dp: Float): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt()
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.argb(a, r, g, b)
    }

    companion object {
        private val ONLINE_COLOR = Color.parseColor("#4CAF50")
        private val OFFLINE_COLOR = Color.parseColor("#FFC107")
        private val ERROR_COLOR = Color.parseColor("#F44336")
        private val DEVICE_CONNECTED_COLOR = Color.parseColor("#2196F3")
    }
}
