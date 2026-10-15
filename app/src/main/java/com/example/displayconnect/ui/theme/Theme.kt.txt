package com.example.displayconnect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Black,
    primaryContainer = BlueDeep,
    onPrimaryContainer = White,
    secondary = Silver,
    onSecondary = Black,
    tertiary = Silver,
    background = Black,
    onBackground = White,
    surface = Ink,
    onSurface = White,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Silver,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Ink,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    outline = Outline,
    outlineVariant = Outline,
    error = Alert,
    onError = White
)

@Composable
fun DisplayConnectTheme(
    // Se mantienen los parametros para no romper llamadas existentes; el tema es siempre oscuro.
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}
