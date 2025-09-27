package io.texne.g1.hub.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ApplicationFrame() {
    val viewModel = hiltViewModel<ApplicationViewModel>()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshDevices()
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "G1 Glasses Pairing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.refreshDevices() }) {
                Text(text = "Refresh")
            }
            if (state.isLooking) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(text = "Searching…") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        if (state.devices.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No glasses detected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Use the refresh action to search again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(state.devices) { device ->
                    DeviceCard(
                        device = device,
                        onPair = { id -> viewModel.pair(id) },
                        onDisconnect = { id -> viewModel.disconnect(id) },
                        onMissingId = { viewModel.reportMissingIdentifier() }
                    )
                }
            }
        }

        if (state.serviceError) {
            ErrorBanner()
        }
    }
}

@Composable
private fun DeviceCard(
    device: ApplicationViewModel.Device,
    onPair: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onMissingId: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    device.id?.let { identifier ->
                        Text(
                            text = "ID: $identifier",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } ?: Text(
                        text = "ID unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusBadge(status = device.status)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceActionButton(
                    device = device,
                    onPair = onPair,
                    onDisconnect = onDisconnect,
                    onMissingId = onMissingId
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ApplicationViewModel.DeviceStatus) {
    val (background, content) = when (status) {
        ApplicationViewModel.DeviceStatus.CONNECTED -> Color(0xFF4CAF50) to Color.White
        ApplicationViewModel.DeviceStatus.CONNECTING, ApplicationViewModel.DeviceStatus.DISCONNECTING -> Color(0xFFFFC107) to Color.Black
        ApplicationViewModel.DeviceStatus.ERROR -> Color(0xFFF44336) to Color.White
        ApplicationViewModel.DeviceStatus.DISCONNECTED -> Color(0xFF9E9E9E) to Color.White
    }

    Surface(
        shape = CircleShape,
        color = background,
        contentColor = content
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DeviceActionButton(
    device: ApplicationViewModel.Device,
    onPair: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onMissingId: () -> Unit
) {
    val identifier = device.id
    when {
        device.canDisconnect && identifier != null -> {
            Button(
                onClick = { onDisconnect(identifier) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(text = "Disconnect")
            }
        }

        device.canPair && identifier != null -> {
            Button(
                onClick = { onPair(identifier) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "Pair")
            }
        }

        device.status == ApplicationViewModel.DeviceStatus.CONNECTING ||
            device.status == ApplicationViewModel.DeviceStatus.DISCONNECTING -> {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        else -> {
            Button(
                onClick = onMissingId,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "Pair")
            }
        }
    }
}

@Composable
private fun ErrorBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Service error",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The glasses service reported an error.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
