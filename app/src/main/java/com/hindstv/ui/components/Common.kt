package com.hindstv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

fun formatTime(epochSec: Long): String = TimeFormat.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

fun formatDay(epochSec: Long): String = DayFormat.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

fun ProgrammeEntity.timeRange(): String = "${formatTime(start)} – ${formatTime(stop)}"

fun ProgrammeEntity.progress(nowSec: Long): Float =
    if (stop <= start) 0f else ((nowSec - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

/** Wall clock, updated by the caller once a minute. */
@Composable
fun Clock(nowSec: Long, modifier: Modifier = Modifier) {
    Text(formatTime(nowSec), style = MaterialTheme.typography.titleMedium, color = HindsColors.Text, modifier = modifier)
}

/** A thin progress line (programme elapsed time). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, color: Color = HindsColors.Accent) {
    Box(
        modifier
            .height(height)
            .clip(HindsShapes.Pill)
            .background(HindsColors.Hairline),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color),
        )
    }
}

/** Channel logo in a rounded tile; initials when the provider has no logo. */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(HindsShapes.Small)
            .background(HindsColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            // Coil sizes the decode to this box, so large portal logos never hit memory at full size.
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxWidth())
        } else {
            val initials = remember(name) { initials(name) }
            Text(initials, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = HindsColors.Muted)
        }
    }
}

private fun initials(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
