package io.texne.g1.hub.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ButtonDefaults
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
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus

private val ConnectedGreen = Color(0xFF0EAD69)
private val DisconnectedGray = Color(0xFF6D6D6D)
private val ErrorRed = Color(0xFFCC3A3A)
private val WarningOrange = Color(0xFFF57C00)
private val BreathOfFireAccent = Color(0xFFFFB347)
private val BreathOfFireSecondary = Color(0xFF3AAED8)

@Composable
fun GlassesScreen(
    glasses: G1ServiceCommon.Glasses,
    serviceStatus: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    onRefresh: () -> Unit,
    connect: () -> Unit,
    disconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val deviceName = glasses.name?.takeIf { it.isNotBlank() } ?: glasses.id ?: "Unknown Device"
    val status = glasses.status

    val statusLabel = when {
        serviceError -> "Service Error"
        status == G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
        status == G1ServiceCommon.GlassesStatus.CONNECTING && isLooking -> "Scanning for glasses…"
        status == G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        status == G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        status == G1ServiceCommon.GlassesStatus.ERROR -> "Connection Error"
        serviceStatus == ServiceStatus.LOOKING -> "Searching for known device…"
        else -> "Disconnected"
    }

    val statusColor = when {
        serviceError -> ErrorRed
        status == G1ServiceCommon.GlassesStatus.CONNECTED -> ConnectedGreen
        status == G1ServiceCommon.GlassesStatus.CONNECTING ||
            status == G1ServiceCommon.GlassesStatus.DISCONNECTING -> WarningOrange
        status == G1ServiceCommon.GlassesStatus.ERROR -> ErrorRed
        else -> DisconnectedGray
    }

    val buttonLabel = when {
        status == G1ServiceCommon.GlassesStatus.CONNECTED -> "Disconnect"
        status == G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        status == G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        else -> "Connect"
    }

    val hasIdentifier = glasses.id?.isNotBlank() == true
    val isActionEnabled = hasIdentifier && !serviceError && when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> false
        else -> true
    }

    val showProgress = status == G1ServiceCommon.GlassesStatus.CONNECTING ||
        status == G1ServiceCommon.GlassesStatus.DISCONNECTING || isLooking
    val onPrimaryAction = if (status == G1ServiceCommon.GlassesStatus.CONNECTED) disconnect else connect

    val primaryButtonColors = if (status == G1ServiceCommon.GlassesStatus.CONNECTED) {
        ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Color.White)
    } else {
        ButtonDefaults.buttonColors(containerColor = BreathOfFireAccent, contentColor = Color(0xFF1B1B1B))
    }
    val refreshButtonColors = ButtonDefaults.buttonColors(
        containerColor = BreathOfFireSecondary,
        contentColor = Color.White
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "G1 Glasses",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusLabel,
            color = statusColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = deviceName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        if (!hasIdentifier) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Device identifier unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onPrimaryAction,
            enabled = isActionEnabled,
            colors = primaryButtonColors
        ) {
            Text(
                text = buttonLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRefresh,
            colors = refreshButtonColors
        ) {
            Text(
                text = "Refresh",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (showProgress) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = statusColor
                )
                Text(
                    text = if (isLooking) "Scanning nearby devices…" else "Updating status…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 600.dp
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassDetailCard(
                        title = "Left Glass",
                        status = status,
                        batteryPercentage = glasses.batteryPercentage,
                        modifier = Modifier.fillMaxWidth()
                    )
                    GlassDetailCard(
                        title = "Right Glass",
                        status = status,
                        batteryPercentage = glasses.batteryPercentage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GlassDetailCard(
                        title = "Left Glass",
                        status = status,
                        batteryPercentage = glasses.batteryPercentage,
                        modifier = Modifier.weight(1f)
                    )
                    GlassDetailCard(
                        title = "Right Glass",
                        status = status,
                        batteryPercentage = glasses.batteryPercentage,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (serviceError) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "The glasses service reported an error.",
                color = ErrorRed,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun GlassDetailCard(
    title: String,
    status: G1ServiceCommon.GlassesStatus,
    batteryPercentage: Int?,
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
        batteryPercentage == null -> Icons.Outlined.BatteryUnknown to Color(0xFF3C4A64)
        batteryPercentage < 20 -> Icons.Outlined.BatteryAlert to ErrorRed
        batteryPercentage < 50 -> Icons.Outlined.BatteryChargingFull to WarningOrange
        else -> Icons.Outlined.BatteryFull to ConnectedGreen
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
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
