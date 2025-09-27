package io.texne.g1.hub.ui.glasses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.R

private val PrimaryAccent = Color(0xFF3DDCFF)
private val SecondaryAccent = Color(0xFFF4C26C)
private val OverlayStart = Color(0xCC06121F)
private val OverlayEnd = Color(0xE6000000)
private val ConnectedColor = Color(0xFF7AE2C2)
private val DisconnectedColor = Color(0xFFB8C6D1)
private val ErrorColor = Color(0xFFFF8A80)
private val WarningColor = Color(0xFFFFB74D)

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
    val scrollState = rememberScrollState()
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

    val buttonLabel = when {
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTED -> "Disconnect"
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting…"
        primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting…"
        else -> "Reconnect"
    }

    val showProgress = isLooking ||
        primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTING ||
        primaryStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING

    val isActionEnabled = !serviceError && when (primaryStatus) {
        G1ServiceCommon.GlassesStatus.CONNECTING,
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> false
        else -> true
    }

    Box(modifier = modifier) {
        Image(
            painter = painterResource(id = R.drawable.bof4_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(OverlayStart, OverlayEnd)))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Guardian Sync",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Breath of Fire IV Link",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SecondaryAccent,
                    fontWeight = FontWeight.Medium
                )
            }

            ConnectionStatusPill(text = statusLabel, color = statusColor)

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val onPrimaryAction = if (primaryStatus == G1ServiceCommon.GlassesStatus.CONNECTED) {
                        disconnect
                    } else {
                        connect
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onPrimaryAction,
                        enabled = isActionEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryAccent,
                            contentColor = Color(0xFF042532)
                        )
                    ) {
                        Text(buttonLabel, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        modifier = Modifier.height(48.dp),
                        onClick = refresh,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SecondaryAccent
                        ),
                        border = BorderStroke(1.dp, SecondaryAccent)
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
                            color = PrimaryAccent,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = if (isLooking) "Scanning nearby devices…" else "Updating connection…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }

                if (serviceError) {
                    ServiceErrorBanner()
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

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
private fun ConnectionStatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.85f),
        contentColor = Color.Black,
        shape = RoundedCornerShape(50)
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
        color = ErrorColor.copy(alpha = 0.9f),
        contentColor = Color(0xFF3B0A06),
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
        colors = CardDefaults.cardColors(containerColor = Color(0xAA0B1D2A)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
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
                color = SecondaryAccent,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Connection Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
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
                    color = Color.White.copy(alpha = 0.7f)
                )
                LinearProgressIndicator(
                    progress = { batteryProgress },
                    color = PrimaryAccent,
                    trackColor = PrimaryAccent.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = batteryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "No glasses detected",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = helperText,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Start
        )
    }
}
