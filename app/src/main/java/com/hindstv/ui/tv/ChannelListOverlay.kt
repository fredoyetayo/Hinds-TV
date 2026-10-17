package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.R
import com.hindstv.data.db.ChannelRow
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.GenreItem
import com.hindstv.ui.SyncUi
import com.hindstv.ui.theme.HindsColors

/**
 * Groups on the left, channels on the right, drawn over the playing video (TiviMate-style).
 * Opens focused on the playing channel. Lists are lazy and keyed, so 10k+ channels stay smooth.
 */
@Composable
fun ChannelListOverlay(vm: AppViewModel) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    val selectedGenre by vm.selectedGenre.collectAsStateWithLifecycle()
    val genreChannels by vm.channels.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxHeight().background(HindsColors.Panel)) {
        GenreColumn(
            genres = genres,
            selectedGenre = selectedGenre,
            sync = sync,
            onFocused = vm::onGenreFocused,
            onSelected = vm::selectGenre,
            onRefresh = vm::refreshChannels,
            onSettings = vm::openSettings,
        )
        val list = genreChannels?.takeIf { it.genreId == selectedGenre }
        ChannelColumn(
            genreId = selectedGenre,
            channels = list?.channels,
            playingId = playback.channel?.id,
            onPlay = { vm.play(it, selectedGenre) },
            onToggleFavorite = vm::toggleFavorite,
        )
    }
}

@Composable
private fun GenreColumn(
    genres: List<GenreItem>,
    selectedGenre: String,
    sync: SyncUi,
    onFocused: (String) -> Unit,
    onSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.width(280.dp).fillMaxHeight().padding(top = 24.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = HindsColors.Accent,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        when (sync) {
            SyncUi.Running -> SmallNote("Updating channel list…", HindsColors.Muted)
            is SyncUi.Failed -> SmallNote(sync.message, HindsColors.Error)
            SyncUi.Idle -> Unit
        }
        LazyColumn(
            modifier = Modifier.focusRestorer().focusGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            itemsIndexed(genres, key = { _, g -> g.id }) { _, genre ->
                ListItem(
                    selected = genre.id == selectedGenre,
                    onClick = { onSelected(genre.id) },
                    headlineContent = { Text(genre.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    scale = ListItemDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused(genre.id) },
                )
            }
            item(key = "__refresh") {
                ListItem(
                    selected = false,
                    onClick = onRefresh,
                    headlineContent = { Text(stringResource(R.string.action_refresh), color = HindsColors.Muted) },
                    scale = ListItemDefaults.scale(focusedScale = 1f),
                )
            }
            item(key = "__settings") {
                ListItem(
                    selected = false,
                    onClick = onSettings,
                    headlineContent = { Text(stringResource(R.string.action_settings), color = HindsColors.Muted) },
                    scale = ListItemDefaults.scale(focusedScale = 1f),
                )
            }
        }
    }
}

@Composable
private fun ChannelColumn(
    genreId: String,
    channels: List<ChannelRow>?,
    playingId: String?,
    onPlay: (ChannelRow) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val targetIndex = channels?.indexOfFirst { it.id == playingId }?.coerceAtLeast(0) ?: 0

    Box(Modifier.width(500.dp).fillMaxHeight().padding(top = 24.dp)) {
        when {
            channels == null -> Unit
            channels.isEmpty() -> SmallNote(stringResource(R.string.no_channels), HindsColors.Muted)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelItem(
                        channel = channel,
                        playing = channel.id == playingId,
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
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = playing,
        onClick = onClick,
        scale = ListItemDefaults.scale(focusedScale = 1f),
        modifier = modifier.onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                onMenu()
                true
            } else {
                false
            }
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (channel.number > 0) channel.number.toString() else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HindsColors.Muted,
                    modifier = Modifier.width(44.dp),
                )
                ChannelLogo(channel.logo, Modifier.size(width = 48.dp, height = 32.dp))
            }
        },
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (channel.isFavorite) ({ Text("★", color = HindsColors.Accent) }) else null,
    )
}

@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier) {
    if (url == null) {
        Box(modifier)
        return
    }
    // Coil sizes the decode to this box, so large portal logos never hit memory at full size.
    AsyncImage(model = url, contentDescription = null, modifier = modifier)
}

@Composable
private fun SmallNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}
