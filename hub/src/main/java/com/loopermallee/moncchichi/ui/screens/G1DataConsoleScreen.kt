package com.loopermallee.moncchichi.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.bluetooth.G1Inbound
import com.loopermallee.moncchichi.service.G1DisplayService
import com.loopermallee.moncchichi.hub.ui.theme.StatusConnected
import com.loopermallee.moncchichi.hub.ui.theme.StatusError
import com.loopermallee.moncchichi.hub.ui.theme.StatusWarning
import com.loopermallee.moncchichi.ui.shared.LocalServiceConnection
import com.loopermallee.moncchichi.telemetry.G1TelemetryEvent
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun G1DataConsoleScreen(
    binderProvider: () -> G1DisplayService.LocalBinder?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val service = LocalServiceConnection.current
    val telemetryFlow = service?.getTelemetryFlow()
    val telemetryState = telemetryFlow?.collectAsState(initial = emptyList<G1TelemetryEvent>())
    val telemetry = telemetryState?.value ?: emptyList()
    val listState = rememberLazyListState()
    LaunchedEffect(telemetry.size) {
        if (telemetry.isNotEmpty()) {
            listState.animateScrollToItem(telemetry.lastIndex)
        }
    }

    val device = service?.getCurrentDevice()
    val deviceName = device?.name ?: "None"
    val mac = device?.address ?: "N/A"
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🔧 G1 Data Console",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Connected Device: $deviceName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "MAC: $mac",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        DeviceConsoleBody(
            binderProvider = binderProvider,
            telemetry = telemetry,
            listState = listState,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
        ) {
            Text("← Back to Hub", fontSize = 16.sp)
        }
    }
}

