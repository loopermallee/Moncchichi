package io.texne.g1.hub.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.texne.g1.hub.ui.glasses.GlassesScreen
import kotlinx.coroutines.flow.collectLatest
import io.texne.g1.hub.R

private val RefreshButtonColor = Color(0xFF3AAED8)

@Composable
fun DeviceScreen(
    viewModel: ApplicationViewModel = hiltViewModel(),
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

    val glasses = state.glasses

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.breath_of_fire_4_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.2f)
                        )
                    )
                )
        )

        if (glasses != null) {
            GlassesScreen(
                glasses = glasses,
                serviceStatus = state.serviceStatus,
                isLooking = state.isLooking,
                serviceError = state.serviceError,
                onRefresh = { viewModel.refreshDevices(autoReconnect = true) },
                connect = viewModel::connectGlasses,
                disconnect = viewModel::disconnectGlasses,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            EmptyDeviceState(
                isLooking = state.isLooking,
                serviceError = state.serviceError,
                onRefresh = { viewModel.refreshDevices(autoReconnect = true) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun EmptyDeviceState(
    isLooking: Boolean,
    serviceError: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "G1 Glasses",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                serviceError -> {
                    Text(
                        text = "The glasses service reported an error.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB347),
                        textAlign = TextAlign.Center
                    )
                }

                isLooking -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = "Looking for glasses…",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "No Glasses Found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Turn on your glasses and keep them nearby to connect.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onRefresh,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RefreshButtonColor,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Refresh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
