package com.loopermallee.moncchichi.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopermallee.moncchichi.core.ble.ConsoleDiagnostics
import com.loopermallee.moncchichi.core.ble.DeviceBadge
import com.loopermallee.moncchichi.core.ble.DeviceMode
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.bluetooth.G1Inbound
import com.loopermallee.moncchichi.service.G1DisplayService
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
    val diagnosticsFlow = service?.getConsoleDiagnosticsFlow()
    val diagnosticsState = diagnosticsFlow?.collectAsState(initial = ConsoleDiagnostics())
    val diagnostics = diagnosticsState?.value ?: ConsoleDiagnostics()
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
    var highContrast by remember { mutableStateOf(false) }
    val backgroundColor = if (highContrast) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val primaryTextColor = if (highContrast) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.onBackground
    val secondaryTextColor = if (highContrast) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔧 G1 Data Console",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = primaryTextColor,
            )
            IconButton(onClick = { binderProvider()?.disconnect() }) {
                Icon(
                    imageVector = Icons.Filled.LinkOff,
                    contentDescription = "Manual disconnect",
                    tint = if (highContrast) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = "Connected Device: $deviceName",
            style = MaterialTheme.typography.bodyMedium,
            color = primaryTextColor,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "MAC: $mac",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "High Contrast Mode",
                color = secondaryTextColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(checked = highContrast, onCheckedChange = { highContrast = it })
        }

        DeviceConsoleBody(
            binderProvider = binderProvider,
            telemetry = telemetry,
            listState = listState,
            diagnostics = diagnostics,
            highContrast = highContrast,
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
    diagnostics: ConsoleDiagnostics,
    highContrast: Boolean,
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
        G1ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        G1ConnectionState.RECONNECTING -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    val bannerColor by animateColorAsState(targetColor, animationSpec = tween(600), label = "connectionBanner")

    val connectionDescription = when (connectionState) {
        G1ConnectionState.CONNECTED -> "Live data streaming is available."
        G1ConnectionState.RECONNECTING, G1ConnectionState.CONNECTING -> "Attempting to establish a secure link."
        else -> "Power on your glasses and ensure Bluetooth permissions are granted."
    }
    val isConnected = connectionState == G1ConnectionState.CONNECTED

    val primaryTextColor = if (highContrast) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (highContrast) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = if (highContrast) Color(0xFF101010) else Color(0xFF1C1C1C)
    val buttonColor = if (highContrast) Color(0xFF4CAF50) else bannerColor

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
                    vitals.battery?.let { level ->
                        battery = "$level%"
                        batteryPulse.value = true
                        launch {
                            delay(400)
                            batteryPulse.value = false
                        }
                    }
                    vitals.firmware?.takeIf { it.isNotBlank() }?.let { version ->
                        firmware = version
                        firmwarePulse.value = true
                        launch {
                            delay(400)
                            firmwarePulse.value = false
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerColor, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Status: ${connectionState.name}",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = connectionDescription,
                color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Battery",
                value = battery,
                highContrast = highContrast,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Firmware",
                value = firmware,
                highContrast = highContrast,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "Mode: ${formatModeLine(diagnostics.mode)}",
            color = secondaryTextColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )

        if (diagnostics.badges.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                diagnostics.badges.forEach { badge ->
                    BadgeChip(label = badge.displayLabel(), highContrast = highContrast)
                }
            }
        }

        if (!isConnected) {
            Text(
                text = "No active connection. Turn on your G1 glasses and tap Connect from the hub screen.",
                color = secondaryTextColor,
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
                    .alpha(batteryAlpha),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
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
                    .alpha(firmwareAlpha),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
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
                .alpha(sendAlpha),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
        ) {
            Text("Send to Glasses")
        }

        Button(
            onClick = {
                scope.launch {
                    val result = binderProvider()?.exportSessionLog()
                    statusMessage = result?.let { "Session log saved: ${it.name}" }
                        ?: "Session log export failed"
                }
            },
            enabled = telemetry.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
        ) {
            Icon(imageVector = Icons.Filled.FileDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export Session Log")
        }

        statusMessage?.let { message ->
            val statusColor = if (message.contains("saved")) secondaryTextColor else Color(0xFFFF8A80)
            Text(
                text = message,
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📜 Live Log", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = primaryTextColor)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
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
                            color = secondaryTextColor,
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
                            val color = when (event.source.uppercase(Locale.US)) {
                                "APP" -> if (highContrast) Color(0xFF00E5FF) else Color.Cyan
                                "DEVICE" -> if (highContrast) Color(0xFFBB86FC) else Color(0xFFB388FF)
                                "SERVICE" -> Color(0xFFFFB300)
                                "SYSTEM" -> if (highContrast) Color(0xFFFF5252) else Color.Gray
                                else -> primaryTextColor
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
private fun StatusCard(title: String, value: String, highContrast: Boolean, modifier: Modifier = Modifier) {
    val cardColor = if (highContrast) Color(0xFF101010) else Color(0xFF1C1C1C)
    val titleColor = if (highContrast) Color.White else Color.White
    val valueColor = if (highContrast) Color(0xFFA7F3D0) else Color(0xFFB0BEC5)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = titleColor)
            Text(value, color = valueColor)
        }
    }
}

private fun formatModeLine(mode: DeviceMode): String {
    val options = listOf(
        DeviceMode.TEXT to "🟢 Text",
        DeviceMode.IMAGE to "🟣 Image",
        DeviceMode.IDLE to "⚪ Idle",
        DeviceMode.DASHBOARD to "🟠 Dashboard",
    )
    return options.joinToString(" | ") { (candidate, label) ->
        if (candidate == mode) "[$label]" else label
    }
}

private fun DeviceBadge.displayLabel(): String = when (this) {
    DeviceBadge.CHARGING -> "🔋 Charging"
    DeviceBadge.FULL -> "🔋 Full"
    DeviceBadge.WEARING -> "🟢 Wearing"
    DeviceBadge.CRADLE -> "⚫ Cradle"
}

@Composable
private fun BadgeChip(label: String, highContrast: Boolean) {
    val background = if (highContrast) Color(0xFF1F2933) else Color(0x332196F3)
    val textColor = if (highContrast) Color(0xFFA7F3D0) else Color(0xFF2196F3)
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}
