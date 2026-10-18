package com.hindstv.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.Overlay
import com.hindstv.ui.PlayStatus
import com.hindstv.ui.PlaybackUi
import com.hindstv.ui.components.ChannelLogo
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.VideoSurface
import com.hindstv.ui.components.Clock
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.progress
import com.hindstv.ui.components.timeRange
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

@Composable
fun TvScreen(vm: AppViewModel, onNavigate: (NavSection) -> Unit) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (playback.channel != null) VideoLayer(vm.player, playback)
        when (overlay) {
            Overlay.Banner -> InfoBanner(vm, playback, Modifier.align(Alignment.BottomCenter))
            Overlay.ChannelList -> ChannelListOverlay(vm, onNavigate)
            Overlay.Guide -> GuideOverlay(vm)
            Overlay.None -> Unit
        }
        toast?.let { Toast(it, Modifier.align(Alignment.TopCenter)) }
    }

    // Back in full screen is handled by MainActivity (press twice to exit).
    BackHandler(enabled = overlay == Overlay.ChannelList && playback.channel != null) { vm.hideChannelList() }
    BackHandler(enabled = overlay == Overlay.Guide) { vm.closeGuide() }
}

@Composable
private fun VideoLayer(player: Player, playback: PlaybackUi) {
    Box(Modifier.fillMaxSize()) {
        VideoSurface(player)
        when (playback.status) {
            PlayStatus.Tuning, PlayStatus.Buffering -> StatusPill(stringResource(R.string.tuning), Modifier.align(Alignment.Center))
            PlayStatus.Stopped -> StatusPill(stringResource(R.string.stopped), Modifier.align(Alignment.Center))
            PlayStatus.Error -> ErrorCard(playback.error.orEmpty(), Modifier.align(Alignment.Center))
            PlayStatus.Idle, PlayStatus.Playing -> Unit
        }
    }
}

/** Bottom info bar: channel, now/next with progress, clock. */
@Composable
private fun InfoBanner(vm: AppViewModel, playback: PlaybackUi, modifier: Modifier = Modifier) {
    val channel = playback.channel ?: return
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val minute by vm.minute.collectAsStateWithLifecycle()
    val now = nowNext.firstOrNull()?.takeIf { it.start <= minute }
    val next = nowNext.firstOrNull { it !== now }

    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, HindsColors.Background.copy(alpha = 0.92f), HindsColors.Background)))
            .padding(start = 56.dp, end = 56.dp, top = 72.dp, bottom = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 112.dp, height = 72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (channel.number > 0) {
                        Text(channel.number.toString(), style = MaterialTheme.typography.titleLarge, color = HindsColors.Accent)
                    }
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (channel.isFavorite) Text("★", style = MaterialTheme.typography.titleLarge, color = HindsColors.Accent)
                }
                if (now != null) {
                    ProgrammeLine(stringResource(R.string.now), now, emphasised = true)
                    ProgressLine(now.progress(minute), Modifier.width(520.dp))
                }
                next?.let { ProgrammeLine(stringResource(R.string.next), it, emphasised = false) }
                if (now == null && next == null) {
                    Text(stringResource(R.string.banner_hint), style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted)
                }
            }
            Column(Modifier.align(Alignment.Top), horizontalAlignment = Alignment.End) {
                Clock(minute)
                // Speed check for the owner: time from the button press to the picture.
                val tuneMs by vm.tuneMs.collectAsStateWithLifecycle()
                tuneMs?.let {
                    Text(stringResource(R.string.tuned_in, it / 1000.0), style = MaterialTheme.typography.labelSmall, color = HindsColors.Faint)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeLine(label: String, programme: ProgrammeEntity, emphasised: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (emphasised) HindsColors.Accent else HindsColors.Faint)
        Text(
            if (emphasised) programme.timeRange() else formatTime(programme.start),
            style = MaterialTheme.typography.bodyMedium,
            color = HindsColors.Muted,
        )
        Text(
            programme.title,
            style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasised) HindsColors.Text else HindsColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(HindsColors.Panel, HindsShapes.Pill)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier, hint: String = stringResource(R.string.error_retry_hint)) {
    Column(
        modifier = modifier
            .width(560.dp)
            .background(HindsColors.Panel, HindsShapes.Medium)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = HindsColors.Error)
        Spacer(Modifier.height(2.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted)
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier
            .padding(top = 32.dp)
            .background(HindsColors.SurfaceRaised, HindsShapes.Pill)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}
