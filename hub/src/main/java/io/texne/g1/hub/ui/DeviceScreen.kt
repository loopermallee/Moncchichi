package io.texne.g1.hub.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatteryUnknown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DeviceScreen(
    viewModel: ApplicationViewModel = hiltViewModel()
) {
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

    val scrollState = rememberScrollState()

    val hasConnectedGlasses = state.devices.any { it.status == ApplicationViewModel.DeviceStatus.CONNECTED }
    val isBusy = state.isLooking || state.devices.any { it.isBusy }
    val hasError = state.serviceError

    val statusText = when {
        hasError -> "Connection Error"
        isBusy && hasConnectedGlasses -> "Updating connection…"
        isBusy -> "Connecting…"
        hasConnectedGlasses -> "Glasses Online"
        else -> "No Glasses Connected"
    }

    val statusColor = when {
        hasError -> MaterialTheme.colorScheme.error
        hasConnectedGlasses -> Color(0xFF2E7D32)
        isBusy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val actionLabel = if (hasConnectedGlasses) "Disconnect Glasses" else "Connect Glasses"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "G1 Glasses",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = statusText,
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !isBusy,
                onClick = {
                    if (hasConnectedGlasses) {
                        viewModel.disconnectGlasses()
                    } else {
                        viewModel.connectAvailableGlasses()
                    }
                }
            ) {
                Text(actionLabel)
            }

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        if (state.devices.isEmpty() && !isBusy) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "No Glasses Found. Connect to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val (leftDevice, rightDevice) = assignDevices(state.devices)

        DeviceCard(
            title = "Left Glass",
            device = leftDevice,
            modifier = Modifier.fillMaxWidth()
        )

        DeviceCard(
            title = "Right Glass",
            device = rightDevice,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceCard(
    title: String,
    device: ApplicationViewModel.Device?,
    modifier: Modifier = Modifier
) {
    val status = device?.status ?: ApplicationViewModel.DeviceStatus.DISCONNECTED
    val (badgeColor, badgeText) = when (status) {
        ApplicationViewModel.DeviceStatus.CONNECTED -> Color(0xFF2E7D32) to "Online"
        ApplicationViewModel.DeviceStatus.CONNECTING -> MaterialTheme.colorScheme.primary to "Connecting"
        ApplicationViewModel.DeviceStatus.DISCONNECTING -> MaterialTheme.colorScheme.primary to "Disconnecting"
        ApplicationViewModel.DeviceStatus.ERROR -> MaterialTheme.colorScheme.error to "Error"
        ApplicationViewModel.DeviceStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
    }

    val batteryPercentage = device?.batteryPercentage
    val (batteryIcon, batteryTint, batteryText) = when {
        batteryPercentage == null -> Triple(Icons.Outlined.BatteryUnknown, MaterialTheme.colorScheme.onSurfaceVariant, "--%")
        batteryPercentage < 20 -> Triple(Icons.Outlined.BatteryAlert, MaterialTheme.colorScheme.error, "${batteryPercentage}%")
        batteryPercentage < 50 -> Triple(Icons.Outlined.BatteryChargingFull, MaterialTheme.colorScheme.tertiary, "${batteryPercentage}%")
        else -> Triple(Icons.Outlined.BatteryFull, Color(0xFF2E7D32), "${batteryPercentage}%")
    }

    val firmwareVersion = device?.firmwareVersion ?: "Firmware version unavailable"

    Card(
        modifier = modifier.padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                StatusBadge(text = badgeText, backgroundColor = badgeColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = batteryIcon,
                    contentDescription = "Battery",
                    tint = batteryTint
                )
                Text(
                    text = batteryText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = firmwareVersion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = backgroundColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun assignDevices(
    devices: List<ApplicationViewModel.Device>
): Pair<ApplicationViewModel.Device?, ApplicationViewModel.Device?> {
    val left = devices.firstOrNull { it.name.contains("left", ignoreCase = true) }
    val right = devices.firstOrNull { it.name.contains("right", ignoreCase = true) }
    val fallbackLeft = left ?: devices.firstOrNull()
    val fallbackRight = right ?: devices.firstOrNull { it != fallbackLeft }
    return fallbackLeft to fallbackRight
}
