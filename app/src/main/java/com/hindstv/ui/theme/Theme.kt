package com.hindstv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.tv.material3.lightColorScheme

/**
 * Two palettes: "Cinema" (dark, inspired by Apple TV and Plex: near-black, one warm gold accent) and
 * "Daylight" (light: warm off-white, one blue accent). Both use a solid focus fill with contrasting
 * text, the most legible focus state from a sofa. No shadows, blur or scale animations — the stick's
 * GPU is weak.
 */
@Immutable
data class HindsPalette(
    val isLight: Boolean,
    val background: Color,
    val panel: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val focus: Color,
    val onFocus: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val error: Color,
    val live: Color,
    val chip: Color,
    val chipLine: Color,
    val plate: Color,
    val track: Color,
    val dim: Color,
)

val DarkPalette = HindsPalette(
    isLight = false,
    background = Color(0xFF0B0B0D),
    panel = Color(0xF20B0B0D),
    surface = Color(0xFF141418),
    surfaceRaised = Color(0xFF1F1F25),
    hairline = Color(0xFF2A2A31),
    focus = Color(0xFFF5F5F7),
    onFocus = Color(0xFF0B0B0D),
    accent = Color(0xFFF2B33D),
    onAccent = Color(0xFF0B0B0D),
    text = Color(0xFFF5F5F7),
    muted = Color(0xFFA1A1AA),
    faint = Color(0xFF8A8A94),
    error = Color(0xFFFF6B5B),
    live = Color(0xFFFF5A4E),
    chip = Color.White.copy(alpha = 0.10f),
    chipLine = Color.White.copy(alpha = 0.14f),
    plate = Color(0xFF1F1F25),
    track = Color.White.copy(alpha = 0.18f),
    dim = Color.Black.copy(alpha = 0.6f),
)

private val Ink = Color(0xFF14161B)

val LightPalette = HindsPalette(
    isLight = true,
    background = Color(0xFFF3F2EE),
    panel = Color(0xF7F6F5F1),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE6E7EB),
    hairline = Color(0xFFD8D9DF),
    focus = Ink,
    onFocus = Color(0xFFFFFFFF),
    accent = Color(0xFF2F4FC4),
    onAccent = Color(0xFFFFFFFF),
    text = Ink,
    muted = Color(0xFF555A66),
    faint = Color(0xFF6B707C),
    error = Color(0xFFC8372D),
    live = Color(0xFFD93A2F),
    chip = Ink.copy(alpha = 0.06f),
    chipLine = Ink.copy(alpha = 0.14f),
    plate = Color(0xFFFFFFFF),
    track = Ink.copy(alpha = 0.14f),
    dim = Ink.copy(alpha = 0.45f),
)

/** Static: a theme switch is rare and should redraw everything at once, with no animation. */
val LocalHindsPalette = staticCompositionLocalOf { DarkPalette }

object HindsColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.background
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.panel
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.surface
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.surfaceRaised
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.hairline
    val Focus: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.focus
    val OnFocus: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.onFocus
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.onAccent
    val Text: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.muted
    val Faint: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.faint
    val Error: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.error
    val Live: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.live
    val Chip: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.chip
    val ChipLine: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.chipLine
    val Plate: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.plate
    val Track: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.track
    val Dim: Color @Composable @ReadOnlyComposable get() = LocalHindsPalette.current.dim
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
    val Card = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(50)
}

@Composable
fun HindsTheme(light: Boolean = false, content: @Composable () -> Unit) {
    val p = if (light) LightPalette else DarkPalette
    val typography = remember { manropeTypography() }
    val scheme = remember(p) {
        if (p.isLight) {
            lightColorScheme(
                primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text,
                surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted,
                inverseSurface = p.focus, inverseOnSurface = p.onFocus, error = p.error,
            )
        } else {
            darkColorScheme(
                primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text,
                surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted,
                inverseSurface = p.focus, inverseOnSurface = p.onFocus, error = p.error,
            )
        }
    }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Text outside a tv-material Surface would otherwise default to black.
        CompositionLocalProvider(LocalHindsPalette provides p, LocalContentColor provides p.text, content = content)
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
