package com.loopermallee.moncchichi.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.bluetooth.G1Inbound
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class G1DataConsoleActivity : ComponentActivity() {
    private var binder: G1DisplayService.LocalBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? G1DisplayService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("G1 Data Console", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                ) { padding ->
                    ConsoleContent(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        binderProvider = { binder }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleContent(
    modifier: Modifier = Modifier,
    binderProvider: () -> G1DisplayService.LocalBinder?
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
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Battery: $battery", style = MaterialTheme.typography.bodyLarge)
        Text("Firmware: $firmware", style = MaterialTheme.typography.bodyLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
