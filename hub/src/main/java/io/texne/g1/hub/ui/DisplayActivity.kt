package io.texne.g1.hub.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.ui.theme.G1HubTheme
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class DisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            G1HubTheme {
                DisplayScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun DisplayScreen(
    onBack: () -> Unit,
    viewModel: ApplicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var messageText by rememberSaveable { mutableStateOf("") }
    val canSendMessage = state.glasses.firstOrNull()?.status ==
        G1ServiceCommon.GlassesStatus.CONNECTED

    LaunchedEffect(Unit) {
        viewModel.onScreenReady()
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var hasShownNoDeviceToast by remember { mutableStateOf(false) }

    LaunchedEffect(canSendMessage) {
        if (!canSendMessage && !hasShownNoDeviceToast) {
            Toast.makeText(context, "No device connected", Toast.LENGTH_SHORT).show()
            hasShownNoDeviceToast = true
        } else if (canSendMessage) {
            hasShownNoDeviceToast = false
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Display Message",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = messageText,
            onValueChange = { messageText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            minLines = 2
        )

        Button(
            onClick = {
                viewModel.sendMessage(messageText) { success ->
                    if (success) {
                        messageText = ""
                    }
                }
            },
            enabled = canSendMessage && messageText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Send Message")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.stopDisplaying() },
            enabled = canSendMessage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Displaying")
        }

        if (!canSendMessage) {
            Text(
                text = "Connect to your glasses from the Device screen to send a message.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Back to Device")
        }
    }
}
