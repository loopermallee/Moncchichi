package io.texne.g1.hub.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    GlassesScreen(
        glasses = state.glasses,
        serviceStatus = state.serviceStatus,
        isLooking = state.isLooking,
        serviceError = state.serviceError,
        connect = viewModel::connectGlasses,
        disconnect = viewModel::disconnectGlasses,
        refresh = viewModel::refreshDevices,
        modifier = Modifier.fillMaxSize()
    )
}
