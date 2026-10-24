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

// 阿卡丽经典原皮：近黑的紫底、血红主色、紫色辅助色
val Crimson = Color(0xFFC8283D)
val Violet = Color(0xFF6B4FA0)

private val LightColors = lightColorScheme(
    primary = Crimson,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFADADF),
    onPrimaryContainer = Color(0xFF6E0E1C),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E0F4),
    onSecondaryContainer = Color(0xFF2E1C57),
    background = Color(0xFFF5F2F7),
    onBackground = Color(0xFF1B1722),
    surface = Color.White,
    onSurface = Color(0xFF1B1722),
    surfaceVariant = Color(0xFFEBE6F0),
    onSurfaceVariant = Color(0xFF625B70),
    outline = Color(0xFFCCC5D6),
    outlineVariant = Color(0xFFE4DEEB),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0566A),
    onPrimary = Color(0xFF4A0612),
    primaryContainer = Color(0xFF6E1626),
    onPrimaryContainer = Color(0xFFFFDADF),
    secondary = Color(0xFFB59CE6),
    onSecondary = Color(0xFF2A1855),
    secondaryContainer = Color(0xFF3F2D6B),
    onSecondaryContainer = Color(0xFFE8DDFF),
    background = Color(0xFF15121A),
    onBackground = Color(0xFFE9E4EE),
    surface = Color(0xFF201C27),
    onSurface = Color(0xFFE9E4EE),
    surfaceVariant = Color(0xFF2C2735),
    onSurfaceVariant = Color(0xFFA9A2B8),
    outline = Color(0xFF4D4659),
    outlineVariant = Color(0xFF383241),
    error = Color(0xFFFF8A80),
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
