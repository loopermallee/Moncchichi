package io.texne.g1.hub.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatteryUnknown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.texne.g1.basis.client.G1ServiceCommon

private val ConnectedGreen = Color(0xFF047A00)
private val DisconnectedGray = Color(0xFF6D6D6D)
private val ErrorRed = Color(0xFFB3261E)
private val WarningOrange = Color(0xFFF57C00)

@Composable
fun GlassesScreen(
    glasses: G1ServiceCommon.Glasses,
    connect: () -> Unit,
    disconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val deviceName = glasses.name?.takeIf { it.isNotBlank() } ?: "Unnamed glasses"
    val status = glasses.status

    val statusLabel = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> "Glasses Online"
        G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        G1ServiceCommon.GlassesStatus.ERROR -> "Connection Error"
        G1ServiceCommon.GlassesStatus.DISCONNECTED,
        G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "No Glasses Connected"
    }

    val statusColor = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> ConnectedGreen
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> WarningOrange
        G1ServiceCommon.GlassesStatus.ERROR -> ErrorRed
        G1ServiceCommon.GlassesStatus.DISCONNECTED,
        G1ServiceCommon.GlassesStatus.UNINITIALIZED -> DisconnectedGray
    }

    val buttonLabel = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> "Disconnect Glasses"
        G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        G1ServiceCommon.GlassesStatus.DISCONNECTED,
        G1ServiceCommon.GlassesStatus.UNINITIALIZED,
        G1ServiceCommon.GlassesStatus.ERROR -> "Connect Glasses"
    }

    val isActionEnabled = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> false
        else -> true
    }

    val showProgress = status == G1ServiceCommon.GlassesStatus.CONNECTING ||
        status == G1ServiceCommon.GlassesStatus.DISCONNECTING
    val onPrimaryAction = if (status == G1ServiceCommon.GlassesStatus.CONNECTED) disconnect else connect

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "G1 Glasses",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = statusLabel,
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = deviceName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onPrimaryAction,
                enabled = isActionEnabled
            ) {
                Text(buttonLabel)
            }
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = statusColor,
                    strokeWidth = 2.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        GlassDetailCard(
            title = "Left Glass",
            status = status,
            batteryPercentage = glasses.batteryPercentage,
            firmwareVersion = glasses.id,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        GlassDetailCard(
            title = "Right Glass",
            status = status,
            batteryPercentage = glasses.batteryPercentage,
            firmwareVersion = glasses.id,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GlassDetailCard(
    title: String,
    status: G1ServiceCommon.GlassesStatus,
    batteryPercentage: Int?,
    firmwareVersion: String?,
    modifier: Modifier = Modifier
) {
    val (badgeColor, badgeText) = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> ConnectedGreen to "Online"
        G1ServiceCommon.GlassesStatus.CONNECTING -> WarningOrange to "Connecting"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> WarningOrange to "Disconnecting"
        G1ServiceCommon.GlassesStatus.ERROR -> ErrorRed to "Error"
        G1ServiceCommon.GlassesStatus.DISCONNECTED,
        G1ServiceCommon.GlassesStatus.UNINITIALIZED -> DisconnectedGray to "Disconnected"
    }

    val batteryLabel = batteryPercentage?.let { "$it%" } ?: "Unknown"
    val (batteryIcon, batteryTint) = when {
        batteryPercentage == null -> Icons.Outlined.BatteryUnknown to MaterialTheme.colorScheme.onSurfaceVariant
        batteryPercentage < 20 -> Icons.Outlined.BatteryAlert to ErrorRed
        batteryPercentage < 50 -> Icons.Outlined.BatteryChargingFull to WarningOrange
        else -> Icons.Outlined.BatteryFull to ConnectedGreen
    }

    val firmwareLabel = firmwareVersion?.takeIf { it.isNotBlank() }
        ?.let { "Firmware version $it" }
        ?: "Firmware version unavailable"

    Card(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                StatusBadge(text = badgeText, backgroundColor = badgeColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = batteryIcon,
                    contentDescription = "Battery",
                    tint = batteryTint
                )
                Text(
                    text = "Battery $batteryLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = firmwareLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = backgroundColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
