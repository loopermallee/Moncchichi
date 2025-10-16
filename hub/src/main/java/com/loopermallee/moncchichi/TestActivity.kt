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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.DiscoveredDevice
import com.loopermallee.moncchichi.bluetooth.G1ConnectionState
import com.loopermallee.moncchichi.service.G1DisplayService
import io.texne.g1.basis.client.G1ServiceClient
import io.texne.g1.basis.client.G1ServiceCommon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TestActivity : ComponentActivity() {
    private var serviceBound = false
    private var displayService: G1DisplayService? = null
    private lateinit var scanner: BluetoothScanner
    private val binderState = MutableStateFlow<G1DisplayService.LocalBinder?>(null)
    private val hubState = MutableStateFlow<G1ServiceCommon.State?>(null)
    private var serviceClient: G1ServiceClient? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? G1DisplayService.LocalBinder
            displayService = b?.getService()
            binderState.value = b
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
            binderState.value = null
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
                // recomposition reads scanner.isBluetoothOn()
            }
        }
        registerReceiver(receiver, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        bluetoothReceiver = receiver
        setContent { MoncchichiHome() }
        ensureService()
        ensurePermissions()
        lifecycleScope.launch {
            val client = G1ServiceClient.open(applicationContext)
            serviceClient = client
            client?.state?.collect { snapshot ->
                hubState.value = snapshot
            }
        }
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
        binderState.value = null
        serviceClient?.close()
        serviceClient = null
        super.onDestroy()
    }

    // ---------------- UI (Compose) ----------------
    @Composable
    private fun MoncchichiHome() {
        val binder by binderState.collectAsState()
        val serviceSnapshot by hubState.collectAsState()
        val devices by scanner.devices.collectAsState()
        val connectionState by collectBinderState(binder?.connectionStates, G1ConnectionState.DISCONNECTED)
        val readiness by collectBinderState(binder?.readiness, false)
        val rssi by collectBinderState(binder?.rssi(), null)
        var statusMessage by remember { mutableStateOf("Looking for Even G1…") }
        var attemptedAutoPair by remember { mutableStateOf(false) }

        val connectedGlasses = remember(serviceSnapshot) {
            serviceSnapshot?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
        }
        val connectedBattery = connectedGlasses?.batteryPercentage
        val connectedLabel = connectedGlasses?.name ?: connectedGlasses?.id

        val bluetoothOn by produceState(initialValue = scanner.isBluetoothOn()) {
            while (isActive) {
                val latest = scanner.isBluetoothOn()
                if (value != latest) {
                    value = latest
                }
                delay(1_000)
            }
        }

        LaunchedEffect(bluetoothOn) {
            if (bluetoothOn) {
                scanner.start()
            } else {
                scanner.stop()
            }
        }

        LaunchedEffect(connectionState, connectedLabel, bluetoothOn) {
            statusMessage = when (connectionState) {
                G1ConnectionState.CONNECTED -> connectedLabel?.let { "Linked to $it" } ?: "Linked to Even G1"
                G1ConnectionState.CONNECTING -> "Establishing secure session…"
                G1ConnectionState.RECONNECTING -> "Reconnecting to glasses…"
                else -> if (bluetoothOn) "Looking for Even G1…" else "Turn Bluetooth on to begin pairing."
            }
            if (connectionState == G1ConnectionState.DISCONNECTED) {
                attemptedAutoPair = false
            }
        }

        LaunchedEffect(devices, connectionState, bluetoothOn, attemptedAutoPair) {
            if (bluetoothOn && connectionState == G1ConnectionState.DISCONNECTED && !attemptedAutoPair) {
                devices.firstOrNull { (it.name ?: "").startsWith("Even G1") }?.let { device ->
                    attemptedAutoPair = true
                    statusMessage = "Pairing with ${device.name ?: device.address}"
                    onDeviceSelected(device.address)
                }
            }
        }

        MaterialTheme {
            Scaffold { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "Soul Tether Hub",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        ConnectionStatusPill(connectionState)
                        Spacer(Modifier.height(8.dp))
                        BluetoothStateIndicator(bluetoothOn)
                        if (connectedLabel != null) {
                            Spacer(Modifier.height(8.dp))
                            BatteryIndicator(connectedBattery, connectedLabel)
                        }
                        Spacer(Modifier.height(4.dp))
                        SignalStrengthIndicator(rssi)
                        Spacer(Modifier.height(16.dp))
                        StatusMessageCard(statusMessage, readiness)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Nearby devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        DeviceTable(
                            devices = devices,
                            onSelect = { device ->
                                attemptedAutoPair = true
                                statusMessage = "Connecting to ${device.name ?: device.address}"
                                onDeviceSelected(device.address)
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        ControlButtons(bluetoothOn)
                        if (!bluetoothOn) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Enable Bluetooth to scan for Even G1 glasses.",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onDeviceSelected(address: String) {
        lifecycleScope.launch {
            displayService?.connect(address)
        }
    }

    @Composable
    private fun ConnectionStatusPill(state: G1ConnectionState) {
        val (label, color) = when (state) {
            G1ConnectionState.CONNECTED -> "Connected" to Color(0xFF2ECC71)
            G1ConnectionState.CONNECTING -> "Connecting…" to Color(0xFFF1C40F)
            G1ConnectionState.RECONNECTING -> "Reconnecting…" to Color(0xFFF39C12)
            else -> "Disconnected" to Color(0xFFE74C3C)
        }
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = color.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    }

    @Composable
    private fun BluetoothStateIndicator(isOn: Boolean) {
        val color = if (isOn) Color(0xFF26A69A) else Color(0xFFE74C3C)
        val label = if (isOn) "Bluetooth ON" else "Bluetooth OFF"
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    @Composable
    private fun BatteryIndicator(percent: Int?, deviceLabel: String) {
        val (color, badge) = when {
            percent == null -> MaterialTheme.colorScheme.onSurfaceVariant to "Battery: --"
            percent >= 70 -> Color(0xFF4CAF50) to "Battery: $percent%"
            percent >= 30 -> Color(0xFFFFC107) to "Battery: $percent%"
            else -> Color(0xFFF44336) to "Battery: $percent%"
        }
        Text(
            text = "🔋 $badge • $deviceLabel",
            color = color,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun SignalStrengthIndicator(rssi: Int?) {
        val label = rssi?.let { "Signal: ${it} dBm" } ?: "Signal: -- dBm"
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    private fun StatusMessageCard(message: String, ready: Boolean) {
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (ready) "Service ready" else "Waiting for service…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun DeviceTable(
        devices: List<DiscoveredDevice>,
        onSelect: (DiscoveredDevice) -> Unit,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No devices detected yet",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap scan to refresh once your Even G1 is nearby.",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device = device, onSelect = onSelect)
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceRow(
        device: DiscoveredDevice,
        onSelect: (DiscoveredDevice) -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(device) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "(unknown)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(onClick = { onSelect(device) }, label = { Text("Pair") })
            }
        }
        Divider()
    }

    @Composable
    private fun ControlButtons(bluetoothOn: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (bluetoothOn) {
                        scanner.clear()
                        scanner.start()
                        displayService?.requestNearbyRescan()
                    }
                },
                enabled = bluetoothOn,
                modifier = Modifier.weight(1f)
            ) {
                Text("Scan")
            }
            OutlinedButton(
                onClick = { displayService?.requestReconnect() },
                enabled = bluetoothOn,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reconnect")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { startActivity(Intent(this@TestActivity, PermissionsActivity::class.java)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Permissions")
        }
    }

    @Composable
    private fun <T> collectBinderState(flow: StateFlow<T>?, default: T): State<T> {
        return if (flow != null) {
            flow.collectAsState(initial = flow.value)
        } else {
            remember { mutableStateOf(default) }
        }
    }
}
