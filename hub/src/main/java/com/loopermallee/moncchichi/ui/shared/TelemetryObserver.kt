package com.loopermallee.moncchichi.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.collectLatest
import com.loopermallee.moncchichi.service.G1DisplayService
import com.loopermallee.moncchichi.bluetooth.G1TelemetryEvent

@Composable
fun rememberTelemetryLog(service: G1DisplayService?): List<G1TelemetryEvent> {
    val telemetry = remember { mutableStateListOf<G1TelemetryEvent>() }
    LaunchedEffect(service) {
        telemetry.clear()
        if (service == null) {
            return@LaunchedEffect
        }
        service.getTelemetryFlow().collectLatest { events ->
            telemetry.clear()
            telemetry.addAll(events)
        }
    }
    return telemetry
}
