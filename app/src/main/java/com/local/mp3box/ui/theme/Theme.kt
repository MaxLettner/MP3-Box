package com.local.mp3box.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Mp3BoxColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    onSecondary = Color.White,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant
)

@Composable
fun Mp3BoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Mp3BoxColors,
        typography = Mp3BoxTypography,
        content = content
    )
}
