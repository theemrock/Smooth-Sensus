package com.theemrock.smooth.sensus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SmoothColorScheme = darkColorScheme(
    primary = SmoothAccent,
    onPrimary = SmoothOnAccent,
    primaryContainer = SmoothAccentContainer,
    onPrimaryContainer = SmoothText,
    secondary = SmoothAccent,
    onSecondary = SmoothOnAccent,
    secondaryContainer = SmoothSurfaceVariant,
    onSecondaryContainer = SmoothText,
    tertiary = SmoothAccent,
    onTertiary = SmoothOnAccent,
    background = SmoothBackground,
    onBackground = SmoothText,
    surface = SmoothSurface,
    onSurface = SmoothText,
    surfaceVariant = SmoothSurfaceVariant,
    onSurfaceVariant = SmoothMutedText,
    outline = SmoothOutline,
    error = SmoothError
)

@Composable
fun SmoothTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmoothColorScheme,
        typography = Typography,
        content = content
    )
}
