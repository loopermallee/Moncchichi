package com.loopermallee.moncchichi.hub.ui.glasses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.GlassesStatus
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Coral
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Midnight
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Mist
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Sand
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Sky
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Steel
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Verdant
import com.loopermallee.moncchichi.hub.ui.theme.Bof4Warning
import java.util.Locale

private val ConnectedColor = Bof4Verdant
private val DisconnectedColor = Bof4Sand
private val ErrorColor = Bof4Coral
private val WarningColor = Bof4Warning

private val GenericNameRegex = Regex("^(left|right)([-_][a-z0-9]+)?$", RegexOption.IGNORE_CASE)

internal fun G1ServiceCommon.Glasses.displayName(): String {
    val id = id?.takeIf { it.isNotBlank() }
    val rawName = name?.takeIf { it.isNotBlank() }
    val lowerId = id?.lowercase(Locale.US)
    val lowerName = rawName?.lowercase(Locale.US)
    val isGenericName = lowerName != null && (lowerId == lowerName || GenericNameRegex.matches(lowerName))
    val sideLabel = when {
        lowerName?.startsWith("left") == true || lowerId?.startsWith("left") == true -> "Left Glasses"
        lowerName?.startsWith("right") == true || lowerId?.startsWith("right") == true -> "Right Glasses"
        else -> null
    }
    return when {
        rawName != null && !isGenericName -> rawName
        sideLabel != null -> sideLabel
        id != null -> id
        else -> "Unknown Glasses"
    }
}

internal fun G1ServiceCommon.Glasses.statusText(): String = when (status) {
    GlassesStatus.CONNECTED -> "Connected"
    GlassesStatus.CONNECTING -> "Connecting"
    GlassesStatus.DISCONNECTING -> "Disconnecting"
    GlassesStatus.ERROR -> "Connection Error"
    GlassesStatus.DISCONNECTED -> "Disconnected"
    GlassesStatus.UNINITIALIZED -> "Initializing"
}

internal fun G1ServiceCommon.Glasses.statusColor(): Color = when (status) {
    GlassesStatus.CONNECTED -> ConnectedColor
    GlassesStatus.CONNECTING, GlassesStatus.UNINITIALIZED, GlassesStatus.DISCONNECTING -> WarningColor
    GlassesStatus.DISCONNECTED -> DisconnectedColor
    GlassesStatus.ERROR -> ErrorColor
}

internal fun G1ServiceCommon.Glasses.batteryLabel(): String =
    batteryPercentage?.takeIf { it >= 0 }?.let { "$it%" } ?: "Unknown"

internal fun G1ServiceCommon.Glasses.firmwareLabel(): String =
    firmwareVersion?.takeIf { it.isNotBlank() } ?: "Unknown"

