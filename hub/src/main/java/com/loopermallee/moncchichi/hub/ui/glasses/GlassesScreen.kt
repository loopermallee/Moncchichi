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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.client.G1ServiceCommon.GlassesStatus
import com.loopermallee.moncchichi.client.G1ServiceCommon.ServiceStatus
import com.loopermallee.moncchichi.hub.ui.components.screenContentWidth
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide.UNKNOWN
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide.LEFT
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide.RIGHT
import com.loopermallee.moncchichi.hub.ui.glasses.PairedGlasses
import com.loopermallee.moncchichi.hub.ui.glasses.hasError
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyInProgress
import com.loopermallee.moncchichi.hub.ui.glasses.isFullyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.lensIds
import com.loopermallee.moncchichi.hub.ui.glasses.lensRecords
import com.loopermallee.moncchichi.hub.ui.theme.StatusConnected
import com.loopermallee.moncchichi.hub.ui.theme.StatusError
import com.loopermallee.moncchichi.hub.ui.theme.StatusIdle
import com.loopermallee.moncchichi.hub.ui.theme.StatusWarning
import java.util.Locale

private val ConnectedColor = StatusConnected
private val DisconnectedColor = StatusIdle
private val ErrorColor = StatusError
private val WarningColor = StatusWarning

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

