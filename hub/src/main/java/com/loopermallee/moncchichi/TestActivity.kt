package com.loopermallee.moncchichi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.DiscoveredDevice
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.service.G1DisplayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class TestActivity : ComponentActivity() {
    private var serviceBound = false
    private var displayService: G1DisplayService? = null
    private lateinit var scanner: BluetoothScanner

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? G1DisplayService.LocalBinder
            displayService = b?.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
        }
    }

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* recomposed by state checks */ }

    private var bluetoothReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BluetoothScanner(this)
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // recomposition will read scanner.isBluetoothOn()
                }
            }
        registerReceiver(receiver, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        bluetoothReceiver = receiver
        setContent { MoncchichiHome() }
        ensureService()
        ensurePermissions()
    }

    private fun ensureService() {
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (!serviceBound) bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequester.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        scanner.close()
        bluetoothReceiver?.let { unregisterReceiver(it) }
        bluetoothReceiver = null
        if (serviceBound) unbindService(connection)
        super.onDestroy()
    }

    // ---------------- UI (Compose) ----------------
    @Composable
    private fun MoncchichiHome() {
        val devices by scanner.devices.collectAsState()
        var btOn by remember { mutableStateOf(scanner.isBluetoothOn()) }
        var pairingLog by remember { mutableStateOf(listOf<String>()) }
        var troubleshooting by remember { mutableStateOf(listOf<String>()) }
        var state by remember { mutableStateOf(G1ConnectionState.DISCONNECTED) }

        // Observe service state
        LaunchedEffect(displayService) {
            displayService?.connectionState?.collectLatest { s ->
                state = s
                when (s) {
                    G1ConnectionState.CONNECTING -> {
                        pairingLog = listOf("Searching…", "Resolving device…", "Opening GATT…")
                        troubleshooting = listOf("Bluetooth ✓", "Scanning ✓", "Handshake …")
                    }
                    G1ConnectionState.CONNECTED -> {
                        pairingLog = pairingLog + "Handshake complete ✓"
                        troubleshooting = listOf("Bluetooth ✓", "Scanning ✓", "Handshake ✓")
                    }
                    G1ConnectionState.RECONNECTING -> {
                        pairingLog = listOf("Link lost. Reconnecting…")
                        troubleshooting = listOf("Bluetooth ✓", "Scanning …", "Handshake –")
                    }
                    G1ConnectionState.DISCONNECTED -> {
                        troubleshooting = listOf(
                            "Bluetooth " + if (scanner.isBluetoothOn()) "✓" else "✗",
                            "Scanning –",
                            "Handshake –"
                        )
                    }
                    else -> Unit
                }
            }
        }

        // Reflect Bluetooth state
        LaunchedEffect(Unit) {
            while (true) {
                val newState = scanner.isBluetoothOn()
                if (newState != btOn) btOn = newState
                delay(600)
            }
        }

        // Auto-pair to Even G1* when discovered and not already connecting/connected
        LaunchedEffect(devices, state, btOn) {
            if (btOn && state != G1ConnectionState.CONNECTED && state != G1ConnectionState.CONNECTING) {
                devices.firstOrNull { (it.name ?: "").startsWith("Even G1") }?.let {
                    pairingLog = listOf("Auto-pair to ${it.name ?: it.address}")
                    displayService?.connect(it.address)
                }
            }
        }

        MaterialTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = true, onClick = { /* current */ },
                            icon = { Text("\uD83D\uDD17") }, label = { Text("Pair") }
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { startActivity(Intent(this@TestActivity, PermissionsActivity::class.java)) },
                            icon = { Text("\uD83D\uDEE0\uFE0F") }, label = { Text("Permissions") }
                        )
                    }
                }
            ) { inner ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Title
                    Text(
                        "Moncchichi",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))

                    // State row + Bluetooth indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val (label, color) = when (state) {
                            G1ConnectionState.CONNECTED -> "Connected" to Color(0xFF2ECC71)
                            G1ConnectionState.CONNECTING -> "Connecting…" to Color(0xFFF1C40F)
                            G1ConnectionState.RECONNECTING -> "Reconnecting…" to Color(0xFFF39C12)
                            else -> "Disconnected" to Color(0xFFE74C3C)
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(label) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.18f))
                        )
                        Spacer(Modifier.width(12.dp))
                        AssistChip(
                            onClick = { /* open BT settings? */ },
                            label = { Text(if (btOn) "Bluetooth: ON" else "Bluetooth: OFF") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (btOn) Color(0xFF1ABC9C).copy(alpha = 0.18f) else Color(0xFFE74C3C).copy(alpha = 0.18f)
                            )
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                pairingLog = listOf("Manual reconnect…")
                                troubleshooting = listOf(
                                    "Bluetooth " + if (btOn) "✓" else "✗",
                                    "Scanning …",
                                    "Handshake –"
                                )
                                displayService?.requestReconnect()
                            },
                            enabled = btOn,
                        ) { Text(if (state == G1ConnectionState.CONNECTED) "Reconnect" else "Try Again") }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Pairing progress (live log)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Pairing progress", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            pairingLog.takeLast(6).forEach { line ->
                                Text("• $line", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Troubleshooting (dynamic checklist)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Smart troubleshooting", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            val items = troubleshooting.ifEmpty {
                                listOf(
                                    "Bluetooth " + if (btOn) "✓" else "✗",
                                    "Scanning …",
                                    "Handshake –"
                                )
                            }
                            items.forEach { Text("• $it") }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Discovered devices table (scrollable)
                    Text("Discovered devices", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF121212))
                                .padding(8.dp)
                        ) {
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text("Name", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.SemiBold)
                                    Text("Address", modifier = Modifier.weight(0.4f), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            items(devices, key = { it.address }) { d ->
                                DeviceRow(d) {
                                    // Manual connect
                                    pairingLog = listOf("Connect to ${d.name ?: d.address}")
                                    displayService?.connect(d.address)
                                }
                            }
                        }
                    }

                    // Scan controls
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(
                            onClick = {
                                if (btOn) {
                                    scanner.clear()
                                    scanner.start()
                                    pairingLog = pairingLog + "Scanning for devices…"
                                }
                            },
                            enabled = btOn
                        ) { Text("Scan") }
                        OutlinedButton(onClick = { scanner.stop() }) { Text("Stop scan") }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceRow(device: DiscoveredDevice, onConnect: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onConnect() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(device.name ?: "(unknown)", modifier = Modifier.weight(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.address, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Divider()
    }
}
