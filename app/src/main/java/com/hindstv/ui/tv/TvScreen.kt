package com.hindstv.ui.tv

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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.hindstv.ui.TimeshiftUi
import com.hindstv.ui.components.ChannelLogo
import com.hindstv.ui.components.ChannelWordmark
import com.hindstv.ui.components.Clock
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.VideoSurface
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
            Overlay.Guide -> GuideOverlay(vm, onNavigate)
            Overlay.Recent -> RecentStrip(vm)
            Overlay.None -> Unit
        }
        toast?.let { Toast(it, Modifier.align(Alignment.TopCenter)) }
    }

    // Back in full screen is handled by MainActivity (press twice to exit).

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

/** Bottom info bar: channel, now/next with progress, clock and tune time, then the keys. */
@Composable
private fun InfoBanner(vm: AppViewModel, playback: PlaybackUi, modifier: Modifier = Modifier) {
    val channel = playback.channel ?: return
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val minute by vm.minute.collectAsStateWithLifecycle()
    val now = nowNext.firstOrNull()?.takeIf { it.start <= minute }
    val next = nowNext.firstOrNull { it !== now }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.42f to HindsColors.Background.copy(alpha = 0.92f), 1f to HindsColors.Background))
            .padding(start = 48.dp, end = 48.dp, top = 78.dp, bottom = 26.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChannelWordmark(
                channel,
                Modifier.size(width = 112.dp, height = 72.dp).background(HindsColors.Plate, HindsShapes.Small).padding(10.dp),
                initialsSize = 22.sp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (channel.number > 0) {
                        Text(channel.number.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HindsColors.Accent)
                    }
                    Text(
                        channel.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.isFavorite) Text("★", fontSize = 20.sp, color = HindsColors.Accent)
                }
                if (now != null) {
                    ProgrammeLine(stringResource(R.string.now), now, emphasised = true)
                    ProgressLine(now.progress(minute), Modifier.width(520.dp))
                }
                next?.let { ProgrammeLine(stringResource(R.string.next), it, emphasised = false) }
                if (now == null && next == null) {
                    Text(stringResource(R.string.banner_no_guide), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted)
                }
                val timeshift by vm.timeshift.collectAsStateWithLifecycle()
                timeshift?.let { TimeshiftLine(it) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(formatTime(minute), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
                // Speed check for the owner: time from the button press to the picture.
                val tuneMs by vm.tuneMs.collectAsStateWithLifecycle()
                tuneMs?.let {
                    Text(stringResource(R.string.tuned_in, it / 1000.0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HindsColors.Faint)
                }
            }
        }
        val canRewind = vm.timeshift.collectAsStateWithLifecycle().value != null
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            stringArrayResource(if (canRewind) R.array.banner_keys_rewind else R.array.banner_keys).forEach {
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HindsColors.Muted, maxLines = 1)
            }
        }
    }
}

/** LIVE, or how far behind live (and paused); a pending ⏪/⏩ shows as −0:30 / +0:30. */
@Composable
private fun TimeshiftLine(state: TimeshiftUi) {
    val atLive = state.behindMs < 3_000 && !state.paused && state.pendingSkipMs == 0L
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (atLive) stringResource(R.string.timeshift_live) else if (state.paused) "⏸" else "◷",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = HindsColors.OnAccent,
            modifier = Modifier.background(if (atLive) HindsColors.Error else HindsColors.Accent, HindsShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (!atLive) {
            val behind = stringResource(R.string.timeshift_behind, formatDuration(state.behindMs))
            Text(
                listOfNotNull(
                    if (state.paused) stringResource(R.string.timeshift_paused) else null,
                    behind,
                    state.pendingSkipMs.takeIf { it != 0L }?.let { (if (it < 0) "−" else "+") + formatDuration(kotlin.math.abs(it)) },
                ).joinToString("  ·  "),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = HindsColors.Muted,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun ProgrammeLine(label: String, programme: ProgrammeEntity, emphasised: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = if (emphasised) HindsColors.Accent else HindsColors.Faint,
        )
        Text(
            if (emphasised) programme.timeRange() else formatTime(programme.start),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = HindsColors.Muted,
        )
        Text(
            programme.title,
            fontSize = if (emphasised) 16.sp else 14.sp,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.SemiBold,
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
