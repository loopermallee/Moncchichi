package com.loopermallee.moncchichi.core.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
        val (assistantTitle, assistantSubtitle, assistantStroke) = when (assistant.state) {
            AssistantConnState.ONLINE -> {
                val modelLabel = assistant.model?.ifBlank { null } ?: "GPT"
                Triple(
                    "Assistant • $modelLabel",
                    "Online",
                    COLOR_BORDER_DEFAULT,
                )
            }
            AssistantConnState.FALLBACK -> {
                Triple(
                    "Assistant ⚡ (Offline)",
                    assistant.reason?.takeIf { it.isNotBlank() } ?: "Waiting for connection…",
                    COLOR_BORDER_HIGHLIGHT,
                )
            }
            AssistantConnState.OFFLINE -> {
                Triple(
                    "Assistant ⚡ (Offline)",
                    assistant.reason?.takeIf { it.isNotBlank() } ?: "Setup required",
                    COLOR_BORDER_HIGHLIGHT,
                )
            }
            AssistantConnState.ERROR -> {
                Triple(
                    "Assistant ⚠ Error",
                    assistant.reason?.takeIf { it.isNotBlank() } ?: "Check API key or network",
                    COLOR_BORDER_ALERT,
                )
            }
        }
        setChip(
            assistantCard,
            assistantText,
            assistantTitle,
            assistantSubtitle,
            assistantStroke,
        )

        when (device.state) {
            DeviceConnState.CONNECTED -> {
                val name = device.deviceName?.takeIf { it.isNotBlank() } ?: "G1 Glasses"
                val parts = buildList {
                    device.batteryPct?.let { add("Glasses ${it}%") }
                    device.caseBatteryPct?.let { add("Case ${it}%") }
                    device.firmware?.takeIf { it.isNotBlank() }?.let { add("FW $it") }
                    device.signalRssi?.let { add("Signal ${it} dBm") }
                }
                val subtitle = when {
                    parts.isEmpty() -> device.deviceId?.takeIf { it.isNotBlank() }
                    else -> "🔋 ${parts.joinToString(" • ")}"
                }
                setChip(
                    deviceCard,
                    deviceText,
                    "Device • $name",
                    subtitle,
                    COLOR_BORDER_DEFAULT,
                )
            }
            DeviceConnState.DISCONNECTED -> setChip(
                deviceCard,
                deviceText,
                "Device ⚠ Not connected",
                "Start scan to pair",
                COLOR_BORDER_ALERT,
            )
        }
    }

    private fun createChipView(): MaterialCardView {
        val card = MaterialCardView(context)
        card.radius = dpToPx(10f).toFloat()
        card.cardElevation = 0f
        card.setCardBackgroundColor(ColorStateList.valueOf(COLOR_SURFACE))
        card.setStrokeColor(ColorStateList.valueOf(COLOR_BORDER_DEFAULT))
        card.strokeWidth = dpToPx(1f)
        card.useCompatPadding = false
        val text = TextView(context)
        text.setPadding(dpToPx(10f))
        text.textSize = 14f
        text.setTextColor(COLOR_TEXT_PRIMARY)
        text.setLineSpacing(0f, 1.05f)
        card.addView(text)
        return card
    }

    private fun setChip(
        card: MaterialCardView,
        text: TextView,
        title: String,
        subtitle: String?,
        strokeColor: Int,
    ) {
        card.setStrokeColor(ColorStateList.valueOf(strokeColor))
        val label = buildLabel(title, subtitle)
        text.text = label
        text.contentDescription = subtitle?.let { "$title. $it" } ?: title
    }

    private fun buildLabel(title: String, subtitle: String?): CharSequence {
        if (subtitle.isNullOrBlank()) return title
        return SpannableStringBuilder(title).apply {
            append('\n')
            val start = length
            append(subtitle)
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(COLOR_TEXT_SECONDARY), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dpToPx(dp: Float): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    companion object {
        private val COLOR_SURFACE = Color.parseColor("#1A1A1A")
        private val COLOR_BORDER_DEFAULT = Color.parseColor("#2A2A2A")
        private val COLOR_BORDER_HIGHLIGHT = Color.parseColor("#353535")
        private val COLOR_BORDER_ALERT = Color.parseColor("#3F3F3F")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_SECONDARY = Color.parseColor("#CCCCCC")
    }
}
