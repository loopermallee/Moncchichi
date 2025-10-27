package com.loopermallee.moncchichi.hub.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun HudScreen(
    state: HudUiState,
    onSendMessage: (String) -> Unit,
    onStopMessage: () -> Unit,
    onTargetSelected: (String?) -> Unit,
    onToggleTile: (HudTile, Boolean) -> Unit,
    onMoveTile: (HudTile, Int) -> Unit,
    onRefreshWeather: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var messageText by rememberSaveable { mutableStateOf("") }
    var targetExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state.lastMessageTimestamp) {
        if (state.lastMessageTimestamp != null) {
            messageText = ""
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = "Message sent to HUD",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    LaunchedEffect(state.sendError) {
        val error = state.sendError ?: return@LaunchedEffect
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short,
            )
            onDismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            MirrorCard(state = state)
            MessageComposerCard(
                state = state,
                messageText = messageText,
                onMessageChanged = { messageText = it },
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it },
                onSendMessage = { onSendMessage(messageText) },
                onStopMessage = onStopMessage,
                onTargetSelected = onTargetSelected,
            )
            DashboardCard(
                state = state,
                onToggleTile = onToggleTile,
                onMoveTile = onMoveTile,
                onRefreshWeather = onRefreshWeather,
                onRequestPostNotifications = onRequestPostNotifications,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
        }
    }
}

@Composable
private fun MirrorCard(state: HudUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "HUD Mirror",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = connectionLabel(state),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val mirrorText = state.mirrorState.text
                    if (mirrorText.isBlank()) {
                        Text(
                            text = "No message currently showing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = mirrorText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = if (state.mirrorState.isDisplaying) "Displaying" else "Idle",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (state.mirrorState.isDisplaying) Icons.Filled.PlayArrow else Icons.Filled.Stop,
                                    contentDescription = null,
                                )
                            },
                        )
                        Spacer(Modifier.size(12.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("Scroll ${String.format("%.1f", state.mirrorState.scrollSpeed)}x")
                            },
                        )
                        Spacer(Modifier.size(12.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("Devices ${state.connectedDevices.size}")
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Devices, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageComposerCard(
    state: HudUiState,
    messageText: String,
    onMessageChanged: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSendMessage: () -> Unit,
    onStopMessage: () -> Unit,
    onTargetSelected: (String?) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Compose message",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageChanged,
                label = { Text("HUD text") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            )
            Spacer(Modifier.height(12.dp))
            TargetDropdown(
                state = state,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onTargetSelected = onTargetSelected,
            )
            Spacer(Modifier.height(12.dp))
            if (state.isSendingMessage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSendMessage,
                    enabled = !state.isSendingMessage && messageText.isNotBlank(),
                ) {
                    Text("Send to glasses")
                }
                OutlinedButton(
                    onClick = onStopMessage,
                    enabled = state.connectedDevices.isNotEmpty(),
                ) {
                    Text("Stop display")
                }
            }
        }
    }
}

@Composable
private fun TargetDropdown(
    state: HudUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTargetSelected: (String?) -> Unit,
) {
    val options = remember(state.connectedDevices) {
        listOf(null to "All connected (${state.connectedDevices.size})") +
            state.connectedDevices.map { it.id to it.name }
    }
    val selectedLabel = options
        .firstOrNull { it.first == state.selectedTargetId }
        ?.second ?: options.firstOrNull()?.second ?: "No devices"
    val menuLabel = if (expanded) "Collapse target list" else "Expand target list"
    val interactionSource = remember { MutableInteractionSource() }
    var textFieldSize by remember { mutableStateOf(Size.Zero) }
    val localDensity = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Target") },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = menuLabel,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    textFieldSize = coordinates.size.toSize()
                },
            singleLine = true,
            enabled = options.isNotEmpty(),
        )

        DropdownMenu(
            expanded = expanded && options.isNotEmpty(),
            onDismissRequest = { onExpandedChange(false) },
            modifier = if (textFieldSize.width > 0f) {
                Modifier.width(
                    with(localDensity) {
                        (textFieldSize.width / density).dp
                    },
                )
            } else {
                Modifier
            },
        ) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTargetSelected(id)
                        onExpandedChange(false)
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    onExpandedChange(!expanded)
                },
        )
    }
}

