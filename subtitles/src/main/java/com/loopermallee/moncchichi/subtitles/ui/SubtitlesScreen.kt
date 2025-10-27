package com.loopermallee.moncchichi.subtitles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.loopermallee.moncchichi.client.G1ServiceCommon
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
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f)
        ) {
            if(connectedGlasses == null) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .fillMaxSize()
                        .clickable(state.hubInstalled, onClick = openHub),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if(state.hubInstalled) {
                            Text("No connected glasses found.", color = MaterialTheme.colorScheme.onSurface)
                            Button(
                                onClick = openHub,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onSurface,
                                    contentColor = MaterialTheme.colorScheme.background
                                )
                            ) {
                                Text("OPEN BASIS HUB")
                            }
                        } else {
                            Text(text = "The Basis G1 Hub is not installed", color = MaterialTheme.colorScheme.onSurface)
                            Text(text = "in this device.", color = MaterialTheme.colorScheme.onSurface)
                            Text(text = "Please install and run it,", color = MaterialTheme.colorScheme.onSurface)
                            Text(text = "Then restart this application to continue.", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            } else {
                GlassesCard(connectedGlasses, openHub)
            }
        }
        if(connectedGlasses != null) {
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.listening) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.listening) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
                ),
                onClick = {
                    if(state.started) {
                        viewModel.stopRecognition()
                    } else {
                        viewModel.startRecognition()
                    }
                }
            ) {
                if(state.started) {
                    Icon(Icons.Filled.MicOff, "stop listening", modifier = Modifier.height(48.dp).aspectRatio(1f).padding(4.dp))
                } else {
                    Icon(Icons.Filled.Mic, "start listening", modifier = Modifier.height(48.dp).aspectRatio(1f).padding(8.dp))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp))
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    state.displayText.forEach {
                        Text(it, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                AndroidView(
                    modifier = Modifier.align(Alignment.TopEnd),
                    factory = { hudOverlay }
                )
            }
        }
    }
}

@Composable
fun GlassesCard(
    glasses: G1ServiceCommon.Glasses,
    openHub: () -> Unit
) {
    val name = glasses.name ?: "Unnamed device"
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .fillMaxSize()
            .clickable(true, onClick = openHub),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painter = painterResource(R.drawable.glasses_a), contentDescription = "picture of glasses", modifier = Modifier.height(48.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy((-6.dp))
            ) {
                Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 32.sp)
                val batteryPercentage = glasses.batteryPercentage
                val batteryLabel = when {
                    batteryPercentage == null -> "Battery unknown"
                    batteryPercentage > 75 -> "Battery $batteryPercentage% • Ready"
                    batteryPercentage > 25 -> "Battery $batteryPercentage% • Moderate"
                    else -> "Battery $batteryPercentage% • Charge soon"
                }
                Text(batteryLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            }
        }
    }
}
