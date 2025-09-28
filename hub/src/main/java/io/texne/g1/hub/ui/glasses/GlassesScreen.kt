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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Breath of Fire IV",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "G1 Hub Device Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Discover, pair, and manage your G1 glasses with a single tap.",
                style = MaterialTheme.typography.bodyMedium
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
        color = Bof4Palette.Steel.copy(alpha = 0.85f),
        contentColor = Bof4Palette.Mist,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Surface(
                color = statusColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
            ) {
                Text(
                    text = statusLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    color = statusColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.2f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPrimaryAction,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = statusColor.copy(alpha = 0.85f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(buttonLabel)
                }

                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !serviceError,
                    border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.55f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
            }

            if (isLooking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = statusColor,
                        strokeWidth = 3.dp
                    )

                    Text(
                        text = "Scanning for nearby G1 glasses…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassesCard(
    title: String,
    glasses: G1ServiceCommon.Glasses?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Bof4Palette.Steel.copy(alpha = 0.7f),
            contentColor = Bof4Palette.Mist
        ),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (glasses == null) {
                Text(
                    text = "No glasses detected",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = glasses.name ?: "Unnamed glasses",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Status: ${glasses.status.name}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Battery: ${glasses.batteryPercentage ?: 0}%",
                    style = MaterialTheme.typography.bodyMedium
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
    val description = when {
        serviceStatus == ServiceStatus.ERROR ->
            "Unable to start discovery. Check Bluetooth and location permissions."

        isLooking ->
            "Scanning for glasses… stay close to your device."

        else ->
            "Tap refresh to scan for available G1 glasses nearby."
    }

    Surface(
        color = Bof4Palette.Steel.copy(alpha = 0.8f),
        contentColor = Bof4Palette.Mist,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Bof4Palette.Sky.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No glasses detected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
