package com.loopermallee.moncchichi.hub.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.menuAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.theme.G1HubTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HudScreen(
    state: HudUiState,
    onMessageChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopMessage: () -> Unit,
    onToggleTile: (HudTile) -> Unit,
    onMoveTileUp: (HudTile) -> Unit,
    onMoveTileDown: (HudTile) -> Unit,
    onRefreshWeather: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HudMirrorCard(state)
        HudMessageComposer(
            state = state,
            onMessageChange = onMessageChange,
            onTargetChange = onTargetChange,
            onSendMessage = onSendMessage,
            onStopMessage = onStopMessage,
        )
        HudDashboardSettings(
            state = state,
            onToggleTile = onToggleTile,
            onMoveTileUp = onMoveTileUp,
            onMoveTileDown = onMoveTileDown,
            onRefreshWeather = onRefreshWeather,
            onRequestNotificationPermission = onRequestNotificationPermission,
        )
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HudMirrorCard(state: HudUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mirror",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val statusLabel = when (state.connectionStatus) {
                HudConnectionStatus.DISPLAYING -> "Displaying"
                HudConnectionStatus.CONNECTED -> "Connected"
                HudConnectionStatus.DISCONNECTED -> "Disconnected"
            }
            Text(
                text = "Status: $statusLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.connectedLensName?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = "Lens: $name",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = state.mirrorText.ifBlank { "Nothing displayed" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudMessageComposer(
    state: HudUiState,
    onMessageChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopMessage: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Send a message",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (state.availableTargets.isNotEmpty()) {
                expanded = !expanded
            }
        }
    ) {
        val selected = state.availableTargets.firstOrNull { it.id == state.selectedTargetId }
        OutlinedTextField(
            value = selected?.label ?: "No target",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text("Target") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = state.availableTargets.isNotEmpty(),
        )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.availableTargets.forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                onTargetChange(option.id)
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.messageDraft,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message") },
                placeholder = { Text("Write the text that should appear on the glasses HUD") },
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSendMessage, enabled = !state.isSending && state.availableTargets.isNotEmpty()) {
                    Text(if (state.isSending) "Sending…" else "Send")
                }
                OutlinedButton(onClick = onStopMessage, enabled = state.availableTargets.isNotEmpty()) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun HudDashboardSettings(
    state: HudUiState,
    onToggleTile: (HudTile) -> Unit,
    onMoveTileUp: (HudTile) -> Unit,
    onMoveTileDown: (HudTile) -> Unit,
    onRefreshWeather: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "HUD dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tiles",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefreshWeather, enabled = !state.isWeatherLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh weather")
                }
            }
            if (state.isWeatherLoading) {
                Text(
                    text = "Refreshing weather…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.weatherLastUpdated?.let { timestamp ->
                    val formatted = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
                    Text(
                        text = "Last updated at $formatted",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.hudConfig.tileOrder.forEachIndexed { index, tile ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.Switch(
                            checked = when (tile) {
                                HudTile.TIME -> state.hudConfig.showTime
                                HudTile.WEATHER -> state.hudConfig.showWeather
                                HudTile.TEMPERATURE -> state.hudConfig.showTemperature
                                HudTile.NOTIFICATIONS -> state.hudConfig.showNotifications
                            },
                            onCheckedChange = { onToggleTile(tile) },
                        )
                        Text(
                            text = tileLabel(tile),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { onMoveTileUp(tile) }, enabled = index > 0) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { onMoveTileDown(tile) },
                                enabled = index < state.hudConfig.tileOrder.lastIndex,
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                            }
                        }
                    }
                }
            }
            Divider()
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            HudPreview(state = state)
            if (!state.isNotificationAccessGranted) {
                Text(
                    text = "Grant notification access to show recent alerts on the HUD.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRequestNotificationPermission) {
                    Text("Open notification settings")
                }
            }
        }
    }
}

@Composable
private fun HudPreview(state: HudUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.hudConfig.tileOrder.forEach { tile ->
                when (tile) {
                    HudTile.TIME -> if (state.hudConfig.showTime) {
                        Text(
                            text = "Time • ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HudTile.WEATHER -> if (state.hudConfig.showWeather) {
                        Text(
                            text = "Weather • ${state.weatherDescription ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HudTile.TEMPERATURE -> if (state.hudConfig.showTemperature) {
                        val tempLabel = state.temperatureCelsius?.let {
                            String.format(Locale.getDefault(), "%.1f°C", it)
                        } ?: "—°C"
                        Text(
                            text = "Temperature • $tempLabel",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HudTile.NOTIFICATIONS -> if (state.hudConfig.showNotifications) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.notifications.isEmpty()) {
                                Text(
                                    text = "No recent notifications",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                state.notifications.forEach { notification ->
                                    Text(
                                        text = "• ${notification.title}: ${notification.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tileLabel(tile: HudTile): String = when (tile) {
    HudTile.TIME -> "Current time"
    HudTile.WEATHER -> "Weather summary"
    HudTile.TEMPERATURE -> "Temperature"
    HudTile.NOTIFICATIONS -> "Notifications"
}

@Preview
private fun HudScreenPreview() {
    G1HubTheme {
        val glasses = G1ServiceCommon.Glasses(
            id = "demo-123",
            name = "Demo Lens",
            status = G1ServiceCommon.GlassesStatus.CONNECTED,
            batteryPercentage = 90,
        )
        HudScreen(
            state = HudUiState(
                connectionStatus = HudConnectionStatus.CONNECTED,
                connectedLensName = "Demo Lens",
                mirrorText = "Hello world",
                availableTargets = listOf(HudTargetOption(glasses.id!!, "Demo Lens", glasses)),
                selectedTargetId = glasses.id!!,
                hudConfig = HudConfig(),
                weatherDescription = "Clear sky",
                temperatureCelsius = 21.5,
                notifications = listOf(
                    HudNotification("1", "Calendar", "Standup at 10", System.currentTimeMillis()),
                    HudNotification("2", "Messages", "Alex: On my way", System.currentTimeMillis()),
                ),
                isNotificationAccessGranted = true,
            ),
            onMessageChange = {},
            onTargetChange = {},
            onSendMessage = {},
            onStopMessage = {},
            onToggleTile = {},
            onMoveTileUp = {},
            onMoveTileDown = {},
            onRefreshWeather = {},
            onRequestNotificationPermission = {},
        )
    }
}
