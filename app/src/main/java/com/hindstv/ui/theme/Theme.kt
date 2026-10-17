package com.hindstv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object HindsColors {
    val Background = Color(0xFF0D1117)
    val Panel = Color(0xF20D1117)
    val Surface = Color(0xFF161B22)
    val Accent = Color(0xFF00A8E1)
    val Text = Color(0xFFF0F4F8)
    val Muted = Color(0xFF8B949E)
    val Error = Color(0xFFFF7B72)
}

@Composable
fun HindsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HindsColors.Accent,
            onPrimary = Color.White,
            background = HindsColors.Background,
            onBackground = HindsColors.Text,
            surface = HindsColors.Surface,
            onSurface = HindsColors.Text,
            onSurfaceVariant = HindsColors.Muted,
            error = HindsColors.Error,
        ),
    ) {
        // Text outside a tv-material Surface would otherwise default to black.
        CompositionLocalProvider(LocalContentColor provides HindsColors.Text, content = content)
    }
}
