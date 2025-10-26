package com.loopermallee.moncchichi.hub.data.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.loopermallee.moncchichi.hub.console.ConsoleInterpreter
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.viewmodel.AppState
import com.loopermallee.moncchichi.hub.viewmodel.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticRepository(
    context: Context,
    private val memory: MemoryRepository,
) {

    data class NetworkReport(
        val isConnected: Boolean,
        val transports: List<String>,
        val hasValidatedInternet: Boolean,
        val isMetered: Boolean,
    ) {
        val label: String
            get() = if (!isConnected) {
                "offline"
            } else {
                buildString {
                    append(transports.joinToString(", ").ifBlank { "connected" })
                    if (!hasValidatedInternet) append(" (no internet)")
                    if (isMetered) append(" • metered")
                }
            }
    }

    data class Snapshot(
        val logs: List<String>,
        val insights: ConsoleInterpreter.Insights,
        val device: DeviceInfo,
        val network: NetworkReport,
        val phoneBattery: Int?,
        val isPowerSaver: Boolean,
        val telemetry: List<MemoryRepository.TelemetrySnapshotRecord>,
    )

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun snapshot(state: AppState): Snapshot {
        val logs = memory.lastConsoleLines(40)
        val insights = ConsoleInterpreter.analyze(logs)
        val network = readNetwork()
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val saver = powerManager?.isPowerSaveMode ?: false
        val telemetrySnapshots = memory.recentTelemetrySnapshots(40)

        return Snapshot(
            logs = logs,
            insights = insights,
            device = state.device,
            network = network,
            phoneBattery = battery,
            isPowerSaver = saver,
            telemetry = telemetrySnapshots,
        )
    }

    private suspend fun readNetwork(): NetworkReport = withContext(Dispatchers.IO) {
        val transports = mutableListOf<String>()
        var hasInternet = false
        var metered = false
        val active = connectivityManager?.activeNetwork
        val capabilities = active?.let { connectivityManager?.getNetworkCapabilities(it) }

        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi‑Fi"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Cellular"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "Bluetooth"
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        NetworkReport(
            isConnected = capabilities != null,
            transports = transports,
            hasValidatedInternet = hasInternet,
            isMetered = metered,
        )
    }
}
