package com.airdrop.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF), onPrimaryContainer = Color(0xFF00205F),
    secondary = Color(0xFF006A60), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5E8), onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF8B5000), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8), onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
    surfaceContainerLow = Color(0xFFF1F4F9), surfaceContainer = Color(0xFFE8EDF4),
    surfaceContainerHigh = Color(0xFFE2E8F1), surfaceContainerHighest = Color(0xFFD8DFEA),
    onSurface = Color(0xFF191C20), onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C6D0),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB0C6FF), onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF1B3F85), onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFF4FE3CD), onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0A4A40), onSecondaryContainer = Color(0xFFB8F5E8),
    tertiary = Color(0xFFFFB86B), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00), onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF111318), surface = Color(0xFF111318),
    surfaceContainerLow = Color(0xFF191C22), surfaceContainer = Color(0xFF1E2128),
    surfaceContainerHigh = Color(0xFF282A31), surfaceContainerHighest = Color(0xFF33353C),
    onSurface = Color(0xFFE2E2E9), onSurfaceVariant = Color(0xFFC3C6D0),
    outline = Color(0xFF8D9199), outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AirDropTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val base = Typography()
    val type = base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
    MaterialTheme(
        colorScheme = when {
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            dark -> Dark
            else -> Light
        },
        shapes = AppShapes,
        typography = type,
        content = content,
    )
}
