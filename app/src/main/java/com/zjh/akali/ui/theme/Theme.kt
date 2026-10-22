package com.zjh.akali.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Indigo = Color(0xFF4F5BD5)
val Teal = Color(0xFF1E9E9A)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E4FB),
    onPrimaryContainer = Color(0xFF232B8F),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F1EF),
    onSecondaryContainer = Color(0xFF0B5F5C),
    background = Color(0xFFF3F4F9),
    onBackground = Color(0xFF181A24),
    surface = Color.White,
    onSurface = Color(0xFF181A24),
    surfaceVariant = Color(0xFFE9EAF2),
    onSurfaceVariant = Color(0xFF5E6275),
    outline = Color(0xFFC6C9D6),
    outlineVariant = Color(0xFFE1E3EC),
    error = Color(0xFFE5484D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C96F2),
    onPrimary = Color(0xFF101450),
    primaryContainer = Color(0xFF2B3070),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFF4CC3BE),
    onSecondary = Color(0xFF00302E),
    secondaryContainer = Color(0xFF17413F),
    onSecondaryContainer = Color(0xFFC7F0EE),
    background = Color(0xFF14151C),
    onBackground = Color(0xFFE5E6EE),
    surface = Color(0xFF1E2029),
    onSurface = Color(0xFFE5E6EE),
    surfaceVariant = Color(0xFF292C38),
    onSurfaceVariant = Color(0xFFA3A7B8),
    outline = Color(0xFF474A5A),
    outlineVariant = Color(0xFF323544),
    error = Color(0xFFFF6B70),
)

private val AkaliShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AkaliTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AkaliShapes,
        content = content,
    )
}
