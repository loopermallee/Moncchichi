package com.loopermallee.moncchichi.hub.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.glasses.GlassesScreen
import com.loopermallee.moncchichi.hub.ui.glasses.displayName
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DeviceScreen(
    viewModel: ApplicationViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val scrollState = rememberScrollState()
    val statusHistory = remember { mutableStateMapOf<String, G1ServiceCommon.GlassesStatus>() }
    val testMessages = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassesScreen(
            glasses = state.glasses,
            serviceStatus = state.serviceStatus,
            isLooking = state.isLooking,
            serviceError = state.serviceError,
            connect = { id, name -> viewModel.connect(id, name) },
            disconnect = { id -> viewModel.disconnect(id) },
            refresh = viewModel::refreshGlasses,
            testMessages = testMessages,
            onTestMessageChange = { id, value -> testMessages[id] = value },
            onSendTestMessage = { id, message, onResult ->
                viewModel.displayText(message, listOf(id)) { success ->
                    if (success) {
                        testMessages[id] = ""
                    }
                    onResult(success)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    LaunchedEffect(state.glasses) {
        val idsInState = mutableSetOf<String>()
        state.glasses.forEach { glass ->
            val id = glass.id
            if (id != null) {
                idsInState += id
                if (!testMessages.containsKey(id)) {
                    testMessages[id] = ""
                }
                val previous = statusHistory[id]
                if (previous != null && previous != glass.status) {
                    if (previous == G1ServiceCommon.GlassesStatus.CONNECTING &&
                        glass.status == G1ServiceCommon.GlassesStatus.CONNECTED
                    ) {
                        Toast.makeText(
                            context,
                            "Connected to ${glass.displayName()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (previous == G1ServiceCommon.GlassesStatus.CONNECTING &&
                        glass.status == G1ServiceCommon.GlassesStatus.ERROR
                    ) {
                        Toast.makeText(
                            context,
                            "Failed to connect to ${glass.displayName()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                statusHistory[id] = glass.status
            }
        }
        val iterator = statusHistory.keys.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in idsInState) {
                iterator.remove()
            }
        }
        val messageIterator = testMessages.keys.iterator()
        while (messageIterator.hasNext()) {
            val id = messageIterator.next()
            if (id !in idsInState) {
                messageIterator.remove()
            }
        }
    }
}
