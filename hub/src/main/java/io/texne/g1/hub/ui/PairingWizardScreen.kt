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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun PairingWizardScreen(
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "G1 Glasses Pairing")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { viewModel.refreshDevices() }) {
                Text(text = "Refresh")
            }
            if (state.isLooking) {
                Text(text = "Searching for glasses…")
            }
        }

        if (state.devices.isEmpty()) {
            Text(text = "No glasses detected.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.devices) { device ->
                    DeviceRow(
                        device = device,
                        onPair = { id -> viewModel.pair(id) },
                        onMissingId = { viewModel.reportMissingIdentifier() }
                    )
                }
            }
        }

        if (state.serviceError) {
            Text(
                text = "The glasses service reported an error.",
                color = Color.Red
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: ApplicationViewModel.Device,
    onPair: (String) -> Unit,
    onMissingId: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = device.name)
        Text(text = "Status: ${device.status.label}")
        device.id?.let { identifier ->
            Text(text = "ID: $identifier")
        } ?: Text(text = "ID unavailable")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                enabled = device.canPair,
                onClick = {
                    val identifier = device.id
                    if (identifier != null) {
                        onPair(identifier)
                    } else {
                        onMissingId()
                    }
                }
            ) {
                Text(text = "Pair")
            }
        }
    }
}
