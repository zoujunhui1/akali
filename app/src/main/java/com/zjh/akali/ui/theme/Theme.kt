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

val BiliPink = Color(0xFFFB7299)
val BiliBlue = Color(0xFF23ADE5)

private val LightColors = lightColorScheme(
    primary = BiliPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3EC),
    onPrimaryContainer = Color(0xFF9C2B52),
    secondary = BiliBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3FC),
    onSecondaryContainer = Color(0xFF0B6A93),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF18191C),
    surface = Color.White,
    onSurface = Color(0xFF18191C),
    surfaceVariant = Color(0xFFEDEEF0),
    onSurfaceVariant = Color(0xFF61666D),
    outline = Color(0xFFC9CCD0),
    outlineVariant = Color(0xFFE3E5E7),
    error = Color(0xFFE5484D),
)

private val DarkColors = darkColorScheme(
    primary = BiliPink,
    onPrimary = Color(0xFF3B0A1D),
    primaryContainer = Color(0xFF4A2432),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = BiliBlue,
    onSecondary = Color(0xFF00232F),
    secondaryContainer = Color(0xFF123B4D),
    onSecondaryContainer = Color(0xFFCBEEFB),
    background = Color(0xFF17181A),
    onBackground = Color(0xFFE6E7E9),
    surface = Color(0xFF232427),
    onSurface = Color(0xFFE6E7E9),
    surfaceVariant = Color(0xFF2E3033),
    onSurfaceVariant = Color(0xFFA5A8AD),
    outline = Color(0xFF4A4D52),
    outlineVariant = Color(0xFF34363A),
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
