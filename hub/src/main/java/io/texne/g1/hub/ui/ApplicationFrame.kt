package io.texne.g1.hub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ApplicationFrame() {
    val viewModel = hiltViewModel<ApplicationViewModel>()
    val state by viewModel.state.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ApplicationViewModel.Event.ConnectionResult -> {
                    val text = if (event.success) {
                        "Connected to glasses"
                    } else {
                        "Failed to connect"
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
                ApplicationViewModel.Event.Disconnected -> {
                    Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val connectedGlasses = state.connectedGlasses
    val nearbyGlasses = state.nearbyGlasses

    LaunchedEffect(connectedGlasses?.id) {
        selectedId = connectedGlasses?.id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Moncchichi Hub",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(text = "Manage Even Realities G1 glasses over Bluetooth")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { viewModel.scan() }) {
                Text(if (state.scanning) "Scanning..." else "Scan for devices")
            }
            if (connectedGlasses != null) {
                Button(onClick = { viewModel.disconnect(connectedGlasses.id) }) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = {
                    val target = selectedId ?: nearbyGlasses?.firstOrNull()?.id
                    if (target != null) {
                        viewModel.connect(target)
                    } else {
                        Toast.makeText(context, "Select a device first", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Connect")
                }
            }
        }

        val devices = nearbyGlasses ?: emptyList()
        if (devices.isEmpty()) {
            Text(
                text = if (state.scanning) "Scanning for glasses..." else "No glasses found",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(devices) { glasses ->
                    DeviceRow(
                        glasses = glasses,
                        selected = glasses.id == selectedId,
                        onClick = { selectedId = glasses.id },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = message,
            onValueChange = { message = it },
            label = { Text("Message to send") },
            singleLine = false,
            maxLines = 3,
        )
        Button(
            enabled = message.isNotBlank() && connectedGlasses != null,
            onClick = {
                viewModel.sendMessage(message)
                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
            },
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun DeviceRow(
    glasses: G1ServiceCommon.Glasses,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = glasses.name, style = MaterialTheme.typography.titleMedium)
            Text(text = glasses.id, style = MaterialTheme.typography.bodySmall)
        }
    }
}
