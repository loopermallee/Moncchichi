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
import com.loopermallee.moncchichi.hub.ui.glasses.lensRecords
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
            glasses = state.pairedGlasses,
            serviceStatus = state.serviceStatus,
            isLooking = state.isLooking,
            serviceError = state.serviceError,
            connect = { ids, name ->
                viewModel.connectPair(ids, name)
            },
            disconnect = { ids ->
                ids.forEach { id -> viewModel.disconnect(id) }
            },
            refresh = viewModel::refreshGlasses,
            testMessages = testMessages,
            onTestMessageChange = { pairId, value -> testMessages[pairId] = value },
            onSendTestMessage = { ids, message, onResult ->
                viewModel.displayText(message, ids, onResult)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    LaunchedEffect(state.pairedGlasses) {
        val activeLensIds = mutableSetOf<String>()
        val activePairIds = mutableSetOf<String>()
        state.pairedGlasses.forEach { pair ->
            activePairIds += pair.pairId
            if (!testMessages.containsKey(pair.pairId)) {
                testMessages[pair.pairId] = ""
            }
            pair.lensRecords.forEach { lens ->
                val id = lens.id
                if (id != null) {
                    activeLensIds += id
                    val previous = statusHistory[id]
                    if (previous != null && previous != lens.status) {
                        if (previous == G1ServiceCommon.GlassesStatus.CONNECTING &&
                            lens.status == G1ServiceCommon.GlassesStatus.CONNECTED
                        ) {
                            Toast.makeText(
                                context,
                                "Connected to ${lens.displayName()}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (previous == G1ServiceCommon.GlassesStatus.CONNECTING &&
                            lens.status == G1ServiceCommon.GlassesStatus.ERROR
                        ) {
                            Toast.makeText(
                                context,
                                "Failed to connect to ${lens.displayName()}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    statusHistory[id] = lens.status
                }
            }
        }
        val statusIterator = statusHistory.keys.iterator()
        while (statusIterator.hasNext()) {
            val id = statusIterator.next()
            if (id !in activeLensIds) {
                statusIterator.remove()
            }
        }
        val messageIterator = testMessages.keys.iterator()
        while (messageIterator.hasNext()) {
            val pairId = messageIterator.next()
            if (pairId !in activePairIds) {
                messageIterator.remove()
            }
        }
    }
}
