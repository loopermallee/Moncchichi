package com.teleprompter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.texne.g1.basis.service.G1DisplayService
import io.texne.g1.basis.service.protocol.IG1DisplayService
import java.text.DateFormat
import java.util.Date
import kotlin.math.coerceIn
import com.teleprompter.ui.GlassesSummary
import com.teleprompter.ui.MainViewModel
import com.teleprompter.ui.ServiceStatus
import com.teleprompter.ui.ServiceUiState
import com.teleprompter.ui.theme.MoncchichiHubTheme

class MainActivity : ComponentActivity() {
    private var displayService: IG1DisplayService? = null
    private var isServiceBound: Boolean = false
    private val viewModel: MainViewModel by viewModels()

    private val displayServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            displayService = IG1DisplayService.Stub.asInterface(binder)
            isServiceBound = true
            Log.d(TAG, "Display service connected")
            viewModel.setServiceStatus(ServiceStatus.READY)
            refreshGlassesInfo()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            displayService = null
            isServiceBound = false
            Log.d(TAG, "Display service disconnected")
            viewModel.markServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setServiceStatus(ServiceStatus.LOOKING)
        setContent {
            MoncchichiHubTheme {
                val uiState by viewModel.uiState.collectAsState()
                MainScreen(
                    uiState = uiState,
                    onRefresh = ::refreshGlassesInfo,
                    onStartDisplay = ::startDisplay,
                    onPauseDisplay = ::pauseDisplay,
                    onResumeDisplay = ::resumeDisplay,
                    onStopDisplay = ::stopDisplay
                )
            }
        }

        Intent(this, G1DisplayService::class.java).also {
            bindService(it, displayServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGlassesInfo()
    }

    override fun onDestroy() {
        if (isServiceBound) {
            try {
                displayService?.stopDisplay()
                unbindService(displayServiceConnection)
            } catch (throwable: IllegalArgumentException) {
                Log.w(TAG, "Attempted to unbind display service that was not bound", throwable)
            }
            isServiceBound = false
        }
        displayService = null
        viewModel.markServiceDisconnected()
        super.onDestroy()
    }

    private fun refreshGlassesInfo() {
        val service = displayService ?: return
        try {
            val info = service.glassesInfo
            if (info != null) {
                viewModel.updateFromGlasses(info)
            } else {
                viewModel.clearConnectedGlasses()
                viewModel.setServiceStatus(ServiceStatus.READY)
            }
        } catch (exception: RemoteException) {
            Log.e(TAG, "Unable to fetch glasses info", exception)
        }
    }

    private fun startDisplay() {
        withDisplayService("start display") { service ->
            val displayText = viewModel.uiState.value.connectedGlasses.firstOrNull()?.currentText
                ?.takeIf { it.isNotBlank() }
                ?: viewModel.uiState.value.currentText
            service.displayText(displayText)
        }
    }

    private fun pauseDisplay() {
        withDisplayService("pause display") { it.pauseDisplay() }
    }

    private fun resumeDisplay() {
        withDisplayService("resume display") { it.resumeDisplay() }
    }

    private fun stopDisplay() {
        withDisplayService("stop display") { it.stopDisplay() }
    }

    private inline fun withDisplayService(
        actionLabel: String,
        action: (IG1DisplayService) -> Unit
    ) {
        val service = displayService ?: return
        try {
            action(service)
            refreshGlassesInfo()
        } catch (exception: RemoteException) {
            Log.e(TAG, "Failed to $actionLabel", exception)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
private fun MainScreen(
    uiState: ServiceUiState,
    onRefresh: () -> Unit,
    onStartDisplay: () -> Unit,
    onPauseDisplay: () -> Unit,
    onResumeDisplay: () -> Unit,
    onStopDisplay: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "G1 Display Service",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            StatusCard(uiState = uiState, onRefresh = onRefresh)
            GlassesCard(uiState.connectedGlasses)
            ActionsCard(
                status = uiState.status,
                onStartDisplay = onStartDisplay,
                onPauseDisplay = onPauseDisplay,
                onResumeDisplay = onResumeDisplay,
                onStopDisplay = onStopDisplay,
                onRefresh = onRefresh
            )
        }
    }
}

@Composable
private fun StatusCard(
    uiState: ServiceUiState,
    onRefresh: () -> Unit
) {
    val statusLabel = remember(uiState.status) { statusLabel(uiState.status) }
    val lastUpdatedText = remember(uiState.lastUpdatedEpochMillis) {
        uiState.lastUpdatedEpochMillis?.let { timestamp ->
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = statusSupportingText(uiState.status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh status"
                    )
                }
            }
            lastUpdatedText?.let { updated ->
                Text(
                    text = "Last updated $updated",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GlassesCard(glasses: List<GlassesSummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connected Glasses",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (glasses.isEmpty()) {
                Text(
                    text = "No glasses connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                glasses.forEachIndexed { index, summary ->
                    GlassesSummaryRow(summary)
                    if (index != glasses.lastIndex) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassesSummaryRow(summary: GlassesSummary) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = summary.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        summary.batteryPercentage?.let { battery ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Battery: $battery%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = (battery / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        summary.firmwareVersion?.let { firmware ->
            Text(
                text = "Firmware: $firmware",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val statusDetails = when {
            summary.isDisplaying -> "Displaying"
            summary.isPaused -> "Paused"
            else -> "Ready"
        }
        Text(
            text = "Status: $statusDetails",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Scroll Speed: ${"%.1f".format(summary.scrollSpeed)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (summary.currentText.isNotBlank()) {
            Text(
                text = "Content: ${summary.currentText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionsCard(
    status: ServiceStatus,
    onStartDisplay: () -> Unit,
    onPauseDisplay: () -> Unit,
    onResumeDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    onRefresh: () -> Unit
) {
    val canStart = status == ServiceStatus.READY || status == ServiceStatus.PAUSED
    val canPause = status == ServiceStatus.DISPLAYING
    val canResume = status == ServiceStatus.PAUSED
    val canStop = status != ServiceStatus.DISCONNECTED && status != ServiceStatus.LOOKING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartDisplay,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Display")
                }
                FilledTonalButton(
                    onClick = onPauseDisplay,
                    enabled = canPause,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pause Display")
                }
                FilledTonalButton(
                    onClick = onResumeDisplay,
                    enabled = canResume,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Resume Display")
                }
                OutlinedButton(
                    onClick = onStopDisplay,
                    enabled = canStop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Display")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh Status")
                }
            }
        }
    }
}

private fun statusLabel(status: ServiceStatus): String = when (status) {
    ServiceStatus.DISCONNECTED -> "Disconnected"
    ServiceStatus.LOOKING -> "Looking for service"
    ServiceStatus.READY -> "Ready"
    ServiceStatus.DISPLAYING -> "Displaying"
    ServiceStatus.PAUSED -> "Paused"
}

private fun statusSupportingText(status: ServiceStatus): String = when (status) {
    ServiceStatus.DISCONNECTED -> "Reconnect glasses to resume control."
    ServiceStatus.LOOKING -> "Searching for the G1 display service and connected glasses."
    ServiceStatus.READY -> "Glasses are connected and ready for commands."
    ServiceStatus.DISPLAYING -> "Content is currently streaming to the glasses."
    ServiceStatus.PAUSED -> "Display is paused and can be resumed at any time."
}
