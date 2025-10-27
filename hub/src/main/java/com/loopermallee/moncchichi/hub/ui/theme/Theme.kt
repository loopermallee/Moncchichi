package com.loopermallee.moncchichi.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkMonochromeColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF232323),
    onPrimaryContainer = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color.White,
    tertiary = Color.White,
    onTertiary = Color.Black,
    background = Color(0xFF040404),
    onBackground = Color.White,
    surface = Color(0xFF101010),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF191919),
    onSurfaceVariant = Color.White,
    outline = Color(0xFF363636),
    outlineVariant = Color(0xFF2C2C2C),
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color(0xFF1F1F1F),
    onErrorContainer = Color.White
)

private val LightMonochromeColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCDCDC),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF6F6F6),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color.Black,
    outline = Color(0xFFB0B0B0),
    outlineVariant = Color(0xFFA0A0A0),
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFE0E0E0),
    onErrorContainer = Color.Black
)

@Composable
fun G1HubTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkMonochromeColorScheme else LightMonochromeColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
