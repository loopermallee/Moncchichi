package io.texne.g1.hub.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.ui.glasses.batteryLabel
import io.texne.g1.hub.ui.glasses.displayName
import io.texne.g1.hub.ui.glasses.firmwareLabel
import io.texne.g1.hub.ui.glasses.statusColor
import io.texne.g1.hub.ui.glasses.statusText
import kotlinx.coroutines.flow.collectLatest

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

    val sortedGlasses = remember(state.glasses) {
        state.glasses.sortedBy { it.displayName() }
    }
    val connectedGlasses = remember(sortedGlasses) {
        sortedGlasses.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
    }
    val connectableGlasses = remember(connectedGlasses) {
        connectedGlasses.filter { !it.id.isNullOrBlank() }
    }
    val allConnected = remember(sortedGlasses) {
        sortedGlasses.isNotEmpty() &&
            sortedGlasses.all {
                it.status == G1ServiceCommon.GlassesStatus.CONNECTED && !it.id.isNullOrBlank()
            }
    }
    val targetIds = remember(connectableGlasses, selectedTargetId) {
        if (connectableGlasses.isEmpty()) {
            emptyList()
        } else if (selectedTargetId == null) {
            connectableGlasses.mapNotNull { it.id }
        } else {
            connectableGlasses.firstOrNull { it.id == selectedTargetId }
                ?.id
                ?.let { listOf(it) }
                ?: emptyList()
        }
    }

    LaunchedEffect(connectableGlasses.mapNotNull { it.id }) {
        if (selectedTargetId != null && connectableGlasses.none { it.id == selectedTargetId }) {
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
            glasses = sortedGlasses,
            modifier = Modifier.fillMaxWidth()
        )

        if (sortedGlasses.isNotEmpty()) {
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
                        Text(if (allConnected) "All connected" else "Connected devices")
                    },
                    enabled = connectableGlasses.isNotEmpty(),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                )

                connectableGlasses.forEach { glasses ->
                    val glassesId = glasses.id
                    val isSelected = selectedTargetId == glassesId
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedTargetId = if (isSelected) null else glassesId
                        },
                        enabled = glassesId != null,
                        label = { Text(glasses.displayName()) },
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
            if (connectedGlasses.isEmpty()) {
                Text(
                    text = "Connect to your glasses from the Device screen to send a message.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusPanel(
    glasses: List<G1ServiceCommon.Glasses>,
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
                    text = "No glasses discovered. Use the Device tab to scan for nearby glasses.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                glasses.forEachIndexed { index, glassesItem ->
                    GlassesStatusRow(glassesItem)
                    if (index < glasses.lastIndex) {
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassesStatusRow(glasses: G1ServiceCommon.Glasses) {
    val statusColor = glasses.statusColor()
    val statusText = glasses.statusText()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color = statusColor, shape = CircleShape)
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = glasses.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
    }
}