@Composable
private fun DashboardCard(
    state: HudUiState,
    onToggleTile: (HudTile, Boolean) -> Unit,
    onMoveTile: (HudTile, Int) -> Unit,
    onRefreshWeather: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Show time",
                checked = state.hudConfig.showTime,
                onToggle = { onToggleTile(HudTile.TIME, it) },
            )
            ToggleRow(
                title = "Show weather",
                checked = state.hudConfig.showWeather,
                onToggle = { onToggleTile(HudTile.WEATHER, it) },
                trailingContent = {
                    OutlinedButton(onClick = onRefreshWeather) { Text("Refresh") }
                },
            )
            ToggleRow(
                title = "Show temperature",
                checked = state.hudConfig.showTemperature,
                onToggle = { onToggleTile(HudTile.TEMPERATURE, it) },
            )
            ToggleRow(
                title = "Show notifications",
                checked = state.hudConfig.showNotifications,
                onToggle = { onToggleTile(HudTile.NOTIFICATIONS, it) },
            )
            if (!state.notificationListenerEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Enable notification access to mirror alerts on your HUD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text("Open settings")
                    }
                    if (!state.postNotificationPermissionGranted) {
                        TextButton(onClick = onRequestPostNotifications) {
                            Icon(Icons.Filled.Notifications, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text("Grant alerts")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tile order",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            state.hudConfig.tileOrder.forEachIndexed { index, tile ->
                ReorderRow(
                    tile = tile,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.hudConfig.tileOrder.lastIndex,
                    onMoveUp = { onMoveTile(tile, -1) },
                    onMoveDown = { onMoveTile(tile, 1) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Live preview",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            HudPreview(
                state = state,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailingContent?.invoke()
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ReorderRow(
    tile: HudTile,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val label = when (tile) {
        HudTile.MIRROR -> "Mirror"
        HudTile.TIME -> "Time"
        HudTile.WEATHER -> "Weather"
        HudTile.TEMPERATURE -> "Temperature"
        HudTile.NOTIFICATIONS -> "Notifications"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
        }
    }
}

@Composable
private fun HudPreview(state: HudUiState, modifier: Modifier = Modifier) {
    val enabledTiles = state.hudConfig.tileOrder.filter { tile ->
        when (tile) {
            HudTile.MIRROR -> true
            HudTile.TIME -> state.hudConfig.showTime
            HudTile.WEATHER -> state.hudConfig.showWeather
            HudTile.TEMPERATURE -> state.hudConfig.showTemperature
            HudTile.NOTIFICATIONS -> state.hudConfig.showNotifications && state.notifications.isNotEmpty()
        }
    }
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            enabledTiles.forEach { tile ->
                when (tile) {
                    HudTile.MIRROR -> PreviewRow(title = "Mirror", content = state.mirrorState.text.ifBlank { "—" })
                    HudTile.TIME -> PreviewRow(title = "Time", content = DateFormat.getTimeInstance().format(Date()))
                    HudTile.WEATHER -> PreviewRow(
                        title = "Weather",
                        content = state.weather?.description ?: "Unknown",
                    )
                    HudTile.TEMPERATURE -> PreviewRow(
                        title = "Temperature",
                        content = state.weather?.let { "${String.format("%.1f", it.temperatureCelsius)}°C" } ?: "—",
                    )
                    HudTile.NOTIFICATIONS -> {
                        val first = state.notifications.firstOrNull()
                        PreviewRow(
                            title = "Notification",
                            content = first?.let { "${it.appName}: ${it.title}" } ?: "None",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(title: String, content: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun connectionLabel(state: HudUiState): String {
    return when (state.connectionStatus) {
        HudConnectionStatus.DISCONNECTED -> "No connected glasses"
        HudConnectionStatus.CONNECTING -> "Looking for glasses…"
        HudConnectionStatus.CONNECTED -> "Connected to ${state.connectedDevices.size} device(s)"
        HudConnectionStatus.ERROR -> "Connection error"
    }
}

@Preview(showBackground = true)
@Composable
private fun HudScreenPreview() {
    val state = HudUiState(
        connectionStatus = HudConnectionStatus.CONNECTED,
        connectedDevices = listOf(HudDevice(id = "1", name = "G1")),
        mirrorState = HudMirrorState(
            text = "Remember to hydrate",
            isDisplaying = true,
            scrollSpeed = 1.2f,
        ),
        hudConfig = HudConfig(),
        weather = HudWeatherSnapshot(temperatureCelsius = 21.3, description = "Clear skies", updatedAtMillis = System.currentTimeMillis()),
        notifications = listOf(
            HudNotification("Mail", "New email", "Project update", System.currentTimeMillis())
        ),
    )
    HudScreen(
        state = state,
        onSendMessage = {},
        onStopMessage = {},
        onTargetSelected = {},
        onToggleTile = { _, _ -> },
        onMoveTile = { _, _ -> },
        onRefreshWeather = {},
        onRequestPostNotifications = {},
        onOpenNotificationSettings = {},
        onDismissError = {},
    )
}
