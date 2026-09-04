package com.ayakix.pocketfm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = DialAmber,
    onPrimary = Color(0xFF452B00),
    primaryContainer = DialAmberDim,
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = DialTeal,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = DialTealDim,
    onSecondaryContainer = Color(0xFFB2DFD8),
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainer = NightSurfaceHigh,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerLow = NightSurface,
    outline = NightOutline,
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = Color.White,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = DaySecondary,
    onSecondary = Color.White,
    secondaryContainer = DaySecondaryContainer,
    onSecondaryContainer = Color(0xFF00201B),
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainer = DaySurfaceHigh,
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerLow = DaySurface,
    outline = DayOutline,
)

@Composable
fun PocketFmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
