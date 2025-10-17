package com.loopermallee.moncchichi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestActivity : ComponentActivity() {
    private val logger by lazy { MoncchichiLogger(this) }
    private val logEntries = MutableStateFlow<List<MoncchichiLogger.LogEvent>>(emptyList())

    private var serviceBound = false
    private var displayService: G1DisplayService? = null
    private lateinit var scanner: BluetoothScanner
    private val binderState = MutableStateFlow<G1DisplayService.LocalBinder?>(null)
    private val hubState = MutableStateFlow<G1ServiceCommon.State?>(null)
    private var serviceClient: G1ServiceClient? = null

    private val bluetoothState = MutableStateFlow(false)
    private val bluetoothSupportedState = MutableStateFlow(true)
    private val permissionState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val cachedDeviceState = MutableStateFlow<CachedDevice?>(null)
    private val previouslyPairedState = MutableStateFlow(false)
    private val bondedDevicesState = MutableStateFlow<Set<String>>(emptySet())
    private val pairingStatusState = MutableStateFlow<Map<String, PairingUiStatus>>(emptyMap())

    private val prefs by lazy { getSharedPreferences("g1_pairing", Context.MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? G1DisplayService.LocalBinder
            displayService = localBinder?.getService()
            binderState.value = localBinder
            serviceBound = true
            lifecycleScope.launch {
                val address = localBinder?.lastDeviceAddress()
                val name = displayService?.getConnectedDeviceName()
                if (address != null) {
                    setCachedDevice(address, name)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            displayService = null
            binderState.value = null
        }
    }

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionSnapshot()
        refreshBondedDevices()
        if (!hasCriticalPermissions()) {
            releaseService()
        }
        ensureService()
    }

    private val enableBluetoothLauncher = registerForActivityResult(StartActivityForResult()) { }

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var bondReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BluetoothScanner(this)
        loadCachedDevice()
        updateBluetoothSupport()
        updatePermissionSnapshot()
        refreshBondedDevices()
        registerBluetoothReceiver()
        registerBondReceiver()
        setContent { MaterialTheme { MoncchichiHome() } }
        ensurePermissions()
        lifecycleScope.launch {
            MoncchichiLogger.logEvents().collect { event ->
                logEntries.update { entries -> (entries + event).takeLast(LOG_BUFFER_LIMIT) }
            }
        }
        lifecycleScope.launch {
            val client = G1ServiceClient.open(applicationContext)
            serviceClient = client
            client?.state?.collect { snapshot ->
                hubState.value = snapshot
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothSupport()
        updatePermissionSnapshot()
        refreshBondedDevices()
        ensureService()
    }

    override fun onDestroy() {
        scanner.close()
        bluetoothReceiver?.let { unregisterReceiver(it) }
        bondReceiver?.let { unregisterReceiver(it) }
        bluetoothReceiver = null
        bondReceiver = null
        releaseService()
        serviceClient?.close()
        serviceClient = null
        super.onDestroy()
    }

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            bluetoothState.value = true
                            logger.i(UI_TAG, "Bluetooth enabled")
                            ensureService()
                            refreshBondedDevices()
                            displayService?.requestNearbyRescan()
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            bluetoothState.value = false
                            logger.w(UI_TAG, "Bluetooth disabled")
                            pairingStatusState.value = emptyMap()
                        }
                    }
                }
            }
        }
        bluetoothReceiver = receiver
        registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun registerBondReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                val address = device.address
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDING -> {
                        pairingStatusState.update { it + (address to PairingUiStatus.PAIRING) }
                        logger.i(UI_TAG, "Pairing with ${device.name ?: address}")
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        pairingStatusState.update { it + (address to PairingUiStatus.PAIRED) }
                        logger.i(UI_TAG, "Connected successfully to ${device.name ?: address}")
                        setCachedDevice(address, device.name)
                        refreshBondedDevices()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        pairingStatusState.update { it + (address to PairingUiStatus.FAILED) }
                        logger.e(UI_TAG, "Failed to connect to ${device.name ?: address}")
                    }
                }
            }
        }
        bondReceiver = receiver
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun updateBluetoothSupport() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothSupportedState.value = adapter != null
        bluetoothState.value = adapter?.isEnabled == true
        if (adapter == null) {
            releaseService()
        }
    }

    private fun ensureService() {
        if (!bluetoothSupportedState.value) return
        if (!hasCriticalPermissions()) return
        val intent = Intent(this, G1DisplayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (!serviceBound) bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun hasCriticalPermissions(): Boolean {
        val snapshot = permissionState.value
        return snapshot[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            snapshot[Manifest.permission.BLUETOOTH_SCAN] == true
    }

    private fun ensurePermissions() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) {
            releaseService()
            permissionRequester.launch(missing.toTypedArray())
        } else {
            ensureService()
        }
    }

    private fun updatePermissionSnapshot() {
        permissionState.value = requiredPermissions().associateWith { hasPermission(it) }
    }

    private fun refreshBondedDevices() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        bondedDevicesState.value = adapter.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
    }

    private fun releaseService() {
        if (serviceBound) {
            runCatching { unbindService(connection) }
            serviceBound = false
            displayService = null
            binderState.value = null
        }
    }

    private fun hasPermission(perm: String): Boolean {
        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

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

    private fun loadCachedDevice() {
        val address = prefs.getString(KEY_DEVICE_ADDRESS, null)
        val name = prefs.getString(KEY_DEVICE_NAME, null)
        if (address != null) {
            cachedDeviceState.value = CachedDevice(address, name)
        }
        previouslyPairedState.value = prefs.getBoolean(KEY_PREVIOUSLY_PAIRED, address != null)
    }

    private fun setCachedDevice(address: String, name: String?, markAsPaired: Boolean = true) {
        val trimmedName = name?.takeIf { it.isNotBlank() }
        val current = cachedDeviceState.value
        if (current?.address == address && current.name == trimmedName && (!markAsPaired || previouslyPairedState.value)) return
        cachedDeviceState.value = CachedDevice(address, trimmedName)
        if (markAsPaired) {
            pairingStatusState.update { it + (address to PairingUiStatus.PAIRED) }
            prefs.edit()
                .putString(KEY_DEVICE_ADDRESS, address)
                .putString(KEY_DEVICE_NAME, trimmedName)
                .putBoolean(KEY_PREVIOUSLY_PAIRED, true)
                .apply()
            previouslyPairedState.value = true
        }
    }

    private fun requestDeviceBond(device: DiscoveredDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            ensurePermissions()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logger.e(UI_TAG, "Device not found: ${device.address}")
            return
        }
        val remote = adapter.getRemoteDevice(device.address)
        if (remote.bondState == BluetoothDevice.BOND_BONDED) {
            logger.i(UI_TAG, "Already paired with ${remote.name ?: remote.address}")
            setCachedDevice(remote.address, remote.name)
            refreshBondedDevices()
            return
        }
        pairingStatusState.update { it + (device.address to PairingUiStatus.PAIRING) }
        logger.i(UI_TAG, "Attempting connection to ${device.name ?: device.address}")
        remote.createBond()
    }

    private fun onDeviceSelected(device: DiscoveredDevice) {
        lifecycleScope.launch {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                ensurePermissions()
                return@launch
            }
            setCachedDevice(device.address, device.name, markAsPaired = false)
            logger.i(UI_TAG, "Attempting connection to ${device.name ?: device.address}")
            displayService?.connect(device.address)
        }
    }

    private fun rescanNearby() {
        lifecycleScope.launch {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return@launch
            if (!bluetoothState.value) return@launch
            logger.i(UI_TAG, "Searching for devices…")
            scanner.clear()
            scanner.start()
            displayService?.requestNearbyRescan()
        }
    }

    private fun requestReconnect() {
        lifecycleScope.launch {
            logger.i(UI_TAG, "Manual reconnect requested")
            displayService?.requestReconnect()
        }
    }

    private fun handleConnectAction() {
        when {
            !bluetoothSupportedState.value -> {
                logger.w(UI_TAG, "Bluetooth not supported on this device")
            }
            !bluetoothState.value -> {
                logger.w(UI_TAG, "Bluetooth is OFF. Prompting user to enable.")
                Toast.makeText(this, "Please enable Bluetooth to continue.", Toast.LENGTH_SHORT).show()
                requestEnableBluetooth()
            }
            !hasCriticalPermissions() -> {
                logger.w(UI_TAG, "Bluetooth permissions missing. Requesting…")
                ensurePermissions()
            }
            else -> {
                val cached = cachedDeviceState.value
                if (cached == null) {
                    logger.w(UI_TAG, "Device not found")
                    rescanNearby()
                } else {
                    lifecycleScope.launch {
                        logger.i(UI_TAG, "Attempting connection to ${cached.name ?: cached.address}")
                        displayService?.connect(cached.address)
                            ?: requestReconnect()
                    }
                }
            }
        }
    }

    private fun requestEnableBluetooth() {
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    @Composable
    private fun MoncchichiHome() {
        val binder by binderState.collectAsState()
        val serviceSnapshot by hubState.collectAsState()
        val devices by scanner.devices.collectAsState()
        val connectionState by collectBinderState(binder?.connectionStates, G1ConnectionState.DISCONNECTED)
        val readiness by collectBinderState(binder?.readiness, false)
        val bluetoothOn by bluetoothState.collectAsState()
        val bluetoothSupported by bluetoothSupportedState.collectAsState()
        val cachedDevice by cachedDeviceState.collectAsState()
        val previouslyPaired by previouslyPairedState.collectAsState()
        val bondedDevices by bondedDevicesState.collectAsState()
        val pairingStatuses by pairingStatusState.collectAsState()
        val permissions by permissionState.collectAsState()
        val logs by logEntries.collectAsState()

        val connectedGlasses = remember(serviceSnapshot) {
            serviceSnapshot?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED } ?: emptyList()
        }

        LaunchedEffect(connectionState, bluetoothOn) {
            if (connectionState == G1ConnectionState.CONNECTED && bluetoothOn) {
                val address = binder?.lastDeviceAddress()
                val name = displayService?.getConnectedDeviceName()
                if (address != null) {
                    setCachedDevice(address, name ?: cachedDevice?.name)
                }
            }
        }

        val hasScanPermission = permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        val hasConnectPermission = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        val hasCriticalPermissions = hasScanPermission && hasConnectPermission

        LaunchedEffect(bluetoothOn, hasScanPermission, bluetoothSupported) {
            if (bluetoothSupported && bluetoothOn && hasScanPermission) {
                rescanNearby()
            } else {
                scanner.stop()
            }
        }

        var previousConnectionState by remember { mutableStateOf<G1ConnectionState?>(null) }
        var lastFailure by remember { mutableStateOf(false) }
        LaunchedEffect(connectionState) {
            val previous = previousConnectionState
            if (connectionState == G1ConnectionState.CONNECTED && previous != G1ConnectionState.CONNECTED) {
                lastFailure = false
                logger.i(UI_TAG, "Connected successfully")
            }
            if (connectionState == G1ConnectionState.DISCONNECTED &&
                (previous == G1ConnectionState.CONNECTING || previous == G1ConnectionState.RECONNECTING)
            ) {
                lastFailure = true
                logger.e(UI_TAG, "Failed to connect")
            }
            if (connectionState == G1ConnectionState.CONNECTING || connectionState == G1ConnectionState.RECONNECTING) {
                lastFailure = false
            }
            previousConnectionState = connectionState
        }

        val buttonUi = when {
            connectionState == G1ConnectionState.CONNECTED -> ConnectButtonUi("Connected ✅", Color(0xFF2ECC71), true)
            connectionState == G1ConnectionState.CONNECTING || connectionState == G1ConnectionState.RECONNECTING ->
                ConnectButtonUi("Connecting…", Color(0xFFFFC107), false)
            lastFailure -> ConnectButtonUi("Failed ❌", Color(0xFFE53935), true)
            else -> ConnectButtonUi("Connect", MaterialTheme.colorScheme.primary, true)
        }

        val evenDevice = remember(cachedDevice) {
            val name = cachedDevice?.name.orEmpty()
            name.contains("even", ignoreCase = true) || name.contains("g1", ignoreCase = true)
        }

        val contextMessage = when {
            !bluetoothSupported -> "Bluetooth unavailable on this device."
            !bluetoothOn -> "Bluetooth is OFF. Please enable to continue."
            !hasCriticalPermissions -> "Bluetooth permissions are required to connect."
            connectionState == G1ConnectionState.CONNECTED -> "Connected to ${cachedDevice?.name ?: cachedDevice?.address ?: "Even G1"}."
            lastFailure -> "Failed to connect. Tap Connect to retry."
            previouslyPaired -> "Reconnecting to Even G1…"
            cachedDevice != null && !evenDevice -> "Not a supported device."
            else -> "Searching for devices…"
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Moncchichi BLE Hub",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = contextMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0BEC5)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when (connectionState) {
                                G1ConnectionState.CONNECTED -> "Status: Connected ✅"
                            G1ConnectionState.CONNECTING -> "Status: Connecting…"
                            G1ConnectionState.RECONNECTING -> "Status: Reconnecting…"
                            else -> "Status: Disconnected"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when (connectionState) {
                            G1ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            G1ConnectionState.CONNECTING, G1ConnectionState.RECONNECTING -> Color(0xFFFFC107)
                            else -> Color(0xFFE53935)
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (readiness) "Service ready" else "Service initializing…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (readiness) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    )
                }
                BluetoothIndicator(
                    bluetoothSupported = bluetoothSupported,
                    bluetoothOn = bluetoothOn
                )
            }

            Text(
                text = "Status",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val connectedName = cachedDevice?.name ?: cachedDevice?.address ?: "None"
                    val statusLabel = when (connectionState) {
                        G1ConnectionState.CONNECTED -> "Connected"
                        G1ConnectionState.CONNECTING -> "Connecting"
                        G1ConnectionState.RECONNECTING -> "Reconnecting"
                        else -> "Disconnected"
                    }
                    Text(
                        text = "Connected to: $connectedName",
                        color = if (connectionState == G1ConnectionState.CONNECTED) Color(0xFF4CAF50) else Color.LightGray,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Status: $statusLabel",
                        color = Color.White
                    )
                    Text(
                        text = "Service: ${if (readiness) "Ready" else "Initializing"}",
                        color = Color(0xFFB0BEC5)
                    )
                }
            }

            NearbyDevicesSection(
                devices = devices,
                bluetoothOn = bluetoothOn,
                hasScanPermission = hasScanPermission,
                pairingStatuses = pairingStatuses,
                bondedDevices = bondedDevices,
                cachedDevice = cachedDevice,
                previouslyPaired = previouslyPaired,
                onSelect = { onDeviceSelected(it) },
                onPair = { requestDeviceBond(it) },
                onRescan = { rescanNearby() },
                modifier = Modifier.fillMaxWidth()
            )

            if (connectedGlasses.isNotEmpty()) {
                G1SummaryBox(connectedGlasses)
            }

            StatusConsole(
                logs = logs,
                contextFallback = contextMessage,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { handleConnectAction() },
                enabled = buttonUi.enabled && bluetoothSupported && bluetoothOn && hasCriticalPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonUi.color,
                    contentColor = if (buttonUi.color.luminance() < 0.5f) Color.White else Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(50.dp)
            ) {
                Text(buttonUi.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } // end of Column
    } // end of Box
} // end of HubScreen()

    @Composable
    private fun BluetoothIndicator(bluetoothSupported: Boolean, bluetoothOn: Boolean, modifier: Modifier = Modifier) {
        val (label, tint) = when {
            !bluetoothSupported -> "Unsupported" to MaterialTheme.colorScheme.error
            bluetoothOn -> "Bluetooth ON" to Color(0xFF4CAF50)
            else -> "Bluetooth OFF" to MaterialTheme.colorScheme.error
        }
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = tint
                )
                Text(label, color = tint, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun StatusConsole(
        logs: List<MoncchichiLogger.LogEvent>,
        contextFallback: String,
        modifier: Modifier = Modifier,
    ) {
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        val scrollState = rememberScrollState()
        val displayLogs = remember(logs) { logs.takeLast(50) }
        LaunchedEffect(displayLogs.size) {
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Status console",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 300.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (displayLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contextFallback,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayLogs.forEach { event ->
                            val timestamp = formatter.format(Date(event.timestamp))
                            val color = when (event.priority) {
                                android.util.Log.ERROR -> Color(0xFFE53935)
                                android.util.Log.WARN -> Color(0xFFFFA000)
                                android.util.Log.INFO -> Color.White
                                else -> Color(0xFFB0BEC5)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "[$timestamp] ${event.tag} ${event.message}",
                                    color = color,
                                    fontSize = 13.sp
                                )
                                event.throwable?.let {
                                    Text(
                                        text = it,
                                        color = color,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NearbyDevicesSection(
        devices: List<DiscoveredDevice>,
        bluetoothOn: Boolean,
        hasScanPermission: Boolean,
        pairingStatuses: Map<String, PairingUiStatus>,
        bondedDevices: Set<String>,
        cachedDevice: CachedDevice?,
        previouslyPaired: Boolean,
        onSelect: (DiscoveredDevice) -> Unit,
        onPair: (DiscoveredDevice) -> Unit,
        onRescan: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val sortedDevices = remember(devices) {
            devices.sortedWith(
                compareByDescending<DiscoveredDevice> { it.name?.contains("Even G1", ignoreCase = true) == true }
            )
        }
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF242424))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nearby devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    OutlinedButton(onClick = onRescan) {
                        Text("Rescan")
                    }
                }

                when {
                    !bluetoothOn -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Bluetooth is OFF. Please enable to continue.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                                color = Color(0xFFB0BEC5)
                            )
                        }
                    }
                    !hasScanPermission -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Bluetooth Scan permission required to discover devices.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                                color = Color(0xFFE57373)
                            )
                        }
                    }
                    sortedDevices.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Searching for devices…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB0BEC5)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sortedDevices, key = { it.address }) { device ->
                                val isCached = cachedDevice?.address == device.address
                                val bonded = bondedDevices.contains(device.address)
                                val status = pairingStatuses[device.address]
                                    ?: if (bonded || (isCached && previouslyPaired)) PairingUiStatus.PAIRED else PairingUiStatus.IDLE
                                DeviceRow(
                                    device = device,
                                    isCached = isCached,
                                    status = status,
                                    isBonded = bonded,
                                    onSelect = onSelect,
                                    onPair = onPair
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceRow(
        device: DiscoveredDevice,
        isCached: Boolean,
        status: PairingUiStatus,
        isBonded: Boolean,
        onSelect: (DiscoveredDevice) -> Unit,
        onPair: (DiscoveredDevice) -> Unit,
    ) {
        val statusLabel = when (status) {
            PairingUiStatus.PAIRING -> "Pairing…"
            PairingUiStatus.PAIRED -> "Paired"
            PairingUiStatus.FAILED -> "Failed"
            PairingUiStatus.IDLE -> null
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(device) }
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = device.name ?: "(unknown)",
                        color = Color.White,
                        fontWeight = if (isCached) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "MAC: ${device.address}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                    statusLabel?.let { label ->
                        Text(
                            text = label,
                            color = when (status) {
                                PairingUiStatus.FAILED -> Color(0xFFE57373)
                                PairingUiStatus.PAIRING -> Color(0xFFFFC107)
                                PairingUiStatus.PAIRED -> Color(0xFF4CAF50)
                                else -> Color(0xFFB0BEC5)
                            },
                            fontSize = 12.sp
                        )
                    }
                }
                IconButton(
                    onClick = { onPair(device) },
                    enabled = status != PairingUiStatus.PAIRING
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Pair",
                        tint = if (isBonded || status == PairingUiStatus.PAIRED) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                    )
                }
            }
        }
    }

    @Composable
    private fun G1SummaryBox(glasses: List<G1ServiceCommon.Glasses>) {
        val right = glasses.firstOrNull { it.name?.contains("right", ignoreCase = true) == true }
        val left = glasses.firstOrNull { it.name?.contains("left", ignoreCase = true) == true }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "G1 Glasses Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                BatteryRow(label = "Even G1 Right", percent = right?.batteryPercentage)
                BatteryRow(label = "Even G1 Left", percent = left?.batteryPercentage)
            }
        }
    }

    @Composable
    private fun BatteryRow(label: String, percent: Int?) {
        val (color, badge) = when {
            percent == null || percent < 0 -> MaterialTheme.colorScheme.onSurfaceVariant to "--%"
            percent >= 70 -> Color(0xFF4CAF50) to "$percent%"
            percent >= 30 -> Color(0xFFFFC107) to "$percent%"
            else -> MaterialTheme.colorScheme.error to "$percent%"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("🔋 $badge", color = color, style = MaterialTheme.typography.bodyMedium)
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

    data class CachedDevice(val address: String, val name: String?)

    data class ConnectButtonUi(val label: String, val color: Color, val enabled: Boolean)

    enum class PairingUiStatus { IDLE, PAIRING, PAIRED, FAILED }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_DEVICE_NAME = "last_device_name"
        private const val KEY_PREVIOUSLY_PAIRED = "previously_paired"
        private const val LOG_BUFFER_LIMIT = 200
        private const val UI_TAG = "[UI]"
    }
}
