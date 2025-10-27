package com.loopermallee.moncchichi.subtitles.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.hub.ui.theme.StatusConnected
import com.loopermallee.moncchichi.hub.ui.theme.StatusError
import com.loopermallee.moncchichi.hub.ui.theme.StatusWarning
import com.loopermallee.moncchichi.subtitles.R

@Composable
fun SubtitlesScreen(
    viewModel: SubtitlesViewModel,
    openHub: () -> Unit,
) {
    val state = viewModel.state.collectAsState().value
    val connectedGlasses = state.glasses
    val displayService = viewModel.displayService
    val context = LocalContext.current
    val hudOverlay = remember(context) { G1HudOverlay(context) }

    LaunchedEffect(displayService, hudOverlay) {
        hudOverlay.bind(displayService.connectionState, displayService.getRssiFlow())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (connectedGlasses == null) {
                SubtitlesPlaceholder(
                    hubInstalled = state.hubInstalled,
                    onOpenHub = openHub
                )
            } else {
                GlassesCard(glasses = connectedGlasses, openHub = openHub)
            }
        }

        if (connectedGlasses != null) {
            ListeningToggle(
                listening = state.listening,
                started = state.started,
                onToggle = {
                    if (state.started) {
                        viewModel.stopRecognition()
                    } else {
                        viewModel.startRecognition()
                    }
                }
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.displayText.isEmpty()) {
                            Text(
                                text = "Live captions will appear here when speech is detected.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            state.displayText.forEach { line ->
                                Text(
                                    text = line,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    AndroidView(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        factory = { hudOverlay }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitlesPlaceholder(
    hubInstalled: Boolean,
    onOpenHub: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = hubInstalled, onClick = onOpenHub),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            if (hubInstalled) {
                Text(
                    text = "No connected glasses found",
                    color = onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Launch the hub to pair your device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onOpenHub) {
                    Text("Open Basis Hub")
                }
            } else {
                Text(
                    text = "Basis G1 Hub is not installed on this device.",
                    color = onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Install the hub, start it, then relaunch to continue.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ListeningToggle(
    listening: Boolean,
    started: Boolean,
    onToggle: () -> Unit,
) {
    val buttonColors = if (started) {
        ButtonDefaults.buttonColors(containerColor = StatusError, contentColor = MaterialTheme.colorScheme.onPrimary)
    } else {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onToggle, colors = buttonColors) {
            if (started) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = "Stop listening",
                    modifier = Modifier
                        .height(48.dp)
                        .aspectRatio(1f)
                        .padding(4.dp)
                )
                Text("Stop listening", modifier = Modifier.padding(start = 8.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Start listening",
                    modifier = Modifier
                        .height(48.dp)
                        .aspectRatio(1f)
                        .padding(8.dp)
                )
                Text("Start listening", modifier = Modifier.padding(start = 8.dp))
            }
        }
        val statusMessage = when {
            started && listening -> "Live transcription is streaming to your glasses."
            started -> "Preparing microphone…"
            else -> "Tap start to send speech prompts to the headset."
        }
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GlassesCard(
    glasses: G1ServiceCommon.Glasses,
    openHub: () -> Unit
) {
    val name = glasses.name?.takeIf { it.isNotBlank() } ?: "Unnamed device"
    val batteryPercentage = glasses.batteryPercentage
    val batteryTone = when {
        batteryPercentage == null -> MaterialTheme.colorScheme.onSurfaceVariant
        batteryPercentage > 75 -> StatusConnected
        batteryPercentage > 25 -> StatusWarning
        else -> StatusError
    }
    val batteryLabel = batteryPercentage?.let { "$it% battery" } ?: "Battery unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = openHub)
            .padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(batteryTone)
                    )
                    Text(
                        text = batteryLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Image(
                painter = painterResource(R.drawable.glasses_a),
                contentDescription = "G1 glasses",
                modifier = Modifier.height(52.dp)
            )
        }

        Text(
            text = "Tap to manage pairing in the hub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
