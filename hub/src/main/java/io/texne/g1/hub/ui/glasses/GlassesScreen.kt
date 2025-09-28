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
import io.texne.g1.hub.ui.theme.Bof4Coral
import io.texne.g1.hub.ui.theme.Bof4Midnight
import io.texne.g1.hub.ui.theme.Bof4Mist
import io.texne.g1.hub.ui.theme.Bof4Sand
import io.texne.g1.hub.ui.theme.Bof4Sky
import io.texne.g1.hub.ui.theme.Bof4Steel
import io.texne.g1.hub.ui.theme.Bof4Verdant
import io.texne.g1.hub.ui.theme.Bof4Warning

private val ConnectedColor = Bof4Verdant
private val DisconnectedColor = Bof4Sand
private val ErrorColor = Bof4Coral
private val WarningColor = Bof4Warning

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
    val connectionStatuses = glasses.map { it.status }
    val connectedCount = connectionStatuses.count { it == G1ServiceCommon.GlassesStatus.CONNECTED }
    val hasConnecting = connectionStatuses.any { it == G1ServiceCommon.GlassesStatus.CONNECTING }
    val hasDisconnecting = connectionStatuses.any { it == G1ServiceCommon.GlassesStatus.DISCONNECTING }
    val hasError = serviceError || connectionStatuses.any { it == G1ServiceCommon.GlassesStatus.ERROR }

    val statusLabel = when {
        serviceError -> "Service Error"
        hasDisconnecting -> "Disconnecting"
        hasConnecting -> "Connecting"
        isLooking -> "Scanning for glasses"
        connectedCount > 0 && connectedCount == glasses.size -> "All glasses connected"
        connectedCount > 0 -> "$connectedCount of ${glasses.size} connected"
        glasses.isNotEmpty() -> "Glasses discovered"
        else -> "Ready to connect"
    }

    val statusColor = when {
        hasError -> ErrorColor
        connectedCount > 0 && connectedCount == glasses.size -> ConnectedColor
        connectedCount > 0 -> Bof4Sky
        hasConnecting || hasDisconnecting || isLooking -> WarningColor
        else -> DisconnectedColor
    }

    val shouldDisconnect = connectedCount > 0 || hasDisconnecting
    val buttonLabel = when {
        hasDisconnecting -> "Disconnecting…"
        shouldDisconnect -> "Disconnect"
        hasConnecting -> "Connecting…"
        else -> "Connect"
    }

    val showProgress = isLooking || hasConnecting || hasDisconnecting

    val isActionEnabled = !serviceError && !hasConnecting && !hasDisconnecting

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Bof4Midnight)
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
                onPrimaryAction = if (shouldDisconnect) disconnect else connect,
                onRefresh = refresh,
                enabled = isActionEnabled,
                showProgress = showProgress,
                isLooking = isLooking,
                serviceError = serviceError
            )

            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                glasses.forEach { glass ->
                    GlassesCard(glasses = glass)
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
        color = Bof4Steel.copy(alpha = 0.85f),
        contentColor = Bof4Mist,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.55f))
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
        color = Bof4Steel.copy(alpha = 0.85f),
        contentColor = Bof4Mist,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.55f))
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
                    border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.55f)),
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
    glasses: G1ServiceCommon.Glasses
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Bof4Steel.copy(alpha = 0.7f),
            contentColor = Bof4Mist
        ),
        border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val displayName = glasses.name?.takeIf { it.isNotBlank() }
                ?: glasses.id?.takeIf { it.isNotBlank() }
                ?: "Unknown Glasses"

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            val (statusLabel, statusColor) = when (glasses.status) {
                G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected" to ConnectedColor
                G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting" to WarningColor
                G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting" to WarningColor
                G1ServiceCommon.GlassesStatus.ERROR -> "Connection Error" to ErrorColor
                G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected" to DisconnectedColor
                G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Initializing" to DisconnectedColor
            }

            Surface(
                color = statusColor.copy(alpha = 0.12f),
                contentColor = statusColor,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
            ) {
                Text(
                    text = statusLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val batteryLabel = glasses.batteryPercentage?.takeIf { it >= 0 }?.let { "$it%" }
                ?: "Unknown"
            Text(
                text = "Battery: $batteryLabel",
                style = MaterialTheme.typography.bodyMedium
            )

            val safeId = glasses.id?.takeIf { it.isNotBlank() } ?: "Unknown ID"
            Text(
                text = "ID: $safeId",
                style = MaterialTheme.typography.bodySmall,
                color = Bof4Mist.copy(alpha = 0.8f)
            )
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
        color = Bof4Steel.copy(alpha = 0.8f),
        contentColor = Bof4Mist,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.45f))
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
