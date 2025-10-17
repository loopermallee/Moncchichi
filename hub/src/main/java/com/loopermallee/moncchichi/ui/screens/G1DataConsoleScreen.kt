package com.loopermallee.moncchichi.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.bluetooth.G1Inbound
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CONSOLE_LOG_LIMIT = 120

@Composable
fun G1DataConsoleScreen(
    binderProvider: () -> G1DisplayService.LocalBinder?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🔧 G1 Data Console",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        DeviceConsoleBody(
            binderProvider = binderProvider,
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
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(listOf<ConsoleLog>()) }
    var text by remember { mutableStateOf("Hello G1!") }
    var battery by remember { mutableStateOf("—") }
    var firmware by remember { mutableStateOf("—") }
    var connectionState by remember { mutableStateOf(G1ConnectionState.DISCONNECTED) }

    val binder = binderProvider()

    val connectionColor by animateColorAsState(
        targetValue = when (connectionState) {
            G1ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            G1ConnectionState.CONNECTING, G1ConnectionState.RECONNECTING -> Color(0xFFFFC107)
            else -> Color(0xFFF44336)
        },
        animationSpec = tween(durationMillis = 500),
        label = "connectionColor"
    )

    val connectionLabel = when (connectionState) {
        G1ConnectionState.CONNECTED -> "🟢 Connected to G1 Glasses"
        G1ConnectionState.CONNECTING -> "🟡 Connecting to G1 Glasses…"
        G1ConnectionState.RECONNECTING -> "🟡 Reconnecting to G1 Glasses…"
        else -> "🔴 Disconnected"
    }
    val connectionDescription = when (connectionState) {
        G1ConnectionState.CONNECTED -> "Live data streaming is available."
        G1ConnectionState.CONNECTING, G1ConnectionState.RECONNECTING -> "Attempting to establish a secure link."
        else -> "Power on your glasses and ensure Bluetooth permissions are granted."
    }
    val isConnected = connectionState == G1ConnectionState.CONNECTED
    var previousConnectionState by remember { mutableStateOf<G1ConnectionState?>(null) }

    val actionContentColor = if (connectionColor.luminance() < 0.5f) Color.White else Color.Black
    val actionButtonColors = ButtonDefaults.buttonColors(
        containerColor = connectionColor,
        contentColor = actionContentColor,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    )

    fun pushLog(message: String, type: ConsoleLogType) {
        logs = listOf(ConsoleLog(message, type)) + logs.take(CONSOLE_LOG_LIMIT - 1)
    }

    LaunchedEffect(connectionState) {
        if (previousConnectionState != connectionState) {
            pushLog(connectionLabel, if (isConnected) ConsoleLogType.SYSTEM else ConsoleLogType.ERROR)
            if (!isConnected) {
                battery = "—"
            }
            previousConnectionState = connectionState
        }
    }

    DisposableEffect(binder) {
        var inboundJob: Job? = null
        var connectionJob: Job? = null
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
                            pushLog("Battery → $battery", ConsoleLogType.INBOUND)
                        }

                        is G1Inbound.Firmware -> {
                            firmware = inbound.version
                            pushLog("Firmware → ${inbound.version}", ConsoleLogType.INBOUND)
                        }

                        is G1Inbound.Ack -> {
                            pushLog("Ack op=${inbound.op}", ConsoleLogType.SYSTEM)
                        }

                        is G1Inbound.Error -> {
                            pushLog("Error code=${inbound.code} ${inbound.message}", ConsoleLogType.ERROR)
                        }

                        is G1Inbound.Raw -> {
                            val hex = inbound.bytes.joinToString(" ") { byte -> String.format("%02X", byte) }
                            pushLog("Raw ← $hex", ConsoleLogType.INBOUND)
                        }
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

        onDispose {
            inboundJob?.cancel()
            connectionJob?.cancel()
        }
    }

    val logScrollState = rememberScrollState()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(connectionColor.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                .border(1.dp, connectionColor, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(connectionLabel, color = connectionColor, fontWeight = FontWeight.SemiBold)
                Text(
                    connectionDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
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
                color = Color(0xFFB0BEC5),
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
                    val ok = binderProvider()?.requestBattery() ?: false
                    if (ok) {
                        pushLog("Requesting battery information", ConsoleLogType.OUTBOUND)
                    } else {
                        pushLog("Battery request failed (not connected?)", ConsoleLogType.ERROR)
                    }
                },
                enabled = isConnected,
                modifier = Modifier.weight(1f),
                colors = actionButtonColors
            ) {
                Text("Get Battery")
            }
            Button(
                onClick = {
                    val ok = binderProvider()?.requestFirmware() ?: false
                    if (ok) {
                        pushLog("Requesting firmware information", ConsoleLogType.OUTBOUND)
                    } else {
                        pushLog("Firmware request failed (not connected?)", ConsoleLogType.ERROR)
                    }
                },
                enabled = isConnected,
                modifier = Modifier.weight(1f),
                colors = actionButtonColors
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
                    val ok = binderProvider()?.sendTextPage(payload) ?: false
                    if (ok) {
                        pushLog("Sending text → \"$payload\"", ConsoleLogType.OUTBOUND)
                        text = ""
                    } else {
                        pushLog("Send text failed (not connected?)", ConsoleLogType.ERROR)
                    }
                }
            },
            enabled = isConnected && text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            colors = actionButtonColors
        ) {
            Text("Send to Glasses")
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📜 Live Log", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Activity will appear once data starts flowing.",
                            color = Color(0xFF9E9E9E),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        logs.forEach { entry ->
                            Text(
                                text = entry.message,
                                color = entry.type.color(),
                                fontSize = 13.sp
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(value, color = Color(0xFFB0BEC5))
        }
    }
}

private enum class ConsoleLogType {
    SYSTEM,
    OUTBOUND,
    INBOUND,
    ERROR
}

private data class ConsoleLog(val message: String, val type: ConsoleLogType)

private fun ConsoleLogType.color(): Color = when (this) {
    ConsoleLogType.SYSTEM -> Color(0xFF90A4AE)
    ConsoleLogType.OUTBOUND -> Color(0xFF80DEEA)
    ConsoleLogType.INBOUND -> Color(0xFFB39DDB)
    ConsoleLogType.ERROR -> Color(0xFFFF8A80)
}
