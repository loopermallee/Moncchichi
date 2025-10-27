package com.loopermallee.moncchichi.hub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.hub.ui.theme.StatusConnected
import com.loopermallee.moncchichi.hub.ui.theme.StatusError
import com.loopermallee.moncchichi.hub.ui.theme.StatusWarning
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            Text(
                text = "BLE Link",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            LensRow(title = "Left", state = left)
            LensRow(title = "Right", state = right)
        }
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
            StatusSwatch(color = state.statusColor)
            Spacer(modifier = Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = state.statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.rssi?.let { value ->
                Text(
                    text = "${value} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            state.lastAckLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
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
            isConnected && degraded -> "Connected \u2022 Retrying"
            isConnected -> "Connected"
            degraded -> "Link unstable"
            else -> "Disconnected"
        }

    val statusColor: Color
        get() = when {
            isConnected && !degraded -> StatusConnected
            isConnected && degraded -> StatusWarning
            degraded -> StatusWarning
            else -> StatusError
        }

    val lastAckLabel: String?
        get() = lastAckTimestamp?.let { timestamp ->
            val delta = System.currentTimeMillis() - timestamp
            val seconds = TimeUnit.MILLISECONDS.toSeconds(delta).coerceAtLeast(0)
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val formatted = formatter.format(Date(timestamp))
            "Ack ${seconds}s ago \u2022 $formatted"
        }
}
