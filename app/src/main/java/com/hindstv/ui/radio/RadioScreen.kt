package com.hindstv.ui.radio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.RadioEntity
import com.hindstv.ui.PlayStatus
import com.hindstv.ui.RadioUi
import com.hindstv.ui.RadioViewModel
import com.hindstv.ui.components.Artwork
import com.hindstv.ui.components.KeepScreenOn
import com.hindstv.ui.components.LevelBars
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.pageJumpItem
import com.hindstv.ui.components.rememberPageJump
import com.hindstv.ui.components.focusRing
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/** Radio: now playing on top, the stations in a grid below. Back returns to live TV. */
@Composable
fun RadioScreen(vm: RadioViewModel, onNavigate: (NavSection) -> Unit, onExit: () -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val screenOff by vm.screenOff.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    val navBar = rememberNavBarState()

    // The screensaver would stop the app (and the music); "Screen off" below is the way to dim.
    if (playing != null) KeepScreenOn()

    Column(Modifier.fillMaxSize().background(HindsColors.Background).padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.radio_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TopNav(NavSection.Radio, onNavigate, state = navBar)
        }
        NowPlaying(vm, playing, Modifier.padding(horizontal = 48.dp, vertical = 20.dp))
        Text(
            stringResource(R.string.stations),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 48.dp).padding(bottom = 10.dp),
        )
        val list = stations
        val gridState = rememberLazyGridState()
        val jump = rememberPageJump(list?.size ?: 0, page = RADIO_COLUMNS * 2) { gridState.scrollToItem(it) }
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                stringResource(R.string.no_radio),
                style = MaterialTheme.typography.bodyMedium,
                color = HindsColors.Muted,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(RADIO_COLUMNS),
                state = gridState,
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().then(jump.modifier),
            ) {
                itemsIndexed(list, key = { _, s -> s.id }) { index, station ->
                    StationTile(
                        station,
                        playing = playing?.station?.id == station.id,
                        onClick = { vm.play(station) },
                        modifier = Modifier.pageJumpItem(jump, index).then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    )
                }
            }
        }
    }

    if (screenOff) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
            Text(
                stringResource(R.string.screen_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = HindsColors.Faint.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    NavBackHandler(navBar, enabled = !screenOff, onExit = onExit)
    LaunchedEffect(Unit) { navBar.focus.requestFocusSoon() }
    LaunchedEffect(stations.isNullOrEmpty()) {
        if (!stations.isNullOrEmpty()) firstFocus.requestFocusSoon()
    }
}

@Composable
private fun NowPlaying(vm: RadioViewModel, playing: RadioUi?, modifier: Modifier) {
    // Compact, so more of the station grid fits on screen.
    Row(modifier.height(112.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        val station = playing?.station
        Artwork(station?.logo, station?.name ?: "♪", Modifier.size(112.dp).clip(HindsShapes.Large))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playing == null || station == null) {
                Text(stringResource(R.string.radio_pick), style = MaterialTheme.typography.titleLarge, color = HindsColors.Muted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.on_air).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = HindsColors.OnAccent,
                    modifier = Modifier.background(HindsColors.Accent, HindsShapes.Pill).padding(horizontal = 10.dp, vertical = 3.dp),
                )
                if (station.number > 0) Text(station.number.toString(), style = MaterialTheme.typography.labelLarge, color = HindsColors.Muted)
            }
            Text(station.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when (playing.status) {
                PlayStatus.Tuning, PlayStatus.Buffering -> stringResource(R.string.tuning)
                PlayStatus.Error -> playing.error.orEmpty()
                PlayStatus.Stopped -> null
                else -> playing.nowPlaying
            }
            line?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (playing.status == PlayStatus.Error) HindsColors.Error else HindsColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playing.status == PlayStatus.Playing) LevelBars()
                val stopped = playing.status == PlayStatus.Stopped || playing.status == PlayStatus.Error
                PillButton(
                    stringResource(if (stopped) R.string.radio_play else R.string.radio_stop),
                    onClick = vm::togglePlay,
                    primary = true,
                )
                if (!stopped) PillButton(stringResource(R.string.screen_off), onClick = { vm.setScreenOff(true) })
            }
        }
    }
}

@Composable
private fun StationTile(station: RadioEntity, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(HindsShapes.Large),
            colors = ClickableSurfaceDefaults.colors(containerColor = HindsColors.Surface, focusedContainerColor = HindsColors.Surface),
            border = focusRing(
                focused,
                HindsShapes.Large,
                idle = if (playing) Border(BorderStroke(2.dp, HindsColors.Accent), shape = HindsShapes.Large) else Border.None,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Artwork(station.logo, station.name, Modifier.fillMaxSize().clip(HindsShapes.Large))
        }
        Text(
            station.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (playing) HindsColors.Accent else HindsColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val RADIO_COLUMNS = 7
