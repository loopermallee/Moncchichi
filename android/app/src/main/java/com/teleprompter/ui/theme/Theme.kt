package com.teleprompter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkMonochromeColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F1F1F),
    onPrimaryContainer = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    tertiary = Color.White,
    onTertiary = Color.Black,
    background = Color(0xFF050505),
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color(0xFF202020),
    onErrorContainer = Color.White
)

private val LightMonochromeColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF5F5F5),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color.Black,
    outline = Color(0xFFB0B0B0),
    outlineVariant = Color(0xFF9A9A9A),
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFDDDDDD),
    onErrorContainer = Color.Black
)

@Composable
fun MoncchichiHubTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkMonochromeColorScheme else LightMonochromeColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
