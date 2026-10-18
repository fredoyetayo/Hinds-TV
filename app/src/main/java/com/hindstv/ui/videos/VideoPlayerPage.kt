package com.hindstv.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.ui.PlayStatus
import com.hindstv.ui.VideoViewModel
import com.hindstv.ui.VodUi
import com.hindstv.ui.components.Artwork
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.VideoSurface
import com.hindstv.ui.components.formatTimecode
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.tv.ErrorCard
import com.hindstv.ui.tv.StatusPill

/**
 * Full-screen video. Nothing here takes focus: MainActivity sends the remote's keys to
 * [VideoViewModel.onPlayerKey], so focus can never get lost over the picture.
 */
@Composable
fun VideoPlayerPage(vm: VideoViewModel) {
    val vod by vm.vod.collectAsStateWithLifecycle()
    val current = vod
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(vm.player)
        if (current == null) return@Box
        when (current.status) {
            PlayStatus.Tuning, PlayStatus.Buffering ->
                StatusPill(stringResource(R.string.loading_video), Modifier.align(Alignment.Center))
            PlayStatus.Error -> ErrorCard(
                current.error.orEmpty(),
                Modifier.align(Alignment.Center),
                hint = stringResource(R.string.video_retry_hint),
            )
            else -> if (current.paused) StatusPill(stringResource(R.string.paused), Modifier.align(Alignment.Center))
        }
        if (current.controlsVisible) Controls(vm, current, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Controls(vm: VideoViewModel, vod: VodUi, modifier: Modifier) {
    val next = vm.nextEpisodeAfter(vod.episode)
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, HindsColors.Background.copy(alpha = 0.92f), HindsColors.Background)))
            .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = listOfNotNull(vod.item.title.takeIf { vod.episode != null }, vod.episode?.season)
                    .joinToString(" · ")
                if (label.isNotEmpty()) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = HindsColors.Muted, maxLines = 1)
                }
                Text(
                    vod.episode?.title ?: vod.item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (next != null) {
                Row(
                    Modifier
                        .width(260.dp)
                        .background(HindsColors.SurfaceRaised.copy(alpha = 0.9f), HindsShapes.Large)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(vod.item.poster, next.title, Modifier.size(width = 72.dp, height = 40.dp).clip(HindsShapes.Small))
                    Column {
                        Text(stringResource(R.string.up_next).uppercase(), style = MaterialTheme.typography.labelSmall, color = HindsColors.Muted)
                        Text(next.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Scrubber(vm)
        Text(stringResource(R.string.vod_hint), style = MaterialTheme.typography.bodySmall, color = HindsColors.Faint)
    }
}

/** Elapsed — bar — remaining. Reads the ticking position here only, so nothing else recomposes. */
@Composable
private fun Scrubber(vm: VideoViewModel) {
    val position by vm.position.collectAsStateWithLifecycle()
    val duration = position.durationMs
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(formatTimecode(position.positionMs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ProgressLine(
            if (duration > 0) position.positionMs.toFloat() / duration else 0f,
            Modifier.weight(1f),
            height = 6.dp,
            color = HindsColors.Focus,
        )
        Text(
            if (duration > 0) "−" + formatTimecode(duration - position.positionMs) else "",
            style = MaterialTheme.typography.titleSmall,
            color = HindsColors.Muted,
        )
    }
}
