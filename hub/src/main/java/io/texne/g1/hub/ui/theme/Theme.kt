package io.texne.g1.hub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bof4ColorScheme = darkColorScheme(
    primary = Bof4Verdant,
    onPrimary = Bof4Midnight,
    primaryContainer = Color(0xFF77A98B),
    onPrimaryContainer = Bof4Midnight,
    secondary = Bof4Sky,
    onSecondary = Bof4Midnight,
    secondaryContainer = Color(0xFF80B4E0),
    onSecondaryContainer = Bof4Midnight,
    tertiary = Bof4Ember,
    onTertiary = Bof4Midnight,
    background = Bof4Midnight,
    onBackground = Bof4Mist,
    surface = Bof4Steel,
    onSurface = Bof4Mist,
    surfaceVariant = Color(0xFF274967),
    onSurfaceVariant = Bof4Sand,
    outline = Bof4Sky.copy(alpha = 0.6f),
    error = Bof4Coral,
    onError = Bof4Mist
)

@Composable
fun G1HubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Bof4ColorScheme,
        typography = Typography,
        content = content
    )
}