package com.loopermallee.moncchichi.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EvenRealitiesColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF121212),
    onPrimaryContainer = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F1F1F),
    onSecondaryContainer = Color.White,
    tertiary = Color.White,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color.White,
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFF2A2A2A),
    error = StatusError,
    onError = Color.Black
)

@Composable
fun G1HubTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = EvenRealitiesColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}