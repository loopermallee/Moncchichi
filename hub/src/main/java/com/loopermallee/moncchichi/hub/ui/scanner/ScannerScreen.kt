package com.loopermallee.moncchichi.hub.ui.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.glasses.AttentionIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.ConnectedIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.InactiveIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide
import com.loopermallee.moncchichi.hub.ui.glasses.PairedGlasses
import com.loopermallee.moncchichi.hub.ui.glasses.TransitionIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.batteryLabel
import com.loopermallee.moncchichi.hub.ui.glasses.hasError
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyInProgress
import com.loopermallee.moncchichi.hub.ui.glasses.isFullyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.lensIds
import com.loopermallee.moncchichi.hub.ui.glasses.statusColor
import com.loopermallee.moncchichi.hub.ui.glasses.statusText
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    scanning: Boolean,
    error: Boolean,
    nearbyGlasses: List<PairedGlasses>?,
    scan: () -> Unit,
    connect: (lensIds: List<String>, pairName: String?) -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()
    val pairedGlasses = (nearbyGlasses ?: emptyList())
        .sortedBy { it.pairName.lowercase(Locale.US) }

    PullToRefreshBox(
        modifier = Modifier.fillMaxSize(),
        isRefreshing = scanning,
        onRefresh = scan,
        state = pullToRefreshState,
    ) {
        if (pairedGlasses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                ScannerEmptyState(
                    scanning = scanning,
                    error = error,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(pairedGlasses, key = { it.pairId }) { pair ->
                    PairedHeadsetCard(
                        pair = pair,
                        onConnect = connect,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerEmptyState(
    scanning: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val title: String
    val description: String
    when {
        error -> {
            title = "Unable to scan"
            description = "Check Bluetooth and try again."
        }

        scanning -> {
            title = "Scanning for headsets"
            description = "Stay close to your headset and keep this screen open."
        }

        else -> {
            title = "No headsets nearby"
            description = "Pull to refresh to look again."
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
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

@Composable
private fun PairedHeadsetCard(
    pair: PairedGlasses,
    onConnect: (List<String>, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lensIds = pair.lensIds
    val hasLensIds = lensIds.isNotEmpty()
    val (statusLabel, statusIndicator) = pair.summaryStatus()

    val detectionMessage = detectionMessage(pair)
    val buttonLabel = when {
        pair.isAnyInProgress -> "Connecting…"
        pair.isFullyConnected -> "Connected"
        pair.hasError -> "Retry"
        pair.isAnyConnected -> "Reconnect"
        else -> "Connect"
    }
    val buttonAction: (() -> Unit)? = when {
        !hasLensIds -> null
        pair.isAnyInProgress -> null
        pair.isFullyConnected -> null
        else -> {
            { onConnect(lensIds, pair.pairName) }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(color = statusIndicator)
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LensStatusChip(
                    label = lensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null),
                    glasses = pair.left,
                    modifier = Modifier.weight(1f)
                )
                LensStatusChip(
                    label = lensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null),
                    glasses = pair.right,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = detectionMessage,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Lens IDs: ${formatLensIds(lensIds)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Button(
                onClick = { buttonAction?.invoke() },
                enabled = buttonAction != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun LensStatusChip(
    label: String,
    glasses: G1ServiceCommon.Glasses?,
    modifier: Modifier = Modifier,
) {
    val indicatorColor = glasses?.statusColor() ?: InactiveIndicator
    val detailText = if (glasses != null) {
        "${glasses.statusText()} • Battery ${glasses.batteryLabel()}"
    } else {
        "Not detected"
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(color = indicatorColor)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun StatusIndicator(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun detectionMessage(pair: PairedGlasses): String {
    val leftLabel = pair.left?.let { lensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null) }
    val rightLabel = pair.right?.let { lensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null) }
    return when {
        leftLabel != null && rightLabel != null -> "Both lenses detected for this headset."
        leftLabel != null -> "$leftLabel lens detected. Waiting for the other side."
        rightLabel != null -> "$rightLabel lens detected. Waiting for the other side."
        else -> "No lenses detected yet. Pull to refresh."
    }
}

private fun lensLabel(slotIndex: Int, side: LensSide, hasCompanion: Boolean): String = when (side) {
    LensSide.LEFT -> "Left"
    LensSide.RIGHT -> "Right"
    LensSide.UNKNOWN -> if (hasCompanion) {
        if (slotIndex == 0) "Lens A" else "Lens B"
    } else {
        "Lens"
    }
}

private fun PairedGlasses.summaryStatus(): Pair<String, Color> = when {
    hasError -> "Attention needed" to AttentionIndicator
    isAnyInProgress -> "Working…" to TransitionIndicator
    isFullyConnected -> "Connected" to ConnectedIndicator
    isAnyConnected -> "Partially connected" to TransitionIndicator
    else -> "Ready to pair" to InactiveIndicator
}

private fun formatLensIds(ids: List<String>): String = ids.joinToString(", ").ifEmpty { "Unavailable" }
