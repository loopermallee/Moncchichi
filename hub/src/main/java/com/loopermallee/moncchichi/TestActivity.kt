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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
        setContent {
            MaterialTheme { MainScaffold() }
        }
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
        val missing = requiredPermissions().filter {
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
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
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
    }

    @Composable
    private fun <T> collectBinderState(flow: StateFlow<T>?, default: T): State<T> {
        return if (flow != null) {
            flow.collectAsState(initial = flow.value)
        } else {
            remember { mutableStateOf(default) }
        }
    }

    // --------------------------- Permissions Center with Smart Checklist + Refresh Indicator ---------------------------

    data class PermissionItem(
        val name: String,
        val label: String,
        val granted: Boolean = false,
        val statusText: String = "⏳ Checking..."
    )

    private fun requiredPermissions(): List<String> {
        val base = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base
    }

    @Composable
    private fun PermissionsCenter(onGrantAll: () -> Unit) {
        val context = this
        var refreshing by remember { mutableStateOf(true) }
        val permissions = remember {
            mutableStateListOf<PermissionItem>().apply {
                addAll(requiredPermissions().map {
                    PermissionItem(it, permissionLabel(it), hasPermission(context, it))
                })
            }
        }

        LaunchedEffect(refreshing) {
            if (refreshing) {
                val latest = requiredPermissions()
                if (permissions.size != latest.size || permissions.map { it.name } != latest) {
                    permissions.clear()
                    permissions.addAll(latest.map { perm ->
                        PermissionItem(perm, permissionLabel(perm), hasPermission(context, perm))
                    })
                }
                latest.forEachIndexed { index, perm ->
                    val current = permissions[index]
                    permissions[index] = current.copy(statusText = "⏳ Checking...")
                    delay(250)
                    val grantedNow = hasPermission(context, perm)
                    val status = if (grantedNow) "✅ Granted" else "❌ Missing"
                    permissions[index] = current.copy(granted = grantedNow, statusText = status)
                }
                refreshing = false
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                "App Permissions",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (refreshing) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Checking permissions...")
                }
            } else {
                permissions.forEach { item ->
                    val showRow = item.statusText != "⏳ Checking..."
                    AnimatedVisibility(visible = showRow, enter = fadeIn(), exit = fadeOut()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.label,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp
                            )
                            Text(
                                text = item.statusText,
                                color = when {
                                    item.statusText.contains("✅") -> Color(0xFF4CAF50)
                                    item.statusText.contains("❌") -> Color(0xFFF44336)
                                    else -> Color.Gray
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        onGrantAll()
                        refreshing = true
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Grant All Permissions")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { refreshing = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("🔄 Recheck")
                }
            }
        }
    }

    private fun hasPermission(context: Context, perm: String): Boolean {
        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionLabel(perm: String): String = when (perm) {
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location Access"
        Manifest.permission.POST_NOTIFICATIONS -> "Post Notifications"
        else -> perm.substringAfterLast('.')
    }

    private fun requestAllPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionRequester.launch(missing.toTypedArray())
        }
    }

    // --------------------------- Navigation Scaffold ---------------------------

    @Composable
    private fun MainScaffold() {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            bottomBar = { BottomNavigationBar(navController, currentRoute) }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                NavHost(navController = navController, startDestination = "pairing") {
                    composable("pairing") { MoncchichiHome() }
                    composable("permissions") { PermissionsCenter { requestAllPermissions() } }
                }
            }
        }
    }

    @Composable
    private fun BottomNavigationBar(navController: NavHostController, currentRoute: String?) {
        NavigationBar {
            NavigationBarItem(
                selected = currentRoute == "pairing",
                onClick = {
                    navController.navigate("pairing") {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Pair") },
                label = { Text("Pairing") }
            )
            NavigationBarItem(
                selected = currentRoute == "permissions",
                onClick = {
                    navController.navigate("permissions") {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Permissions") },
                label = { Text("Permissions") }
            )
        }
    }
}
