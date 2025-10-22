package com.loopermallee.moncchichi.core.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
        when (assistant.state) {
            AssistantConnState.ONLINE -> {
                val modelLabel = assistant.model ?: "GPT"
                setChip(
                    assistantCard,
                    assistantText,
                    label = "🟢 Online • $modelLabel",
                    textColor = COLOR_ASSISTANT_ON,
                    backgroundColor = COLOR_ASSISTANT_ON_BG
                )
            }
            AssistantConnState.OFFLINE -> {
                val message = assistant.reason?.takeIf { it.isNotBlank() }?.let { "⚡ Offline • $it" }
                    ?: "⚡ Offline Mode"
                setChip(
                    assistantCard,
                    assistantText,
                    label = message,
                    textColor = COLOR_ASSISTANT_OFF,
                    backgroundColor = COLOR_ASSISTANT_OFF_BG
                )
            }
            AssistantConnState.ERROR -> {
                val reason = assistant.reason ?: "check API key or network"
                setChip(
                    assistantCard,
                    assistantText,
                    label = "❌ Error – $reason",
                    textColor = COLOR_ASSISTANT_ERROR,
                    backgroundColor = COLOR_ASSISTANT_ERROR_BG
                )
            }
        }

        when (device.state) {
            DeviceConnState.CONNECTED -> {
                val header = buildString {
                    append("🟢 Connected")
                    device.deviceName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
                    device.deviceId?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
                    device.firmware?.takeIf { it.isNotBlank() }?.let { append(" FW ").append(it) }
                }.ifBlank { "🟢 Connected" }
                val footer = buildList {
                    device.batteryPct?.let { add("Glasses ${it}%") }
                    device.caseBatteryPct?.let { add("Case ${it}%") }
                }.takeIf { it.isNotEmpty() }?.joinToString(separator = " • ")?.let { "🔋 $it" }
                val label: CharSequence = if (footer.isNullOrBlank()) {
                    header
                } else {
                    SpannableStringBuilder(header).apply {
                        append('\n')
                        val start = length
                        append(footer)
                        setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                setChip(deviceCard, deviceText, label, COLOR_DEVICE_ON, COLOR_DEVICE_ON_BG)
            }
            DeviceConnState.DISCONNECTED -> setChip(
                deviceCard,
                deviceText,
                label = "🚫 No device connected",
                textColor = COLOR_DEVICE_OFF,
                backgroundColor = COLOR_DEVICE_OFF_BG
            )
        }
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

    private fun setChip(
        card: MaterialCardView,
        text: TextView,
        label: CharSequence,
        textColor: Int,
        backgroundColor: Int
    ) {
        card.setCardBackgroundColor(ColorStateList.valueOf(backgroundColor))
        text.setTextColor(textColor)
        text.text = label
        text.contentDescription = label
    }

    private fun dpToPx(dp: Float): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    companion object {
        private val COLOR_ASSISTANT_ON = Color.parseColor("#66FFB2")
        private val COLOR_ASSISTANT_ON_BG = Color.parseColor("#14271C")
        private val COLOR_ASSISTANT_OFF = Color.parseColor("#FFB84D")
        private val COLOR_ASSISTANT_OFF_BG = Color.parseColor("#2A2315")
        private val COLOR_ASSISTANT_ERROR = Color.parseColor("#FF6B6B")
        private val COLOR_ASSISTANT_ERROR_BG = Color.parseColor("#2A1E1E")

        private val COLOR_DEVICE_ON = Color.parseColor("#A691F2")
        private val COLOR_DEVICE_ON_BG = Color.parseColor("#272033")
        private val COLOR_DEVICE_OFF = Color.parseColor("#FF8A65")
        private val COLOR_DEVICE_OFF_BG = Color.parseColor("#2E1F1F")
    }
}
