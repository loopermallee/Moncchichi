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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TestActivity : ComponentActivity() {
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
        setContent { MaterialTheme { MainScaffold() } }
        ensurePermissions()
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
                            ensureService()
                            refreshBondedDevices()
                            displayService?.requestNearbyRescan()
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            bluetoothState.value = false
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
                    BluetoothDevice.BOND_BONDING -> pairingStatusState.update { it + (address to PairingUiStatus.PAIRING) }
                    BluetoothDevice.BOND_BONDED -> {
                        pairingStatusState.update { it + (address to PairingUiStatus.PAIRED) }
                        setCachedDevice(address, device.name)
                        refreshBondedDevices()
                    }
                    BluetoothDevice.BOND_NONE -> pairingStatusState.update { it + (address to PairingUiStatus.FAILED) }
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
        return (snapshot[Manifest.permission.BLUETOOTH_CONNECT] == true && snapshot[Manifest.permission.BLUETOOTH_SCAN] == true)
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

    private fun requestAllPermissions() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionRequester.launch(missing.toTypedArray())
        }
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
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val remote = adapter.getRemoteDevice(device.address)
        if (remote.bondState == BluetoothDevice.BOND_BONDED) {
            setCachedDevice(remote.address, remote.name)
            refreshBondedDevices()
            return
        }
        pairingStatusState.update { it + (device.address to PairingUiStatus.PAIRING) }
        remote.createBond()
    }

    private fun onDeviceSelected(device: DiscoveredDevice) {
        lifecycleScope.launch {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                ensurePermissions()
                return@launch
            }
            setCachedDevice(device.address, device.name, markAsPaired = false)
            displayService?.connect(device.address)
        }
    }

    private fun rescanNearby() {
        lifecycleScope.launch {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return@launch
            if (!bluetoothState.value) return@launch
            scanner.clear()
            scanner.start()
            displayService?.requestNearbyRescan()
        }
    }

    private fun requestReconnect() {
        lifecycleScope.launch {
            displayService?.requestReconnect()
        }
    }

    private fun requestEnableBluetooth() {
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    // ---------------- UI (Compose) ----------------

    @Composable
    private fun MoncchichiHome(onOpenPermissions: () -> Unit) {
        val binder by binderState.collectAsState()
        val serviceSnapshot by hubState.collectAsState()
        val devices by scanner.devices.collectAsState()
        val connectionState by collectBinderState(binder?.connectionStates, G1ConnectionState.DISCONNECTED)
        val readiness by collectBinderState(binder?.readiness, false)
        val rssi by collectBinderState(binder?.rssi(), null)
        val bluetoothOn by bluetoothState.collectAsState()
        val bluetoothSupported by bluetoothSupportedState.collectAsState()
        val cachedDevice by cachedDeviceState.collectAsState()
        val previouslyPaired by previouslyPairedState.collectAsState()
        val bondedDevices by bondedDevicesState.collectAsState()
        val pairingStatuses by pairingStatusState.collectAsState()
        val permissions by permissionState.collectAsState()
        val anyMissingPermissions = permissions.values.any { !it }

        val connectedGlasses = remember(serviceSnapshot) {
            serviceSnapshot?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }
        }
        val connectedBattery = connectedGlasses?.batteryPercentage
        val connectedLabel = connectedGlasses?.name ?: connectedGlasses?.id

        var showEnableBluetoothDialog by remember { mutableStateOf(false) }

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
        LaunchedEffect(bluetoothOn, hasScanPermission, bluetoothSupported) {
            if (bluetoothSupported && bluetoothOn && hasScanPermission) {
                scanner.start()
            } else {
                scanner.stop()
            }
        }

        val baseStatus = remember(
            bluetoothSupported,
            bluetoothOn,
            connectionState,
            previouslyPaired,
            cachedDevice,
            connectedLabel,
            binder
        ) {
            when {
                !bluetoothSupported -> "Bluetooth unavailable on this device."
                !bluetoothOn -> "Bluetooth is OFF — please enable to continue."
                connectionState == G1ConnectionState.CONNECTED -> {
                    val address = cachedDevice?.address ?: binder?.lastDeviceAddress()
                    val label = connectedLabel ?: cachedDevice?.name ?: "Even G1"
                    if (label.contains("Even Realities", ignoreCase = true) && address != null) {
                        "Linked to Even G1 ✅ [$address]"
                    } else {
                        "Linked to ${label}"
                    }
                }
                connectionState == G1ConnectionState.CONNECTING -> "Establishing secure session…"
                connectionState == G1ConnectionState.RECONNECTING -> {
                    val name = cachedDevice?.name ?: "Even G1"
                    val address = cachedDevice?.address ?: binder?.lastDeviceAddress()
                    if (address != null) {
                        "Reconnecting to $name [$address]"
                    } else {
                        "Reconnecting to Even G1…"
                    }
                }
                previouslyPaired -> "Reconnecting to Even G1…"
                else -> "Searching for glasses…"
            }
        }

        var retryCountdown by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(connectionState, bluetoothOn) {
            if (connectionState == G1ConnectionState.RECONNECTING && bluetoothOn) {
                retryCountdown = 10
                while (isActive && retryCountdown != null && retryCountdown!! > 0) {
                    delay(1_000)
                    retryCountdown = retryCountdown?.minus(1)
                }
            } else {
                retryCountdown = null
            }
        }

        var noDeviceWarning by remember { mutableStateOf(false) }
        LaunchedEffect(connectionState, devices, cachedDevice, bluetoothOn) {
            noDeviceWarning = false
            if (connectionState == G1ConnectionState.RECONNECTING && bluetoothOn) {
                val target = cachedDevice?.address
                if (target != null && devices.none { it.address == target }) {
                    delay(10_000)
                    if (connectionState == G1ConnectionState.RECONNECTING && bluetoothOn && devices.none { it.address == target }) {
                        noDeviceWarning = true
                    }
                }
            }
        }

        val statusMessage = when {
            noDeviceWarning -> "No known device nearby. Please ensure glasses are in pairing mode."
            retryCountdown != null && retryCountdown!! > 0 -> "Failed to connect — retrying in ${retryCountdown}s"
            else -> baseStatus
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
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    ConnectionHeader(
                        connectionState = connectionState,
                        bluetoothOn = bluetoothOn,
                        bluetoothSupported = bluetoothSupported,
                        batteryPercent = connectedBattery,
                        deviceLabel = connectedLabel ?: cachedDevice?.name,
                        rssi = rssi,
                        statusMessage = statusMessage,
                        serviceReady = readiness,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.2f, fill = true)
                    )
                    Spacer(Modifier.height(12.dp))
        DeviceSection(
            devices = devices,
            cachedDevice = cachedDevice,
            bondedDevices = bondedDevices,
            pairingStatuses = pairingStatuses,
            bluetoothOn = bluetoothOn,
            bluetoothSupported = bluetoothSupported,
            canScan = hasScanPermission,
            previouslyPaired = previouslyPaired,
            onSelect = { onDeviceSelected(it) },
            onPair = { requestDeviceBond(it) },
            onRescan = { rescanNearby() },
            modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.7f, fill = true)
                    )
                    Spacer(Modifier.height(12.dp))
                   ControlBar(
                        bluetoothOn = bluetoothOn,
                        hasCriticalPermissions = hasCriticalPermissions(),
                        missingPermissions = anyMissingPermissions,
                        onReconnect = {
                            if (bluetoothOn) {
                                requestReconnect()
                            } else {
                                showEnableBluetoothDialog = true
                            }
                        },
                        onOpenPermissions = onOpenPermissions,
                        onRequestPermissions = { requestAllPermissions() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.1f, fill = true)
                    )
                }
                if (showEnableBluetoothDialog) {
                    AlertDialog(
                        onDismissRequest = { showEnableBluetoothDialog = false },
                        confirmButton = {
                            Button(onClick = {
                                showEnableBluetoothDialog = false
                                requestEnableBluetooth()
                            }) { Text("Turn on Bluetooth") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showEnableBluetoothDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        title = { Text("Bluetooth required") },
                        text = { Text("Turn on Bluetooth to reconnect to Even G1.") }
                    )
                }
            }
        }
    }

    @Composable
    private fun ConnectionHeader(
        connectionState: G1ConnectionState,
        bluetoothOn: Boolean,
        bluetoothSupported: Boolean,
        batteryPercent: Int?,
        deviceLabel: String?,
        rssi: Int?,
        statusMessage: String,
        serviceReady: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Soul Tether Hub",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            ConnectionStatusPill(connectionState)
            BluetoothBanner(bluetoothOn = bluetoothOn, supported = bluetoothSupported)
            if (deviceLabel != null && batteryPercent != null) {
                BatteryIndicator(batteryPercent, deviceLabel)
            }
            SignalStrengthIndicator(rssi)
            StatusMessageCard(statusMessage, serviceReady)
        }
    }

    @Composable
    private fun BluetoothBanner(bluetoothOn: Boolean, supported: Boolean) {
        val (label, color) = when {
            !supported -> "Bluetooth unsupported" to Color(0xFFE57373)
            bluetoothOn -> "Bluetooth ON" to Color(0xFF26A69A)
            else -> "Bluetooth OFF" to Color(0xFFE74C3C)
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = color.copy(alpha = 0.15f),
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
    private fun DeviceSection(
        devices: List<DiscoveredDevice>,
        cachedDevice: CachedDevice?,
        bondedDevices: Set<String>,
        pairingStatuses: Map<String, PairingUiStatus>,
        bluetoothOn: Boolean,
        bluetoothSupported: Boolean,
        canScan: Boolean,
        previouslyPaired: Boolean,
        onSelect: (DiscoveredDevice) -> Unit,
        onPair: (DiscoveredDevice) -> Unit,
        onRescan: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onRescan, enabled = bluetoothOn && bluetoothSupported && canScan) {
                    Text("Rescan")
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!bluetoothSupported) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Bluetooth unavailable on this device.")
                    }
                } else if (!bluetoothOn) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Enable Bluetooth to scan for Even G1 glasses.",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else if (!canScan) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Bluetooth Scan permission required to discover devices.",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (devices.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Searching...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device Name", modifier = Modifier.weight(0.35f), fontWeight = FontWeight.SemiBold)
                            Text("MAC", modifier = Modifier.weight(0.3f), fontWeight = FontWeight.SemiBold)
                            Text("Pair", modifier = Modifier.weight(0.18f), fontWeight = FontWeight.SemiBold)
                            Text("Status", modifier = Modifier.weight(0.17f), fontWeight = FontWeight.SemiBold)
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .padding(vertical = 4.dp)
                        ) {
                            items(devices, key = { it.address }) { device ->
                                val isCached = cachedDevice?.address == device.address
                                val bonded = bondedDevices.contains(device.address)
                                val status = pairingStatuses[device.address]
                                    ?: if (bonded || (isCached && previouslyPaired)) PairingUiStatus.PAIRED else PairingUiStatus.IDLE
                                DeviceRow(
                                    device = device,
                                    isCached = isCached,
                                    status = status,
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
        onSelect: (DiscoveredDevice) -> Unit,
        onPair: (DiscoveredDevice) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(device) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(0.35f)) {
                Text(
                    text = device.name ?: "(unknown)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCached) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCached) {
                    Text("✅ Known device", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            }
            Text(
                text = device.address,
                modifier = Modifier.weight(0.3f),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { onPair(device) },
                enabled = status != PairingUiStatus.PAIRING,
                modifier = Modifier.weight(0.18f)
            ) {
                Text("Pair")
            }
            Text(
                text = when (status) {
                    PairingUiStatus.PAIRING -> "Pairing…"
                    PairingUiStatus.PAIRED -> "✅"
                    PairingUiStatus.FAILED -> "Retry"
                    PairingUiStatus.IDLE -> "—"
                },
                modifier = Modifier.weight(0.17f),
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun ControlBar(
        bluetoothOn: Boolean,
        hasCriticalPermissions: Boolean,
        missingPermissions: Boolean,
        onReconnect: () -> Unit,
        onOpenPermissions: () -> Unit,
        onRequestPermissions: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onReconnect,
                    enabled = bluetoothOn && hasCriticalPermissions,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reconnect")
                }
                OutlinedButton(
                    onClick = onOpenPermissions,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Permissions")
                }
            }
            if (missingPermissions) {
                OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant missing permissions")
                }
            }
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
    private fun BatteryIndicator(percent: Int?, deviceLabel: String) {
        val (color, badge) = when {
            percent == null -> MaterialTheme.colorScheme.onSurfaceVariant to "Battery: --"
            percent >= 70 -> Color(0xFF4CAF50) to "Battery: $percent%"
            percent >= 30 -> Color(0xFFFFC107) to "Battery: $percent%"
            else -> Color(0xFFF44336) to "Battery: $percent%"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "🔋 $badge • $deviceLabel",
                color = color,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
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
    private fun StatusMessageCard(message: String, serviceReady: Boolean) {
        Card(
            shape = RoundedCornerShape(20.dp),
            border = CardDefaults.outlinedCardBorder(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (serviceReady) "Service ready" else "Waiting for service…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

    // --------------------------- Permissions Center ---------------------------

    @Composable
    private fun PermissionsCenter(
        bluetoothOn: Boolean,
        bluetoothSupported: Boolean,
        permissionSnapshot: Map<String, Boolean>,
        onGrantAll: () -> Unit,
        onToggleBluetooth: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Permissions & Radios",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Bluetooth state", fontWeight = FontWeight.SemiBold)
                    if (!bluetoothSupported) {
                        Text("❌ Bluetooth unsupported on this device", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(
                            text = if (bluetoothOn) "✅ Enabled" else "❌ Disabled",
                            color = if (bluetoothOn) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = onToggleBluetooth) {
                            Text(if (bluetoothOn) "Open Bluetooth settings" else "Enable Bluetooth")
                        }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("App permissions", fontWeight = FontWeight.SemiBold)
                    requiredPermissions().forEach { perm ->
                        val granted = permissionSnapshot[perm] == true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(permissionLabel(perm))
                            Text(if (granted) "✅" else "❌", color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Button(onClick = onGrantAll, enabled = permissionSnapshot.values.any { !it }) {
                Text("Grant permissions")
            }
        }
    }

    private fun permissionLabel(perm: String): String = when (perm) {
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location Access"
        Manifest.permission.POST_NOTIFICATIONS -> "Post Notifications"
        else -> perm.substringAfterLast('.')
    }

    // --------------------------- Navigation Scaffold ---------------------------

    @Composable
    private fun MainScaffold() {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val bluetoothOn by bluetoothState.collectAsState()
        val bluetoothSupported by bluetoothSupportedState.collectAsState()
        val permissionSnapshot by permissionState.collectAsState()

        Scaffold(
            bottomBar = { BottomNavigationBar(navController, currentRoute) }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                NavHost(navController = navController, startDestination = "pairing") {
                    composable("pairing") {
                        MoncchichiHome(onOpenPermissions = {
                            navController.navigate("permissions") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        })
                    }
                    composable("permissions") {
                        PermissionsCenter(
                            bluetoothOn = bluetoothOn,
                            bluetoothSupported = bluetoothSupported,
                            permissionSnapshot = permissionSnapshot,
                            onGrantAll = { requestAllPermissions() },
                            onToggleBluetooth = { requestEnableBluetooth() }
                        )
                    }
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

    data class CachedDevice(val address: String, val name: String?)

    enum class PairingUiStatus { IDLE, PAIRING, PAIRED, FAILED }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_DEVICE_NAME = "last_device_name"
        private const val KEY_PREVIOUSLY_PAIRED = "previously_paired"
    }
}
