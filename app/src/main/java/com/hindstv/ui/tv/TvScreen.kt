package com.hindstv.ui.tv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.Overlay
import com.hindstv.ui.PlayStatus
import com.hindstv.ui.PlaybackUi
import com.hindstv.ui.theme.HindsColors

@Composable
fun TvScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (playback.channel != null) VideoLayer(vm.player, playback)
        when (overlay) {
            Overlay.Banner -> ChannelBanner(playback, Modifier.align(Alignment.BottomCenter))
            Overlay.ChannelList -> ChannelListOverlay(vm)
            Overlay.None -> Unit
        }
        toast?.let { Toast(it, Modifier.align(Alignment.TopCenter)) }
    }

    // Back in full screen is handled by MainActivity (press twice to exit).
    BackHandler(enabled = overlay == Overlay.ChannelList && playback.channel != null) { vm.hideChannelList() }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(player: Player, playback: PlaybackUi) {
    val presentation = rememberPresentationState(player)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
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

        when (playback.status) {
            PlayStatus.Tuning, PlayStatus.Buffering -> StatusPill(stringResource(R.string.tuning), Modifier.align(Alignment.Center))
            PlayStatus.Stopped -> StatusPill(stringResource(R.string.stopped), Modifier.align(Alignment.Center))
            PlayStatus.Error -> ErrorCard(playback.error.orEmpty(), Modifier.align(Alignment.Center))
            PlayStatus.Idle, PlayStatus.Playing -> Unit
        }
    }
}

@Composable
private fun ChannelBanner(playback: PlaybackUi, modifier: Modifier = Modifier) {
    val channel = playback.channel ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HindsColors.Panel)
            .padding(horizontal = 48.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (channel.number > 0) {
            Text(channel.number.toString(), style = MaterialTheme.typography.headlineMedium, color = HindsColors.Accent)
        }
        ChannelLogo(channel.logo, Modifier.size(width = 72.dp, height = 48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name + if (channel.isFavorite) "  ★" else "",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "▲▼ change channel   ◀ / Select channel list   ☰ favourite   ⏪ previous channel",
                style = MaterialTheme.typography.bodySmall,
                color = HindsColors.Muted,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(HindsColors.Panel, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(560.dp)
            .background(HindsColors.Panel, RoundedCornerShape(16.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, color = HindsColors.Error)
        Text(
            "Press ⏯ to try again, or Select to pick another channel.",
            style = MaterialTheme.typography.bodyMedium,
            color = HindsColors.Muted,
        )
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier
            .padding(top = 32.dp)
            .background(HindsColors.Panel, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}
