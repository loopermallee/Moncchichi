package com.loopermallee.moncchichi.hub.ui.glasses

import androidx.compose.ui.graphics.Color
import com.loopermallee.moncchichi.client.G1ServiceCommon
import com.loopermallee.moncchichi.client.G1ServiceCommon.GlassesStatus

internal val ConnectedIndicator = Color.White
internal val TransitionIndicator = Color(0xFFC8C8C8)
internal val AttentionIndicator = Color(0xFFA0A0A0)
internal val InactiveIndicator = Color(0xFF6E6E6E)

internal fun G1ServiceCommon.Glasses.statusColor(): Color = when (status) {
    GlassesStatus.CONNECTED -> ConnectedIndicator
    GlassesStatus.CONNECTING,
    GlassesStatus.DISCONNECTING,
    GlassesStatus.UNINITIALIZED -> TransitionIndicator
    GlassesStatus.DISCONNECTED -> InactiveIndicator
    GlassesStatus.ERROR -> AttentionIndicator
}
