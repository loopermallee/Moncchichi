package com.loopermallee.moncchichi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.bluetooth.G1Inbound
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔧 G1 Data Console", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 24.dp)
        ) {
            Text("← Back to Hub")
        }

        Spacer(modifier = Modifier.height(20.dp))

        DeviceConsoleBody(
            binderProvider = binderProvider,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun DeviceConsoleBody(
    binderProvider: () -> G1DisplayService.LocalBinder?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(listOf<String>()) }
    var text by remember { mutableStateOf("Hello G1!") }
    var battery by remember { mutableStateOf("—") }
    var firmware by remember { mutableStateOf("—") }

    val binder = binderProvider()
    DisposableEffect(binder) {
        var inboundJob: Job? = null
        val flow = binder?.inbound()
        if (flow != null) {
            inboundJob = scope.launch {
                flow.collectLatest { inbound ->
                    when (inbound) {
                        is G1Inbound.Battery -> {
                            val left = inbound.leftPct?.toString() ?: "?"
                            val right = inbound.rightPct?.toString() ?: "?"
                            val case = inbound.casePct?.toString() ?: "?"
                            battery = "L $left% • R $right% • Case $case%"
                            logs = listOf("Battery → $battery") + logs.take(100)
                        }

                        is G1Inbound.Firmware -> {
                            firmware = inbound.version
                            logs = listOf("Firmware → $firmware") + logs.take(100)
                        }

                        is G1Inbound.Ack -> {
                            logs = listOf("Ack op=${inbound.op}") + logs.take(100)
                        }

                        is G1Inbound.Error -> {
                            logs = listOf("Error code=${inbound.code} ${inbound.message}") + logs.take(100)
                        }

                        is G1Inbound.Raw -> {
                            val hex = inbound.bytes.joinToString(" ") { byte -> String.format("%02X", byte) }
                            logs = listOf("Raw ← $hex") + logs.take(100)
                        }
                    }
                }
            }
        }

        onDispose { inboundJob?.cancel() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Battery: $battery", style = MaterialTheme.typography.bodyLarge)
        Text("Firmware: $firmware", style = MaterialTheme.typography.bodyLarge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                val ok = binderProvider()?.requestBattery() ?: false
                if (!ok) logs = listOf("⚠ Battery request failed (not connected?)") + logs.take(100)
            }) {
                Text("Get Battery")
            }
            Button(onClick = {
                val ok = binderProvider()?.requestFirmware() ?: false
                if (!ok) logs = listOf("⚠ Firmware request failed (not connected?)") + logs.take(100)
            }) {
                Text("Get Firmware")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text page") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            val ok = binderProvider()?.sendTextPage(text) ?: false
            if (!ok) logs = listOf("⚠ Send text failed (not connected?)") + logs.take(100)
        }) {
            Text("Send to Glasses")
        }

        Text("Live Log", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            logs.take(60).forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
