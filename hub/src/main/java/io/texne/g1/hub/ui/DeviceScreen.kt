package io.texne.g1.hub.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.hub.ui.glasses.GlassesScreen
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DeviceScreen(
    viewModel: ApplicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.onScreenReady()
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassesScreen(
            glasses = state.glasses,
            serviceStatus = state.serviceStatus,
            isLooking = state.isLooking,
            serviceError = state.serviceError,
            connect = viewModel::connectGlasses,
            disconnect = viewModel::disconnectGlasses,
            refresh = viewModel::refreshDevices,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                context.startActivity(Intent(context, DisplayActivity::class.java))
            },
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(text = "Open Display Screen")
        }
    }
}
