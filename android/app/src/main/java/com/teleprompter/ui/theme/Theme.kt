package com.teleprompter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MonochromeDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC5CAD3),
    onPrimary = Color(0xFF101114),
    primaryContainer = Color(0xFF2E3138),
    onPrimaryContainer = Color(0xFFE9ECF2),
    secondary = Color(0xFFA7ACB5),
    onSecondary = Color(0xFF111216),
    secondaryContainer = Color(0xFF2B2F35),
    onSecondaryContainer = Color(0xFFE4E6ED),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFEAEDF3),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFEAEDF3),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFCDD0D8),
    outline = Color(0xFF41444C),
    outlineVariant = Color(0xFF2C2F36)
)

private val MonochromeLightColorScheme = lightColorScheme(
    primary = Color(0xFF2F323A),
    onPrimary = Color(0xFFF5F7FB),
    primaryContainer = Color(0xFFDDE1E8),
    onPrimaryContainer = Color(0xFF121316),
    secondary = Color(0xFF41434A),
    onSecondary = Color(0xFFF7F8FA),
    secondaryContainer = Color(0xFFE6E8EE),
    onSecondaryContainer = Color(0xFF16181F),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111216),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111216),
    surfaceVariant = Color(0xFFE3E5EB),
    onSurfaceVariant = Color(0xFF30333A),
    outline = Color(0xFF5F626A),
    outlineVariant = Color(0xFFC4C6CC)
)

@Composable
fun MoncchichiHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        MonochromeDarkColorScheme
    } else {
        MonochromeLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
