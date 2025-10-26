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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
            viewModel.setServiceStatus(ServiceStatus.CONNECTED)
            refreshGlassesInfo()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            displayService = null
            isServiceBound = false
            Log.d(TAG, "Display service disconnected")
            viewModel.markServiceDisconnected(
                reason = "Display service disconnected: ${'$'}{name.className}"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setServiceStatus(ServiceStatus.CONNECTING)
        setContent {
            MoncchichiHubTheme {
                val uiState by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                MainScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onSnackbarShown = viewModel::onSnackbarShown,
                    onRefresh = ::refreshGlassesInfo,
                    onStartDisplay = ::startDisplay,
                    onPauseDisplay = ::pauseDisplay,
                    onResumeDisplay = ::resumeDisplay,
                    onStopDisplay = ::stopDisplay,
                    onRetryConnection = ::retryServiceConnection
                )
            }
        }

        attemptBindDisplayService()
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
        viewModel.markServiceDisconnected(showRetry = false)
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
                viewModel.setServiceStatus(ServiceStatus.CONNECTED)
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
        val service = displayService ?: run {
            Log.w(TAG, "Unable to $actionLabel because the display service is not available")
            viewModel.showSnackbar("Display service unavailable. Please try again.")
            return
        }
        try {
            action(service)
            refreshGlassesInfo()
        } catch (exception: RemoteException) {
            Log.e(TAG, "Failed to $actionLabel", exception)
            val message = buildString {
                append("Failed to $actionLabel")
                exception.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
            }
            viewModel.showSnackbar(message)
        }
    }

    private fun attemptBindDisplayService() {
        if (isServiceBound) {
            return
        }
        viewModel.setServiceStatus(ServiceStatus.CONNECTING)
        val intent = Intent(this, G1DisplayService::class.java)
        val bound = bindService(intent, displayServiceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(TAG, "Failed to bind to display service")
            viewModel.onServiceBindFailed()
        }
    }

    private fun retryServiceConnection() {
        if (isServiceBound) {
            viewModel.showSnackbar("Display service is already connected.")
            return
        }
        attemptBindDisplayService()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
private fun MainScreen(
    uiState: ServiceUiState,
    snackbarHostState: SnackbarHostState,
    onSnackbarShown: () -> Unit,
    onRefresh: () -> Unit,
    onStartDisplay: () -> Unit,
    onPauseDisplay: () -> Unit,
    onResumeDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    onRetryConnection: () -> Unit
) {
    val controlsEnabled = remember(uiState.status) {
        uiState.status == ServiceStatus.READY ||
            uiState.status == ServiceStatus.DISPLAYING ||
            uiState.status == ServiceStatus.PAUSED
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onSnackbarShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "G1 Display Service",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                uiState.persistentErrorMessage?.let { message ->
                    ErrorBanner(message = message)
                }
                StatusCard(
                    uiState = uiState,
                    onRefresh = onRefresh,
                    onRetryConnection = onRetryConnection,
                    isRetryEnabled = uiState.status != ServiceStatus.CONNECTING
                )
                GlassesCard(uiState.connectedGlasses)
                ActionsCard(
                    status = uiState.status,
                    onStartDisplay = onStartDisplay,
                    onPauseDisplay = onPauseDisplay,
                    onResumeDisplay = onResumeDisplay,
                    onStopDisplay = onStopDisplay,
                    onRefresh = onRefresh,
                    controlsEnabled = controlsEnabled
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    uiState: ServiceUiState,
    onRefresh: () -> Unit,
    onRetryConnection: () -> Unit,
    isRetryEnabled: Boolean
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
            if (uiState.status == ServiceStatus.CONNECTING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            lastUpdatedText?.let { updated ->
                Text(
                    text = "Last updated $updated",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val showRetryButton = remember(uiState.status, uiState.isRetryVisible) {
                uiState.isRetryVisible ||
                    uiState.status == ServiceStatus.DISCONNECTED ||
                    uiState.status == ServiceStatus.CONNECTING ||
                    uiState.status == ServiceStatus.CONNECTED
            }
            if (showRetryButton) {
                FilledTonalButton(
                    onClick = onRetryConnection,
                    enabled = isRetryEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Retry connection")
                }
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
    onRefresh: () -> Unit,
    controlsEnabled: Boolean
) {
    val canStart = controlsEnabled && (status == ServiceStatus.READY || status == ServiceStatus.PAUSED)
    val canPause = controlsEnabled && status == ServiceStatus.DISPLAYING
    val canResume = controlsEnabled && status == ServiceStatus.PAUSED
    val canStop = controlsEnabled && (
        status == ServiceStatus.READY ||
            status == ServiceStatus.DISPLAYING ||
            status == ServiceStatus.PAUSED
    )

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

@Composable
private fun ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(20.dp)
        )
    }
}

private fun statusLabel(status: ServiceStatus): String = when (status) {
    ServiceStatus.DISCONNECTED -> "Disconnected"
    ServiceStatus.CONNECTING -> "Connecting…"
    ServiceStatus.CONNECTED -> "Connected"
    ServiceStatus.READY -> "Ready"
    ServiceStatus.DISPLAYING -> "Displaying"
    ServiceStatus.PAUSED -> "Paused"
}

private fun statusSupportingText(status: ServiceStatus): String = when (status) {
    ServiceStatus.DISCONNECTED -> "Reconnect glasses to resume control."
    ServiceStatus.CONNECTING -> "Establishing a connection to the G1 display service."
    ServiceStatus.CONNECTED -> "Connection established. Waiting for the service to report readiness."
    ServiceStatus.READY -> "Glasses are connected and ready for commands."
    ServiceStatus.DISPLAYING -> "Content is currently streaming to the glasses."
    ServiceStatus.PAUSED -> "Display is paused and can be resumed at any time."
}
