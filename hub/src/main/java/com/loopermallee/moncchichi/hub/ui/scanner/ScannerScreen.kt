package com.loopermallee.moncchichi.hub.ui.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide
import com.loopermallee.moncchichi.hub.ui.glasses.PairedGlasses
import com.loopermallee.moncchichi.hub.ui.glasses.batteryLabel
import com.loopermallee.moncchichi.hub.ui.glasses.hasError
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyInProgress
import com.loopermallee.moncchichi.hub.ui.glasses.isFullyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.lensIds
import com.loopermallee.moncchichi.hub.ui.glasses.statusColor
import com.loopermallee.moncchichi.hub.ui.glasses.statusText
import com.loopermallee.moncchichi.hub.ui.scanner.LensChipState
import com.loopermallee.moncchichi.hub.ui.scanner.LensConnectionPhase
import com.loopermallee.moncchichi.hub.ui.scanner.PairingProgress
import com.loopermallee.moncchichi.hub.ui.scanner.ScanBannerState
import com.loopermallee.moncchichi.hub.ui.scanner.ScanStage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    scanning: Boolean,
    error: Boolean,
    nearbyGlasses: List<PairedGlasses>?,
    pairingProgress: Map<String, PairingProgress> = emptyMap(),
    banner: ScanBannerState = ScanBannerState(),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ScanProgressBanner(banner = banner)
            if (pairedGlasses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ScannerEmptyState(
                        scanning = scanning,
                        error = error,
                        banner = banner,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(pairedGlasses, key = { it.pairId }) { pair ->
                        val progress = findProgressForPair(pair, pairingProgress)
                        PairedHeadsetCard(
                            pair = pair,
                            progress = progress,
                            onConnect = connect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerEmptyState(
    scanning: Boolean,
    error: Boolean,
    banner: ScanBannerState,
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
            title = banner.headline.ifBlank { "Scanning for headsets" }
            val countdownSuffix = banner.countdownSeconds?.takeIf { it > 0 }?.let { " ${it}s left." } ?: ""
            val tip = banner.tip?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            description = buildString {
                append(banner.supporting.ifBlank { "Stay close to your headset and keep this screen open." })
                append(countdownSuffix)
                append(tip)
            }
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanProgressBanner(
    banner: ScanBannerState,
    modifier: Modifier = Modifier,
) {
    if (banner.stage == ScanStage.Idle) {
        Spacer(modifier = modifier.height(4.dp))
        return
    }
    val scheme = MaterialTheme.colorScheme
    val container = if (banner.isWarning) scheme.errorContainer else scheme.surfaceVariant
    val content = if (banner.isWarning) scheme.onErrorContainer else scheme.onSurface
    val outline = if (banner.isWarning) scheme.error else scheme.outline.copy(alpha = 0.2f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        border = BorderStroke(1.dp, outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (banner.showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = content
                )
            } else {
                Spacer(modifier = Modifier.size(20.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = banner.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = content
                )
                Text(
                    text = banner.supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.9f)
                )
                banner.countdownSeconds?.takeIf { it > 0 }?.let { seconds ->
                    Text(
                        text = "$seconds s remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.8f)
                    )
                }
                banner.tip?.takeIf { it.isNotBlank() }?.let { tip ->
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (banner.isWarning) scheme.onErrorContainer else scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedHeadsetCard(
    pair: PairedGlasses,
    progress: PairingProgress?,
    onConnect: (List<String>, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val lensIds = pair.lensIds
    val hasLensIds = lensIds.isNotEmpty()
    val statusColor = when {
        pair.hasError -> scheme.error
        pair.isAnyInProgress -> scheme.tertiary
        pair.isAnyConnected -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    val statusLabel = when {
        pair.hasError -> "Attention needed"
        pair.isAnyInProgress -> "Working…"
        pair.isFullyConnected -> "Connected"
        pair.isAnyConnected -> "Partially connected"
        else -> "Ready to pair"
    }

    val detectionMessage = detectionMessage(pair, progress)
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
            containerColor = scheme.surface,
            contentColor = scheme.onSurface
        ),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
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
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LensStatusChip(
                    label = lensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null),
                    glasses = pair.left,
                    chipState = progress?.leftChip,
                    modifier = Modifier.weight(1f)
                )
                LensStatusChip(
                    label = lensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null),
                    glasses = pair.right,
                    chipState = progress?.rightChip,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = detectionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                if (progress?.stage == ScanStage.Timeout && !progress.tip.isNullOrBlank()) {
                    Text(
                        text = progress.tip,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "Lens IDs: ${formatLensIds(lensIds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
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
    chipState: LensChipState?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        chipState != null -> chipState.status.color(scheme, chipState.isWarning)
        else -> glasses?.statusColor() ?: scheme.outline
    }
    val title = chipState?.title ?: label
    val details = buildList {
        val chipDetail = chipState?.detail?.takeIf { it.isNotBlank() }
        if (chipDetail != null) add(chipDetail)
        if (glasses != null) {
            val status = glasses.statusText()
            if (chipDetail.isNullOrBlank()) add(status)
            val battery = glasses.batteryLabel()
            if (battery.isNotBlank()) add("Battery $battery")
        }
    }
    val detailText = details.joinToString(" • ").ifBlank { "Not detected" }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f),
            contentColor = color
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

private fun detectionMessage(pair: PairedGlasses, progress: PairingProgress?): String {
    progress?.let { state ->
        when (state.stage) {
            ScanStage.Searching -> return "Scanning for nearby headsets…"
            ScanStage.LensDetected, ScanStage.WaitingForCompanion -> {
                val searchingChip = listOf(state.leftChip, state.rightChip)
                    .firstOrNull { it.status == LensConnectionPhase.Searching }
                val countdown = state.countdownSeconds?.takeIf { it > 0 }
                    ?.let { " ${it}s left" } ?: ""
                val target = searchingChip?.title?.lowercase(Locale.US) ?: "companion lens"
                return "Waiting for $target$countdown"
            }

            ScanStage.Ready -> return "Both lenses detected. Preparing to connect…"
            ScanStage.Connecting -> return "Connecting both lenses…"
            ScanStage.Timeout -> return state.tip ?: "Timed out waiting for the companion lens. Keep the case open and retry."
            ScanStage.Completed -> return "Both lenses ready."
            ScanStage.Idle -> Unit
        }
    }
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

private fun formatLensIds(ids: List<String>): String = ids.joinToString(", ").ifEmpty { "Unavailable" }

private fun LensConnectionPhase.color(scheme: ColorScheme, isWarning: Boolean): Color = when (this) {
    LensConnectionPhase.Connected -> scheme.primary
    LensConnectionPhase.Connecting -> scheme.tertiary
    LensConnectionPhase.Searching -> scheme.secondary
    LensConnectionPhase.Timeout, LensConnectionPhase.Error -> if (isWarning) scheme.error else scheme.error
    LensConnectionPhase.Idle -> scheme.onSurfaceVariant
}

private fun findProgressForPair(
    pair: PairedGlasses,
    progress: Map<String, PairingProgress>,
): PairingProgress? {
    if (progress.isEmpty()) return null
    val keys = buildSet {
        add(pair.pairId.lowercase(Locale.US))
        add(pair.pairName.lowercase(Locale.US))
        pair.lensIds.forEach { id -> add(id.lowercase(Locale.US)) }
    }
    return progress.values.firstOrNull { state ->
        state.candidateIds.any { it in keys }
    }
}
