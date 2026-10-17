package com.hindstv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * "Modern minimal": near-black surfaces, one accent colour, and a light focus fill with dark text
 * (the most legible focus state from a sofa). No shadows, blur or scale animations — the stick's
 * GPU is weak.
 */
object HindsColors {
    val Background = Color(0xFF0B0E13)
    val Panel = Color(0xF00B0E13)
    val Surface = Color(0xFF151A21)
    val SurfaceRaised = Color(0xFF1D232C)
    val Hairline = Color(0xFF262D37)
    val Focus = Color(0xFFE9EEF3)
    val OnFocus = Color(0xFF0B0E13)
    val Accent = Color(0xFF00A8E1)
    val Text = Color(0xFFECF1F5)
    val Muted = Color(0xFF8B95A3)
    val Faint = Color(0xFF5B6573)
    val Error = Color(0xFFFF7A7A)
}

object HindsShapes {
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(50)
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
            inverseSurface = HindsColors.Focus,
            inverseOnSurface = HindsColors.OnFocus,
            error = HindsColors.Error,
        ),
    ) {
        // Text outside a tv-material Surface would otherwise default to black.
        CompositionLocalProvider(LocalContentColor provides HindsColors.Text, content = content)
    }
}

/** Shared focus styling so every list and button looks and behaves the same. */
object HindsStyle {
    @Composable
    fun listItemColors(selectedColor: Color = HindsColors.Accent) = ListItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = HindsColors.Text,
        focusedContainerColor = HindsColors.Focus,
        focusedContentColor = HindsColors.OnFocus,
        selectedContainerColor = HindsColors.SurfaceRaised,
        selectedContentColor = selectedColor,
        focusedSelectedContainerColor = HindsColors.Focus,
        focusedSelectedContentColor = HindsColors.OnFocus,
    )

    @Composable
    fun listItemShape() = ListItemDefaults.shape(HindsShapes.Medium)

    @Composable
    fun noScale() = ListItemDefaults.scale(focusedScale = 1f)

    @Composable
    fun surfaceColors(container: Color = HindsColors.Surface) = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = HindsColors.Text,
        focusedContainerColor = HindsColors.Focus,
        focusedContentColor = HindsColors.OnFocus,
    )
}
