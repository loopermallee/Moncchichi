package com.teleprompter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MoncchichiHubColorScheme = darkColorScheme(
    primary = Color(0xFF6F5AE6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4E43B6),
    onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = Color(0xFFA691F2),
    onSecondary = Color(0xFF1F1932),
    background = Color(0xFF121216),
    onBackground = Color(0xFFE0E0E6),
    surface = Color(0xFF1C1C21),
    onSurface = Color(0xFFE0E0E6),
    surfaceVariant = Color(0xFF2B2B33),
    onSurfaceVariant = Color(0xFFBEBED4),
    outline = Color(0xFF6F6F82),
    outlineVariant = Color(0xFF3C3C46)
)

@Composable
fun MoncchichiHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MoncchichiHubColorScheme,
        typography = Typography(),
        content = content
    )
}
