package com.hindstv.ui.components

import androidx.compose.foundation.background
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
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
            .background(HindsColors.Track),
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

/** The video picture, letterboxed, keeping the screen awake while it is on screen. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player) {
    val presentation = rememberPresentationState(player)
    KeepScreenOn()
    Box(Modifier.fillMaxSize()) {
        // SurfaceView, not TextureView: lower power, better frame timing, HDR-capable.
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )
        if (presentation.coverSurface) Box(Modifier.fillMaxSize().background(Color.Black))
        // Cue positions are fractions of the picture, so the layer covers exactly the picture.
        SubtitleLayer(
            player,
            Modifier.align(Alignment.Center).resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )
    }
}

/**
 * Subtitles drawn by us instead of media3-ui's SubtitleView (one less dependency): text cues
 * along the bottom, picture cues (DVB, PGS) where the broadcaster placed them.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SubtitleLayer(player: Player, modifier: Modifier) {
    var cues by remember(player) { mutableStateOf(emptyList<Cue>()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            cues = emptyList()
        }
    }
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        cues.filter { it.bitmap != null }.forEach { cue ->
            val bitmap = cue.bitmap ?: return@forEach
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val width = if (cue.size != Cue.DIMEN_UNSET) w * cue.size else w * 0.8f
            val height = if (cue.bitmapHeight != Cue.DIMEN_UNSET) h * cue.bitmapHeight else width * (bitmap.height.toFloat() / bitmap.width)
            val x = (if (cue.position != Cue.DIMEN_UNSET) w * cue.position else w * 0.5f) - width * anchorShift(cue.positionAnchor, Cue.ANCHOR_TYPE_MIDDLE)
            val y = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) {
                h * cue.line - height * anchorShift(cue.lineAnchor, Cue.ANCHOR_TYPE_START)
            } else {
                h * 0.92f - height
            }
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(x, y).size(width, height),
            )
        }
        val lines = cues.mapNotNull { if (it.bitmap == null) it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) else null }
        if (lines.isNotEmpty()) {
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = h * 0.06f, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), HindsShapes.Small)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** How far back from its position a cue starts, as a fraction of its size. */
@OptIn(UnstableApi::class)
private fun anchorShift(anchor: Int, default: Int): Float = when (if (anchor == Cue.TYPE_UNSET) default else anchor) {
    Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
    Cue.ANCHOR_TYPE_END -> 1f
    else -> 0f
}

/** Stops the TV's screensaver (which would also stop playback) while this is composed. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
