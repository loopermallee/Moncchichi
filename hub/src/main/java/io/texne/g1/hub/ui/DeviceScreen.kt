package io.texne.g1.hub.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.ui.glasses.GlassesScreen
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DeviceScreen(
    viewModel: ApplicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var messageText by rememberSaveable { mutableStateOf("") }

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
            .verticalScroll(scrollState)
    ) {
        GlassesScreen(
            glasses = state.glasses,
            serviceStatus = state.serviceStatus,
            isLooking = state.isLooking,
            serviceError = state.serviceError,
            connect = viewModel::connectGlasses,
            disconnect = viewModel::disconnectGlasses,
            refresh = viewModel::refreshDevices,
            messageText = messageText,
            onMessageChange = { messageText = it },
            onSendMessage = {
                viewModel.sendMessage(messageText) { success ->
                    if (success) {
                        messageText = ""
                    }
                }
            },
            canSendMessage = state.glasses.firstOrNull()?.status ==
                G1ServiceCommon.GlassesStatus.CONNECTED,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
