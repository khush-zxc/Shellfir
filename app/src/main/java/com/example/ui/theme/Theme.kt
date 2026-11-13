package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ShellDarkTextPrimary,
    onPrimary = ShellDarkBackground,
    secondary = ShellDarkAccent,
    onSecondary = ShellDarkTextPrimary,
    tertiary = ShellDarkAccent,
    background = ShellDarkBackground,
    onBackground = ShellDarkTextPrimary,
    surface = ShellDarkSurface,
    onSurface = ShellDarkTextPrimary,
    surfaceVariant = ShellDarkSurfaceElevated,
    onSurfaceVariant = ShellDarkTextSecondary,
    outline = ShellDarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = ShellLightTextPrimary,
    onPrimary = ShellLightSurface,
    secondary = ShellLightAccent,
    onSecondary = ShellLightSurface,
    tertiary = ShellLightAccent,
    background = ShellLightBackground,
    onBackground = ShellLightTextPrimary,
    surface = ShellLightSurface,
    onSurface = ShellLightTextPrimary,
    surfaceVariant = ShellLightSurfaceElevated,
    onSurfaceVariant = ShellLightTextSecondary,
    outline = ShellLightBorder
)

@Composable
fun ShellfriTheme(
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