@Composable
fun GlassesScreen(
    glasses: List<G1ServiceCommon.Glasses>,
    serviceStatus: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    connect: (String, String?) -> Unit,
    disconnect: (String) -> Unit,
    refresh: () -> Unit,
    testMessages: Map<String, String>,
    onTestMessageChange: (String, String) -> Unit,
    onSendTestMessage: (String, String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedGlasses = glasses.sortedBy { it.displayName() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Bof4Midnight)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { HeroHeader() }

            item {
                StatusPanel(
                    glasses = sortedGlasses,
                    status = serviceStatus,
                    isLooking = isLooking,
                    serviceError = serviceError,
                    onRefresh = refresh,
                )
            }

            if (sortedGlasses.isNotEmpty()) {
                item {
                    Text(
                        text = "Available Glasses",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Bof4Mist,
                    )
                }

                items(sortedGlasses, key = { it.id ?: it.name ?: it.hashCode() }) { glass ->
                    val glassesId = glass.id
                    val testMessage = glassesId?.let { id -> testMessages[id] } ?: ""
                    val messageChanged: (String) -> Unit = { newValue ->
                        if (glassesId != null) {
                            onTestMessageChange(glassesId, newValue)
                        }
                    }
                    val sendMessage = glassesId?.let { id ->
                        { message: String, onResult: (Boolean) -> Unit ->
                            onSendTestMessage(id, message, onResult)
                        }
                    }
                    GlassesCard(
                        glasses = glass,
                        onConnect = { id, name -> connect(id, name) },
                        onDisconnect = { id -> disconnect(id) },
                        testMessage = testMessage,
                        onTestMessageChange = messageChanged,
                        onSendTestMessage = sendMessage,
                    )
                }
            } else {
                item {
                    NoGlassesMessage(serviceStatus = serviceStatus, isLooking = isLooking)
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
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
    glasses: List<G1ServiceCommon.Glasses>,
    status: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    onRefresh: () -> Unit,
) {
    val connectionStatuses = glasses.map { it.status }
    val connectedCount = connectionStatuses.count { it == GlassesStatus.CONNECTED }
    val hasConnecting = connectionStatuses.any { it == GlassesStatus.CONNECTING || it == GlassesStatus.UNINITIALIZED }
    val hasError = serviceError || connectionStatuses.any { it == GlassesStatus.ERROR }

    val statusLabel = when {
        serviceError -> "Service Error"
        hasError -> "Attention Needed"
        hasConnecting -> "Connecting to glasses"
        isLooking -> "Scanning for glasses"
        connectedCount > 0 && connectedCount == glasses.size -> "All glasses connected"
        connectedCount > 0 -> "$connectedCount of ${glasses.size} connected"
        status == ServiceStatus.LOOKING -> "Scanning for glasses"
        status == ServiceStatus.LOOKED && glasses.isEmpty() -> "No glasses discovered"
        else -> "Ready to scan"
    }

    val statusColor = when {
        serviceError || hasError -> ErrorColor
        connectedCount > 0 && connectedCount == glasses.size -> ConnectedColor
        hasConnecting || isLooking -> WarningColor
        else -> DisconnectedColor
    }

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

            if (isLooking || hasConnecting) {
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
                        text = if (isLooking) {
                            "Scanning for nearby G1 glasses…"
                        } else {
                            "Connecting to selected glasses…"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Divider(color = Bof4Sky.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Discovered: ${glasses.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Connected: $connectedCount",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !serviceError,
                border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun GlassesCard(
    glasses: G1ServiceCommon.Glasses,
    onConnect: (String, String?) -> Unit,
    onDisconnect: (String) -> Unit,
    testMessage: String,
    onTestMessageChange: (String) -> Unit,
    onSendTestMessage: ((String, (Boolean) -> Unit) -> Unit)?,
) {
    val displayName = glasses.displayName()
    val statusColor = glasses.statusColor()
    val statusText = glasses.statusText()
    val glassesId = glasses.id

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = statusColor, shape = CircleShape)
                )

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Battery: ${glasses.batteryLabel()}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Firmware: ${glasses.firmwareLabel()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "ID: ${glassesId ?: "Unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = Bof4Mist.copy(alpha = 0.8f)
            )

            if (glasses.status == GlassesStatus.CONNECTED && onSendTestMessage != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = testMessage,
                        onValueChange = onTestMessageChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Test message") },
                        minLines = 1,
                    )

                    Button(
                        onClick = {
                            onSendTestMessage(testMessage) { success ->
                                if (success) {
                                    onTestMessageChange("")
                                }
                            }
                        },
                        enabled = testMessage.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Test Message")
                    }
                }
            }

            val buttonLabel: String
            val buttonEnabled: Boolean
            val onClick: (() -> Unit)?
            when (glasses.status) {
                GlassesStatus.CONNECTED -> {
                    buttonLabel = "Disconnect"
                    buttonEnabled = glassesId != null
                    onClick = glassesId?.let { id -> { onDisconnect(id) } }
                }

                GlassesStatus.CONNECTING, GlassesStatus.DISCONNECTING -> {
                    buttonLabel = if (glasses.status == GlassesStatus.CONNECTING) "Connecting…" else "Disconnecting…"
                    buttonEnabled = false
                    onClick = null
                }

                GlassesStatus.ERROR, GlassesStatus.DISCONNECTED -> {
                    buttonLabel = "Retry"
                    buttonEnabled = glassesId != null
                    onClick = glassesId?.let { id -> { onConnect(id, displayName) } }
                }

                GlassesStatus.UNINITIALIZED -> {
                    buttonLabel = "Connect"
                    buttonEnabled = glassesId != null
                    onClick = glassesId?.let { id -> { onConnect(id, displayName) } }
                }
            }

            Button(
                onClick = { onClick?.invoke() },
                enabled = buttonEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = statusColor.copy(alpha = if (buttonEnabled) 0.85f else 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel)
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
        color = Bof4Steel.copy(alpha = 0.8f),
        contentColor = Bof4Mist,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Bof4Sky.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "No glasses detected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
