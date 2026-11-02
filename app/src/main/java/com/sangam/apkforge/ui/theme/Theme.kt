package com.sangam.apkforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Deep teal with an amber accent: a workshop feel rather than the stock Material look.
private val Teal900 = Color(0xFF0F3D3E)
private val Teal700 = Color(0xFF1F6F6B)
private val Teal200 = Color(0xFF8CCFC7)
private val Amber = Color(0xFFE9A23B)
private val Paper = Color(0xFFF7F5F0)
private val Ink = Color(0xFF1B1F1E)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3ECE8),
    onPrimaryContainer = Teal900,
    secondary = Color(0xFF8A5A12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE3BE),
    onSecondaryContainer = Color(0xFF3A2500),
    tertiary = Amber,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E3DA),
    onSurfaceVariant = Color(0xFF4A4F4D),
    surfaceContainer = Color(0xFFEFECE5),
    surfaceContainerHigh = Color(0xFFE9E6DE),
    outline = Color(0xFF7A807D),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Teal900,
    primaryContainer = Color(0xFF1C4A48),
    onPrimaryContainer = Color(0xFFCDEDE8),
    secondary = Color(0xFFF2C27A),
    onSecondary = Color(0xFF3A2500),
    secondaryContainer = Color(0xFF5A3E10),
    onSecondaryContainer = Color(0xFFFBE3BE),
    tertiary = Amber,
    background = Color(0xFF121615),
    onBackground = Color(0xFFE2E3E1),
    surface = Color(0xFF121615),
    onSurface = Color(0xFFE2E3E1),
    surfaceVariant = Color(0xFF2B302E),
    onSurfaceVariant = Color(0xFFBFC5C2),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF242A28),
    outline = Color(0xFF8A908D),
    error = Color(0xFFF2B8B5),
)

// Modest corners everywhere instead of fully rounded "pill" buttons.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun ApkForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
