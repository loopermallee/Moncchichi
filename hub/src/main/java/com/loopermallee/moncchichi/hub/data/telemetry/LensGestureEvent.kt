package com.loopermallee.moncchichi.hub.data.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.telemetry.G1ReplyParser

/**
 * Represents a gesture event scoped to a specific lens.
 */
data class LensGestureEvent(
    val lens: MoncchichiBleService.Lens,
    val gesture: G1ReplyParser.GestureEvent,
)