@Composable
fun GlassesScreen(
    glasses: List<PairedGlasses>,
    serviceStatus: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    connect: (List<String>, String?) -> Unit,
    disconnect: (List<String>) -> Unit,
    refresh: () -> Unit,
    testMessages: Map<String, String>,
    onTestMessageChange: (String, String) -> Unit,
    onSendTestMessage: (List<String>, String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedGlasses = glasses.sortedBy { it.pairName.lowercase(Locale.US) }
    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        LazyColumn(
            modifier = Modifier
                .screenContentWidth()
                .align(Alignment.TopCenter),
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
                        text = "Available Headsets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                items(sortedGlasses, key = { it.pairId }) { pair ->
                    val testMessage = testMessages[pair.pairId] ?: ""
                    val messageChanged: (String) -> Unit = { newValue ->
                        onTestMessageChange(pair.pairId, newValue)
                    }
                    val lensIds = pair.lensIds
                    val sendMessage = if (lensIds.isNotEmpty()) {
                        { message: String, onResult: (Boolean) -> Unit ->
                            onSendTestMessage(lensIds, message, onResult)
                        }
                    } else {
                        null
                    }
                    GlassesCard(
                        pair = pair,
                        lensIds = lensIds,
                        onConnect = { connect(lensIds, pair.pairName) },
                        onDisconnect = { disconnect(lensIds) },
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
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 4.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "G1 Hub Device Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Discover, pair, and manage your G1 glasses with a single tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusPanel(
    glasses: List<PairedGlasses>,
    status: ServiceStatus,
    isLooking: Boolean,
    serviceError: Boolean,
    onRefresh: () -> Unit,
) {
    val totalPairs = glasses.size
    val connectedPairs = glasses.count { it.isFullyConnected }
    val partiallyConnectedPairs = glasses.count { it.isAnyConnected && !it.isFullyConnected }
    val connectedLensCount = glasses.sumOf { pair -> pair.lensRecords.count { it.status == GlassesStatus.CONNECTED } }
    val totalLensCount = glasses.sumOf { it.lensRecords.size }
    val hasConnecting = glasses.any { it.isAnyInProgress }
    val hasError = serviceError || glasses.any { it.hasError }

    val statusLabel = when {
        serviceError -> "Service Error"
        hasError -> "Attention Needed"
        isLooking -> "Scanning for headsets"
        hasConnecting -> "Managing connections"
        connectedPairs > 0 && connectedPairs == totalPairs -> "All headsets connected"
        connectedPairs > 0 -> "$connectedPairs fully connected"
        partiallyConnectedPairs > 0 -> "Partial connections"
        status == ServiceStatus.LOOKED && glasses.isEmpty() -> "No headsets discovered"
        else -> "Ready to scan"
    }

    val statusColor = when {
        serviceError || hasError -> ErrorColor
        isLooking || hasConnecting -> WarningColor
        connectedPairs > 0 && connectedPairs == totalPairs -> ConnectedColor
        connectedPairs > 0 || partiallyConnectedPairs > 0 -> WarningColor
        else -> DisconnectedColor
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
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
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            StatusPill(
                tone = statusColor,
                label = statusLabel,
                modifier = Modifier.fillMaxWidth()
            )

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
                            "Scanning for nearby headsets…"
                        } else {
                            "Finalizing connections…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMetric(
                    title = "Headsets discovered",
                    value = "$totalPairs"
                )
                StatusMetric(
                    title = "Connected lenses",
                    value = "$connectedLensCount / $totalLensCount",
                    horizontalAlignment = Alignment.End
                )
            }

            if (partiallyConnectedPairs > 0) {
                Text(
                    text = "$partiallyConnectedPairs headset" + if (partiallyConnectedPairs == 1) " needs attention" else "s need attention",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !serviceError,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun GlassesCard(
    pair: PairedGlasses,
    lensIds: List<String>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    testMessage: String,
    onTestMessageChange: (String) -> Unit,
    onSendTestMessage: ((String, (Boolean) -> Unit) -> Unit)?,
) {
    val statusColor = pair.overallStatusColor()
    val hasLensIds = lensIds.isNotEmpty()
    val connectAction = if (hasLensIds) onConnect else null
    val disconnectAction = if (hasLensIds) onDisconnect else null
    val isBusy = pair.isAnyInProgress

    val (buttonLabel, buttonAction) = when {
        isBusy && pair.isAnyConnected -> "Disconnecting…" to null
        isBusy -> "Connecting…" to null
        pair.isAnyConnected -> "Disconnect" to disconnectAction
        pair.hasError -> "Retry" to connectAction
        else -> "Connect" to connectAction
    }

    val buttonEnabled = buttonAction != null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = pair.pairName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Pair ID: ${pair.pairId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(
                    tone = statusColor,
                    label = pair.overallStatusLabel()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LensStatusBadge(
                    label = lensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null),
                    glasses = pair.left,
                    modifier = Modifier.weight(1f)
                )
                LensStatusBadge(
                    label = lensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null),
                    glasses = pair.right,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Lens IDs: ${lensIds.joinToString(", ").ifEmpty { "Unavailable" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (pair.isAnyConnected && onSendTestMessage != null) {
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
            } else if (!pair.isAnyConnected) {
                Text(
                    text = "Connect to both lenses before sending a test message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!hasLensIds) {
                Text(
                    text = "Lens identifiers unavailable. Try refreshing discovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { buttonAction?.invoke() },
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun StatusPill(
    tone: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusSwatch(color = tone)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatusMetric(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun LensStatusBadge(
    label: String,
    glasses: G1ServiceCommon.Glasses?,
    modifier: Modifier = Modifier,
) {
    val color = glasses?.statusColor() ?: DisconnectedColor
    val detailText = if (glasses != null) {
        "${glasses.statusText()} \u2022 Battery ${glasses.batteryLabel()}"
    } else {
        "Not detected"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusSwatch(color = color)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun lensLabel(slotIndex: Int, side: LensSide, hasCompanion: Boolean): String = when (side) {
    LEFT -> "Left"
    RIGHT -> "Right"
    UNKNOWN -> if (hasCompanion) {
        if (slotIndex == 0) "Lens A" else "Lens B"
    } else {
        "Lens"
    }
}

private fun PairedGlasses.overallStatusColor(): Color = when {
    hasError -> ErrorColor
    isAnyInProgress -> WarningColor
    isAnyConnected -> ConnectedColor
    else -> DisconnectedColor
}

private fun PairedGlasses.overallStatusLabel(): String = when {
    hasError -> "Attention Needed"
    isAnyInProgress -> "Working…"
    isFullyConnected -> "Connected"
    isAnyConnected -> "Partially Connected"
    else -> "Disconnected"
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
            "Scanning for headsets… stay close to your device."

        else ->
            "Tap refresh to scan for available G1 headsets nearby."
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "No headsets detected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
