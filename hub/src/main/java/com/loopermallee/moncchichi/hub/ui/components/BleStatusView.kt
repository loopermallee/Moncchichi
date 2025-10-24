package com.loopermallee.moncchichi.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Monochrome HUD-style widget that shows BLE connectivity for left/right lenses.
 */
@Composable
fun BleStatusView(
    left: LensUiState,
    right: LensUiState,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
) {
    Column(
        modifier = modifier
            .background(MonochromeSurface, RoundedCornerShape(20.dp))
            .border(1.dp, MonochromeBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = "BLE Link",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )

        LensRow(title = "Left", state = left)
        LensRow(title = "Right", state = right)
    }
}

@Composable
private fun LensRow(title: String, state: LensUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = state.statusColor)
            Spacer(modifier = Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Text(
                    text = state.statusLabel,
                    color = state.statusDetailColor,
                    fontSize = 11.sp,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.rssi?.let { value ->
                Text(
                    text = "${value} dBm",
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
            state.lastAckLabel?.let { label ->
                Text(
                    text = label,
                    color = SecondaryText,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Spacer(
        modifier = Modifier
            .size(14.dp)
            .background(color, CircleShape)
    )
}

/**
 * Snapshot of a lens connection rendered by [BleStatusView].
 */
data class LensUiState(
    val isConnected: Boolean,
    val degraded: Boolean,
    val rssi: Int? = null,
    val lastAckTimestamp: Long? = null,
) {
    val statusLabel: String
        get() = when {
            isConnected && degraded -> "Connected • Retrying"
            isConnected -> "Connected"
            degraded -> "Link unstable"
            else -> "Disconnected"
        }

    val statusColor: Color
        get() = when {
            isConnected && !degraded -> Success
            isConnected && degraded -> Warning
            degraded -> Warning
            else -> Error
        }

    val statusDetailColor: Color
        get() = when {
            degraded -> Warning
            isConnected -> SecondaryText
            else -> SecondaryText
        }

    val lastAckLabel: String?
        get() = lastAckTimestamp?.let { timestamp ->
            val delta = System.currentTimeMillis() - timestamp
            val seconds = TimeUnit.MILLISECONDS.toSeconds(delta).coerceAtLeast(0)
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val formatted = formatter.format(Date(timestamp))
            "Ack ${seconds}s ago • $formatted"
        }
}

private val MonochromeSurface = Color(0xFF1A1A1A)
private val MonochromeBorder = Color(0xFF2A2A2A)
private val SecondaryText = Color(0xFFCCCCCC)
private val Success = Color(0xFF4CAF50)
private val Warning = Color(0xFFFFC107)
private val Error = Color(0xFFF44336)
