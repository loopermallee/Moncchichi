package io.texne.g1.subtitles.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.subtitles.R

@Composable
fun SubtitlesScreen(
    openHub: () -> Unit
) {

    val viewModel = hiltViewModel<SubtitlesViewModel>()
    val state = viewModel.state.collectAsState().value
    val connectedGlasses = state.glasses

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f)
        ) {
            if(connectedGlasses == null) {
                Box(
                    modifier = Modifier.background(Color.LightGray, RoundedCornerShape(16.dp)).fillMaxSize()
                        .clickable(state.hubInstalled, onClick = openHub),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if(state.hubInstalled) {
                            Text("No connected glasses found.", color = Color.Black)
                            Button(
                                onClick = openHub,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(150, 0, 0, 255)
                                )
                            ) {
                                Text("OPEN BASIS HUB")
                            }
                        } else {
                            Text(text = "The Basis G1 Hub is not installed", color = Color.Black)
                            Text(text = "in this device.", color = Color.Black)
                            Text(text = "Please install and run it,", color = Color.Black)
                            Text(text = "Then restart this application to continue.", color = Color.Black)
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
                    containerColor = if(state.listening) Color(150, 0, 0, 255) else Color.White
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
                modifier = Modifier.fillMaxSize().border(1.dp, Color.White, RoundedCornerShape(16.dp)).weight(1f)
            ) {
                Column(modifier = Modifier.padding(32.dp).fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    state.displayText.forEach {
                        Text(it, color = Color.Green)
                    }
                }
            }
        }
    }
}

@Composable
fun GlassesCard(
    glasses: G1ServiceCommon.Glasses,
    openHub: () -> Unit
) {
    Box(
        modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp)).fillMaxSize()
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
                Text(glasses.name, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 32.sp)
                val batteryPercentage = glasses.batteryPercentage
                val batteryColor = when {
                    batteryPercentage == null -> Color.Gray
                    batteryPercentage > 75 -> Color(4, 122, 0, 255)
                    batteryPercentage > 25 -> Color(162, 141, 26, 255)
                    else -> Color(147, 0, 0, 255)
                }
                val batteryLabel = batteryPercentage?.let { "$it% battery" } ?: "Battery unknown"
                Text(batteryLabel, color = batteryColor)
            }
        }
    }
}