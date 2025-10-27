package com.loopermallee.moncchichi.hub.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val DefaultScreenContentMaxWidth: Dp = 600.dp

fun Modifier.screenContentWidth(maxWidth: Dp = DefaultScreenContentMaxWidth): Modifier =
    this.fillMaxWidth().widthIn(max = maxWidth)
