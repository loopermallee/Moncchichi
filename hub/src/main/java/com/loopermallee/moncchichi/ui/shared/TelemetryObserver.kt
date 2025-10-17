package com.loopermallee.moncchichi.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopermallee.moncchichi.telemetry.G1TelemetryEvent

@Composable
fun TelemetryObserver(
    events: List<G1TelemetryEvent>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        if (events.isEmpty()) {
            Text(
                text = "No telemetry yet",
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(events.takeLast(200)) { event ->
                    val color = when (event.category) {
                        G1TelemetryEvent.Category.APP -> Color(0xFF2196F3)
                        G1TelemetryEvent.Category.SERVICE -> Color(0xFF4CAF50)
                        G1TelemetryEvent.Category.DEVICE -> Color(0xFFFFC107)
                        G1TelemetryEvent.Category.SYSTEM -> Color(0xFFF44336)
                    }
                    Text(
                        text = event.toString(),
                        color = color,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
