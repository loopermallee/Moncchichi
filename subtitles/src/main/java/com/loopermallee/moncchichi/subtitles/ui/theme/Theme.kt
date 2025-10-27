package com.loopermallee.moncchichi.subtitles.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC5CAD3),
    onPrimary = Neutral950,
    primaryContainer = Neutral800,
    onPrimaryContainer = Neutral200,
    secondary = Color(0xFFA7ACB5),
    onSecondary = Neutral950,
    secondaryContainer = Neutral850,
    onSecondaryContainer = Neutral200,
    tertiary = Color(0xFFB5B9C2),
    onTertiary = Neutral950,
    background = Neutral950,
    onBackground = Neutral100,
    surface = Neutral900,
    onSurface = Neutral100,
    surfaceVariant = Neutral800,
    onSurfaceVariant = Neutral300,
    outline = Color(0xFF41444C),
    outlineVariant = Color(0xFF2C2F36),
    error = StatusError,
    onError = Neutral100
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2F323A),
    onPrimary = Neutral050,
    primaryContainer = Neutral200,
    onPrimaryContainer = Color(0xFF121316),
    secondary = Color(0xFF41434A),
    onSecondary = Neutral050,
    secondaryContainer = Neutral150,
    onSecondaryContainer = Color(0xFF16181F),
    tertiary = Color(0xFF5A5D65),
    onTertiary = Neutral050,
    background = Neutral100,
    onBackground = Color(0xFF111216),
    surface = Neutral050,
    onSurface = Color(0xFF111216),
    surfaceVariant = Neutral200,
    onSurfaceVariant = Color(0xFF30333A),
    outline = Color(0xFF5F626A),
    outlineVariant = Color(0xFFC4C6CC),
    error = StatusError,
    onError = Neutral050
)

@Composable
fun SubtitlesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}