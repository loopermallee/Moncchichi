package io.texne.g1.hub.ui.glasses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus

private object Bof4Palette {
    val Midnight = Color(0xFF081726)
    val Steel = Color(0xFF1F3D5B)
    val Mist = Color(0xFFE9E2CE)
    val Sand = Color(0xFFCDB894)
    val Ember = Color(0xFFE39A3B)
    val Sky = Color(0xFF6CA6D9)
    val Verdant = Color(0xFF5D9479)
    val Coral = Color(0xFFD9644C)
    val Warning = Color(0xFFF2C74E)
}

private val ConnectedColor = Bof4Palette.Verdant
private val DisconnectedColor = Bof4Palette.Sand
private val ErrorColor = Bof4Palette.Coral
private val WarningColor = Bof4Palette.Warning

@Composable
fun GlassesScreen(
    glasses: List<G1ServiceCommon.Glasses>,
    serviceStatus: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    connect: () -> Unit,
    disconnect: () -> Unit,
    refresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryGlasses = glasses.firstOrNull()
    val primaryStatus = primaryGlasses?.status

    val statusLabel = when {
        serviceError -> "Service Error"
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
        primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
        isLooking -> "Scanning for glasses"
        primaryStatus == G1ServiceCommon.GlassesStatus.ERROR -> "Connection Error"
        primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
        else -> "Ready to connect"
    }

    val statusColor = when {
        serviceError -> ErrorColor
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTED -> ConnectedColor
        primaryStatus == G1ServiceCommon.GlassesStatus.ERROR -> ErrorColor
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTING ||
            primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING ||
            isLooking -> WarningColor
        else -> DisconnectedColor
    }

    val buttonLabel = when (primaryStatus) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> "Disconnect"
        G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        else -> "Connect"
    }

    val showProgress = isLooking ||
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTING ||
        primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING

    val isActionEnabled = !serviceError && when (primaryStatus) {
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> false
        else -> true
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Bof4Palette.Midnight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            HeroHeader()

            StatusPanel(
                statusLabel = statusLabel,
                statusColor = statusColor,
                buttonLabel = buttonLabel,
                onPrimaryAction = if (primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTED) {
                    disconnect
                } else {
                    connect
                },
                onRefresh = refresh,
                enabled = isActionEnabled,
                showProgress = showProgress,
                isLooking = isLooking,
                serviceError = serviceError
            )

            val cardEntries = listOf(
                "Left Glass" to glasses.getOrNull(0),
                "Right Glass" to glasses.getOrNull(1)
            )

            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                cardEntries.forEach { (title, glass) ->
                    GlassesCard(title = title, glasses = glass)
                }
            }

            if (glasses.isEmpty()) {
                NoGlassesMessage(serviceStatus = serviceStatus, isLooking = isLooking)
            }
        }
    }
}

@Composable
private fun HeroHeader() {
    Surface(
        color = Bof4Palette.Steel.copy(alpha = 0.85f),
        contentColor = Bof4Palette.Mist,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Guardian Sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Breath of Fire IV link",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Bof4Palette.Ember
            )
        }
    }
}

@Composable
private fun StatusPanel(
    statusLabel: String,
    statusColor: Color,
    buttonLabel: String,
    onPrimaryAction: () -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean,
    showProgress: Boolean,
    isLooking: Boolean,
    serviceError: Boolean
) {
    Surface(
        color = Bof4Palette.Steel.copy(alpha = 0.9f),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ConnectionStatusPill(text = statusLabel, color = statusColor)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onPrimaryAction,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Bof4Palette.Sky,
                        contentColor = Bof4Palette.Midnight
                    )
                ) {
                    Text(buttonLabel, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    modifier = Modifier.height(48.dp),
                    onClick = onRefresh,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Bof4Palette.Ember
                    ),
                    border = BorderStroke(1.dp, Bof4Palette.Ember)
                ) {
                    Text("Refresh", fontWeight = FontWeight.Medium)
                }
            }

            if (showProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Bof4Palette.Sky,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = if (isLooking) {
                            "Scanning nearby devices…"
                        } else {
                            "Updating connection…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Bof4Palette.Mist
                    )
                }
            }

            if (serviceError) {
                ServiceErrorBanner()
            }
        }
    }
}

@Composable
private fun ConnectionStatusPill(text: String, color: Color) {
    Surface(
        color = color,
        contentColor = Bof4Palette.Midnight,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Bof4Palette.Midnight.copy(alpha = 0.3f))
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ServiceErrorBanner() {
    Surface(
        color = ErrorColor,
        contentColor = Bof4Palette.Mist,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            text = "The glasses service reported an error. Please toggle Bluetooth and try again.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GlassesCard(
    title: String,
    glasses: G1ServiceCommon.Glasses?,
    modifier: Modifier = Modifier
) {
    val deviceName = glasses?.name?.takeIf { it.isNotBlank() } ?: "Waiting for device…"
    val batteryPercentage = glasses?.batteryPercentage?.takeIf { it in 0..100 }
    val batteryText = batteryPercentage?.let { "$it%" } ?: "--"
    val batteryProgress = batteryPercentage?.coerceIn(0, 100)?.div(100f) ?: 0f
    val status = glasses?.status

    val statusLabel = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
        G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
        G1ServiceCommon.GlassesStatus.ERROR -> "Error"
        G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
        G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Initializing"
        null -> "Not detected"
    }

    val statusColor = when (status) {
        G1ServiceCommon.GlassesStatus.CONNECTED -> ConnectedColor
        G1ServiceCommon.GlassesStatus.ERROR -> ErrorColor
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> WarningColor
        else -> DisconnectedColor
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Bof4Palette.Steel.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Bof4Palette.Sky,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = Bof4Palette.Mist,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Connection Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = Bof4Palette.Mist.copy(alpha = 0.7f)
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Battery",
                    style = MaterialTheme.typography.labelMedium,
                    color = Bof4Palette.Mist.copy(alpha = 0.7f)
                )
                LinearProgressIndicator(
                    progress = { batteryProgress },
                    color = Bof4Palette.Sky,
                    trackColor = Bof4Palette.Sky.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = batteryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Bof4Palette.Mist,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun NoGlassesMessage(
    serviceStatus: ServiceStatus,
    isLooking: Boolean
) {
    val helperText = when {
        isLooking -> "Keep your glasses nearby while we scan."
        serviceStatus == ServiceStatus.ERROR -> "Toggle Bluetooth or power-cycle your glasses, then refresh."
        else -> "Turn on your glasses and tap Refresh to start pairing."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Bof4Palette.Steel.copy(alpha = 0.85f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "No glasses detected",
                style = MaterialTheme.typography.titleMedium,
                color = Bof4Palette.Mist,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = helperText,
                style = MaterialTheme.typography.bodyMedium,
                color = Bof4Palette.Mist.copy(alpha = 0.75f),
                textAlign = TextAlign.Start
            )
        }
    }
}