@Composable
fun DeviceConsoleBody(
    binderProvider: () -> G1DisplayService.LocalBinder?,
    telemetry: List<G1TelemetryEvent>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Hello G1!") }
    var battery by remember { mutableStateOf("—") }
    var firmware by remember { mutableStateOf("—") }
    var connectionState by remember { mutableStateOf(G1ConnectionState.DISCONNECTED) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val binder = binderProvider()

    val targetColor = when (connectionState) {
        G1ConnectionState.CONNECTED -> StatusConnected
        G1ConnectionState.RECONNECTING -> StatusWarning
        G1ConnectionState.CONNECTING -> StatusWarning
        G1ConnectionState.DISCONNECTED -> StatusWarning
        else -> StatusError
    }
    val bannerColor by animateColorAsState(targetColor, animationSpec = tween(600), label = "connectionBanner")

    val connectionDescription = when (connectionState) {
        G1ConnectionState.CONNECTED -> "Live data streaming is available."
        G1ConnectionState.RECONNECTING, G1ConnectionState.CONNECTING -> "Attempting to establish a secure link."
        else -> "Power on your glasses and ensure Bluetooth permissions are granted."
    }
    val isConnected = connectionState == G1ConnectionState.CONNECTED

    val batteryPulse = remember { mutableStateOf(false) }
    val firmwarePulse = remember { mutableStateOf(false) }
    val sendPulse = remember { mutableStateOf(false) }
    val batteryAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (batteryPulse.value) 0.5f else 1f,
        animationSpec = tween(400),
        label = "batteryPulse"
    )
    val firmwareAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (firmwarePulse.value) 0.5f else 1f,
        animationSpec = tween(400),
        label = "firmwarePulse"
    )
    val sendAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (sendPulse.value) 0.5f else 1f,
        animationSpec = tween(400),
        label = "sendPulse"
    )

    LaunchedEffect(connectionState) {
        if (!isConnected) {
            battery = "—"
            firmware = "—"
        }
        if (connectionState == G1ConnectionState.DISCONNECTED) {
            statusMessage = null
        }
    }

    DisposableEffect(binder) {
        var inboundJob: Job? = null
        var connectionJob: Job? = null
        var vitalsJob: Job? = null
        val inboundFlow = binder?.inbound()
        if (inboundFlow != null) {
            inboundJob = scope.launch {
                inboundFlow.collectLatest { inbound ->
                    when (inbound) {
                        is G1Inbound.Battery -> {
                            val left = inbound.leftPct?.toString() ?: "?"
                            val right = inbound.rightPct?.toString() ?: "?"
                            val case = inbound.casePct?.toString() ?: "?"
                            battery = "L $left% • R $right% • Case $case%"
                        }

                        is G1Inbound.Firmware -> {
                            firmware = inbound.version
                        }

                        is G1Inbound.Error -> {
                            statusMessage = "Error code=${inbound.code} ${inbound.message}"
                        }

                        else -> Unit
                    }
                }
            }
        }

        val connectionFlow = binder?.connectionStates
        if (connectionFlow != null) {
            connectionJob = scope.launch {
                connectionFlow.collectLatest { state ->
                    connectionState = state
                }
            }
        } else {
            connectionState = G1ConnectionState.DISCONNECTED
        }

        val vitalsFlow = binder?.vitals()
        if (vitalsFlow != null) {
            vitalsJob = scope.launch {
                vitalsFlow.collectLatest { vitals ->
                    vitals.batteryPercent?.let { level ->
                        battery = "$level%"
                        batteryPulse.value = true
                        launch {
                            delay(400)
                            batteryPulse.value = false
                        }
                    }
                }
            }
        }

        onDispose {
            inboundJob?.cancel()
            connectionJob?.cancel()
            vitalsJob?.cancel()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = {
                    binder?.disconnect()
                    binder?.recordTelemetry(
                        G1TelemetryEvent(
                            source = "APP",
                            tag = "[ACTION]",
                            message = "🔌 Manual disconnect triggered"
                        )
                    )
                    statusMessage = "Manual disconnect requested"
                },
                enabled = binder != null,
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Disconnect",
                    tint = StatusError
                )
            }

            val clipboardManager = LocalClipboardManager.current
            val logText = remember(telemetry) { telemetry.joinToString("\n") { it.toString() } }

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(logText))
                    binder?.recordTelemetry(
                        G1TelemetryEvent(
                            source = "APP",
                            tag = "[LOG]",
                            message = "📋 Log copied (${telemetry.size} entries)"
                        )
                    )
                },
                enabled = telemetry.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy log",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = {
                    binder?.clearTelemetry()
                    binder?.recordTelemetry(
                        G1TelemetryEvent("APP", "[LOG]", "🗑️ Log cleared")
                    )
                },
                enabled = telemetry.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Clear log",
                    tint = StatusWarning
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(bannerColor)
                    )
                    Text(
                        text = "Status: ${connectionState.name}",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    text = connectionDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(title = "Battery", value = battery, modifier = Modifier.weight(1f))
            StatusCard(title = "Firmware", value = firmware, modifier = Modifier.weight(1f))
        }

        if (!isConnected) {
            Text(
                text = "No active connection. Turn on your G1 glasses and tap Connect from the hub screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    batteryPulse.value = true
                    scope.launch {
                        val ok = binderProvider()?.requestBattery() ?: false
                        if (!ok) {
                            statusMessage = "Battery request failed (not connected?)"
                        }
                        delay(600)
                        batteryPulse.value = false
                    }
                },
                enabled = isConnected,
                modifier = Modifier
                    .weight(1f)
                    .alpha(batteryAlpha)
            ) {
                Text("Get Battery")
            }
            Button(
                onClick = {
                    firmwarePulse.value = true
                    scope.launch {
                        val ok = binderProvider()?.requestFirmware() ?: false
                        if (!ok) {
                            statusMessage = "Firmware request failed (not connected?)"
                        }
                        delay(600)
                        firmwarePulse.value = false
                    }
                },
                enabled = isConnected,
                modifier = Modifier
                    .weight(1f)
                    .alpha(firmwareAlpha)
            ) {
                Text("Get Firmware")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Message to Glasses") },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected
        )

        Button(
            onClick = {
                val payload = text.trim()
                if (payload.isNotEmpty()) {
                    sendPulse.value = true
                    scope.launch {
                        val ok = binderProvider()?.sendTextPage(payload) ?: false
                        if (ok) {
                            text = ""
                            statusMessage = null
                        } else {
                            statusMessage = "Send text failed (not connected?)"
                        }
                        delay(600)
                        sendPulse.value = false
                    }
                }
            },
            enabled = isConnected && text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .alpha(sendAlpha)
        ) {
            Text("Send to Glasses")
        }

        statusMessage?.let { message ->
            Text(
                text = message,
                color = StatusError,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "📜 Live Log",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                if (telemetry.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Activity will appear once data starts flowing.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        state = listState
                    ) {
                        items(telemetry) { event ->
                            val baseColor = when (event.source.uppercase(Locale.US)) {
                                "APP" -> MaterialTheme.colorScheme.primary
                                "DEVICE" -> MaterialTheme.colorScheme.secondary
                                "SERVICE" -> StatusConnected
                                "SYSTEM" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            val color = if ("error" in event.tag.lowercase(Locale.US)) {
                                StatusError
                            } else {
                                baseColor
                            }
                            Text(
                                text = event.toString(),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
