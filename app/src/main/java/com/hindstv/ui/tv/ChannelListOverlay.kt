package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.GenreItem
import com.hindstv.ui.SyncUi
import com.hindstv.ui.components.ChannelLogo
import com.hindstv.ui.components.Clock
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.progress
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsStyle

/**
 * Groups on the left, channels on the right, drawn over the playing video. Opens focused on the
 * playing channel. Lists are lazy and keyed, so 10k+ channels stay smooth.
 */
@Composable
fun ChannelListOverlay(vm: AppViewModel, onNavigate: (NavSection) -> Unit) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    val selectedGenre by vm.selectedGenre.collectAsStateWithLifecycle()
    val genreChannels by vm.channels.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val onAir by vm.onAir.collectAsStateWithLifecycle()
    val minute by vm.minute.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.fillMaxHeight().background(HindsColors.Panel)) {
                TopNav(NavSection.Live, onNavigate, Modifier.padding(start = 28.dp, top = 24.dp, bottom = 4.dp))
                Row(Modifier.weight(1f)) {
                GenreColumn(
                    genres = genres,
                    selectedGenre = selectedGenre,
                    sync = sync,
                    onFocused = vm::onGenreFocused,
                    onSelected = vm::selectGenre,
                    onEditList = vm::editList,
                    onRefresh = vm::refreshChannels,
                )
                val list = genreChannels?.takeIf { it.genreId == selectedGenre }
                ChannelColumn(
                    genreId = selectedGenre,
                    title = genres.firstOrNull { it.id == selectedGenre }?.title.orEmpty(),
                    channels = list?.channels,
                    playingId = playback.channel?.id,
                    onAir = onAir,
                    nowSec = minute,
                    onPlay = { vm.play(it, selectedGenre) },
                    onToggleFavorite = vm::toggleFavorite,
                )
                }
            }
            // Soft edge into the video instead of a hard panel border.
            Box(
                Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(HindsColors.Panel, Color.Transparent))),
            )
        }
        Clock(minute, Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))
    }
}

@Composable
private fun GenreColumn(
    genres: List<GenreItem>,
    selectedGenre: String,
    sync: SyncUi,
    onFocused: (String) -> Unit,
    onSelected: (String) -> Unit,
    onEditList: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.width(260.dp).fillMaxHeight().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.groups).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = HindsColors.Faint,
            modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp),
        )
        when (sync) {
            SyncUi.Running -> SmallNote(stringResource(R.string.updating_channels), HindsColors.Muted)
            is SyncUi.Failed -> SmallNote(sync.message, HindsColors.Error)
            SyncUi.Idle -> Unit
        }
        LazyColumn(
            modifier = Modifier.focusRestorer().focusGroup(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            itemsIndexed(genres, key = { _, g -> g.id }) { _, genre ->
                ListItem(
                    selected = genre.id == selectedGenre,
                    onClick = { onSelected(genre.id) },
                    headlineContent = { Text(genre.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = HindsStyle.listItemColors(),
                    shape = HindsStyle.listItemShape(),
                    scale = HindsStyle.noScale(),
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) onFocused(genre.id) }
                        .onKeyEvent {
                            val listId = genre.listId
                            if (listId != null && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                                onEditList(listId)
                                true
                            } else {
                                false
                            }
                        },
                )
            }
            item(key = "__actions") { Spacer(Modifier.height(16.dp)) }
            actionItem("__refresh", R.string.action_refresh, onRefresh)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.actionItem(key: String, label: Int, onClick: () -> Unit) {
    item(key = key) {
        ListItem(
            selected = false,
            onClick = onClick,
            headlineContent = {
                Text(stringResource(label), color = LocalContentColor.current.copy(alpha = 0.7f))
            },
            colors = HindsStyle.listItemColors(),
            shape = HindsStyle.listItemShape(),
            scale = HindsStyle.noScale(),
        )
    }
}

@Composable
private fun ChannelColumn(
    genreId: String,
    title: String,
    channels: List<ChannelRow>?,
    playingId: String?,
    onAir: Map<String, ProgrammeEntity>,
    nowSec: Long,
    onPlay: (ChannelRow) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val targetIndex = channels?.indexOfFirst { it.id == playingId }?.coerceAtLeast(0) ?: 0

    Column(Modifier.width(560.dp).fillMaxHeight().padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channels != null) {
                Text(
                    stringResource(R.string.channel_count, channels.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = HindsColors.Faint,
                )
            }
        }
        when {
            channels == null -> Unit
            channels.isEmpty() -> SmallNote(stringResource(R.string.no_channels), HindsColors.Muted)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelItem(
                        channel = channel,
                        playing = channel.id == playingId,
                        programme = onAir[channel.id],
                        nowSec = nowSec,
                        onClick = { onPlay(channel) },
                        onMenu = { onToggleFavorite(channel.id) },
                        modifier = if (!initialFocusDone && index == targetIndex) Modifier.focusRequester(initialFocus) else Modifier,
                    )
                }
            }
        }
    }

    // When the overlay opens, jump to and focus the playing channel (or the first one).
    LaunchedEffect(channels != null && channels.isNotEmpty()) {
        if (initialFocusDone || channels.isNullOrEmpty()) return@LaunchedEffect
        listState.scrollToItem((targetIndex - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
        initialFocusDone = true
    }
    // A different group starts at the top.
    LaunchedEffect(genreId) {
        if (initialFocusDone) listState.scrollToItem(0)
    }
}

@Composable
private fun ChannelItem(
    channel: ChannelRow,
    playing: Boolean,
    programme: ProgrammeEntity?,
    nowSec: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = playing,
        onClick = onClick,
        colors = HindsStyle.listItemColors(selectedColor = HindsColors.Text),
        shape = HindsStyle.listItemShape(),
        scale = HindsStyle.noScale(),
        modifier = modifier.onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                onMenu()
                true
            } else {
                false
            }
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (channel.number > 0) channel.number.toString() else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.55f),
                    modifier = Modifier.width(40.dp),
                )
                ChannelLogo(channel.logo, channel.name, Modifier.size(width = 60.dp, height = 38.dp))
            }
        },
        headlineContent = {
            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        },
        supportingContent = programme?.let {
            {
                Column {
                    Text(
                        it.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LocalContentColor.current.copy(alpha = 0.65f),
                    )
                    ProgressLine(it.progress(nowSec), Modifier.width(140.dp).padding(top = 4.dp), height = 2.dp)
                }
            }
        },
        trailingContent = when {
            playing -> ({ Text("●", color = HindsColors.Accent) })
            channel.isFavorite -> ({ Text("★", color = HindsColors.Accent) })
            else -> null
        },
    )
}

@Composable
private fun SmallNote(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
    )
}
