package com.loopermallee.moncchichi.hub.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.glasses.AttentionIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.ConnectedIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.InactiveIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.LensSide
import com.loopermallee.moncchichi.hub.ui.glasses.PairedGlasses
import com.loopermallee.moncchichi.hub.ui.glasses.TransitionIndicator
import com.loopermallee.moncchichi.hub.ui.glasses.batteryLabel
import com.loopermallee.moncchichi.hub.ui.glasses.connectedLensIds
import com.loopermallee.moncchichi.hub.ui.glasses.hasError
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.isAnyInProgress
import com.loopermallee.moncchichi.hub.ui.glasses.isFullyConnected
import com.loopermallee.moncchichi.hub.ui.glasses.lensIds
import com.loopermallee.moncchichi.hub.ui.glasses.statusColor
import com.loopermallee.moncchichi.hub.ui.glasses.statusText
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun DisplayScreen(
    viewModel: ApplicationViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var messageText by rememberSaveable { mutableStateOf("") }
    var lastSentPreview by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val sortedPairs = remember(state.pairedGlasses) {
        state.pairedGlasses.sortedBy { it.pairName.lowercase(Locale.US) }
    }
    val connectablePairs = remember(sortedPairs) {
        sortedPairs.filter { it.connectedLensIds.isNotEmpty() }
    }
    val allConnected = remember(sortedPairs) {
        sortedPairs.isNotEmpty() &&
            sortedPairs.all { it.isFullyConnected && it.connectedLensIds.isNotEmpty() }
    }
    val targetIds = remember(connectablePairs, selectedTargetId, sortedPairs) {
        when {
            connectablePairs.isEmpty() -> emptyList()
            selectedTargetId == null -> connectablePairs.flatMap { it.connectedLensIds }
            else -> sortedPairs.firstOrNull { it.pairId == selectedTargetId }
                ?.let { pair ->
                    if (pair.lensIds.isNotEmpty()) pair.lensIds else pair.connectedLensIds
                }
                ?: emptyList()
        }
    }

    LaunchedEffect(connectablePairs.map { it.pairId }) {
        if (selectedTargetId != null && connectablePairs.none { it.pairId == selectedTargetId }) {
            selectedTargetId = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Display Message",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        ConnectionStatusPanel(
            glasses = sortedPairs,
            modifier = Modifier.fillMaxWidth()
        )

        if (sortedPairs.isNotEmpty()) {
            Text(
                text = "Choose where to send",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val allSelected = selectedTargetId == null
                FilterChip(
                    selected = allSelected && targetIds.isNotEmpty(),
                    onClick = { selectedTargetId = null },
                    label = {
                        Text(if (allConnected) "All connected headsets" else "Connected headsets")
                    },
                    enabled = connectablePairs.isNotEmpty(),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                )

                sortedPairs.forEach { pair ->
                    val enabled = pair.connectedLensIds.isNotEmpty()
                    val isSelected = selectedTargetId == pair.pairId
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedTargetId = if (isSelected) null else pair.pairId
                        },
                        enabled = enabled,
                        label = { PairChipLabel(pair) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }

        OutlinedTextField(
            value = messageText,
            onValueChange = { messageText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            minLines = 2
        )

        TextButton(
            onClick = {
                messageText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor."
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Lorem Ipsum")
        }

        Button(
            onClick = {
                viewModel.displayText(messageText, targetIds) { success ->
                    if (success) {
                        lastSentPreview = messageText.trim().ifBlank { null }
                        messageText = ""
                    }
                }
            },
            enabled = targetIds.isNotEmpty() && messageText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Send Message")
        }

        Button(
            onClick = {
                viewModel.stopDisplaying(targetIds) { success ->
                    if (success) {
                        lastSentPreview = null
                    }
                }
            },
            enabled = targetIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Displaying")
        }

        lastSentPreview?.let { preview ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Last message sent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }

        if (targetIds.isEmpty()) {
            if (connectablePairs.isEmpty()) {
                Text(
                    text = "Connect to your headset from the Device screen to send a message.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusPanel(
    glasses: List<PairedGlasses>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Connection status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (glasses.isEmpty()) {
                Text(
                    text = "No headsets discovered. Use the Device tab to scan for nearby headsets.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                glasses.forEachIndexed { index, pair ->
                    PairStatusRow(pair)
                    if (index < glasses.lastIndex) {
                        Divider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairStatusRow(pair: PairedGlasses) {
    val (summaryLabel, summaryIndicator) = pair.summaryStatus()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = pair.pairName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Pair ID: ${pair.pairId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(color = summaryIndicator)
                Text(
                    text = summaryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LensStatusLine(
                label = fullLensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null),
                glasses = pair.left
            )
            LensStatusLine(
                label = fullLensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null),
                glasses = pair.right
            )
        }

        Text(
            text = "Lens IDs: ${pair.lensIds.joinToString(", ").ifEmpty { "Unavailable" }}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LensStatusLine(
    label: String,
    glasses: G1ServiceCommon.Glasses?
) {
    if (glasses == null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(color = InactiveIndicator)
            Text(
                text = "$label: Not detected",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(color = glasses.statusColor())
                Text(
                    text = "$label: ${glasses.statusText()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "Battery ${glasses.batteryLabel()}",
                style = MaterialTheme.typography.bodySmall
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

@Composable
private fun PairChipLabel(pair: PairedGlasses) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.Start) {
        Text(
            text = pair.pairName,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LensDot(
                label = shortLensLabel(slotIndex = 0, side = pair.leftSide, hasCompanion = pair.right != null),
                glasses = pair.left
            )
            LensDot(
                label = shortLensLabel(slotIndex = 1, side = pair.rightSide, hasCompanion = pair.left != null),
                glasses = pair.right
            )
        }
    }
}

@Composable
private fun LensDot(
    label: String,
    glasses: G1ServiceCommon.Glasses?
) {
    val color = glasses?.statusColor() ?: InactiveIndicator
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun PairedGlasses.summaryStatus(): Pair<String, Color> = when {
    hasError -> "Attention Needed" to AttentionIndicator
    isAnyInProgress -> "Working…" to TransitionIndicator
    isFullyConnected -> "Connected" to ConnectedIndicator
    isAnyConnected -> "Partially Connected" to TransitionIndicator
    else -> "Disconnected" to InactiveIndicator
}

private fun fullLensLabel(slotIndex: Int, side: LensSide, hasCompanion: Boolean): String = when (side) {
    LensSide.LEFT -> "Left"
    LensSide.RIGHT -> "Right"
    LensSide.UNKNOWN -> if (hasCompanion) {
        if (slotIndex == 0) "Lens A" else "Lens B"
    } else {
        "Lens"
    }
}

private fun shortLensLabel(slotIndex: Int, side: LensSide, hasCompanion: Boolean): String = when (side) {
    LensSide.LEFT -> "L"
    LensSide.RIGHT -> "R"
    LensSide.UNKNOWN -> if (hasCompanion) {
        if (slotIndex == 0) "A" else "B"
    } else {
        "L"
    }
}
