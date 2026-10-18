package com.hindstv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.hindstv.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * "Cinema" (inspired by Apple TV and Plex): near-black, one warm gold accent, big confident type,
 * and a white focus fill with dark text (the most legible focus state from a sofa). No shadows,
 * blur or scale animations — the stick's GPU is weak.
 */
object HindsColors {
    val Background = Color(0xFF0B0B0D)
    val Panel = Color(0xF20B0B0D)
    val Surface = Color(0xFF141418)
    val SurfaceRaised = Color(0xFF1F1F25)
    val Hairline = Color(0xFF2A2A31)
    val Focus = Color(0xFFF5F5F7)
    val OnFocus = Color(0xFF0B0B0D)
    val Accent = Color(0xFFF2B33D)
    val OnAccent = Color(0xFF0B0B0D)
    val Text = Color(0xFFF5F5F7)
    val Muted = Color(0xFFA1A1AA)
    val Faint = Color(0xFF6E6E78)
    val Error = Color(0xFFFF6B5B)
}

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private fun manropeTypography(): Typography {
    val base = Typography()
    fun TextStyle.m() = copy(fontFamily = Manrope)
    return Typography(
        displayLarge = base.displayLarge.m(),
        displayMedium = base.displayMedium.m(),
        displaySmall = base.displaySmall.m(),
        headlineLarge = base.headlineLarge.m().copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.m().copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.m().copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.m().copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.m().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.m().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.m(),
        bodyMedium = base.bodyMedium.m(),
        bodySmall = base.bodySmall.m(),
        labelLarge = base.labelLarge.m().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.m().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelSmall = base.labelSmall.m().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    )
}

object HindsShapes {
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
}

@Composable
fun HindsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HindsColors.Accent,
            onPrimary = HindsColors.OnAccent,
            background = HindsColors.Background,
            onBackground = HindsColors.Text,
            surface = HindsColors.Surface,
            onSurface = HindsColors.Text,
            onSurfaceVariant = HindsColors.Muted,
            inverseSurface = HindsColors.Focus,
            inverseOnSurface = HindsColors.OnFocus,
            error = HindsColors.Error,
        ),
        typography = manropeTypography(),
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
