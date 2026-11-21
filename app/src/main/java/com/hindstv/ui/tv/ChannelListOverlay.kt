package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.GenreItem
import com.hindstv.ui.GroupKind
import com.hindstv.ui.SyncUi
import com.hindstv.ui.components.ChannelWordmark
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.focusColors
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.pageJumpItem
import com.hindstv.ui.components.progress
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.rememberPageJump
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/**
 * Groups on the left, channels on the right, on a panel over the playing video. Opens focused on
 * the playing channel. Lists are lazy and keyed, so 10k+ channels stay smooth.
 */
@Composable
fun ChannelListOverlay(vm: AppViewModel, onNavigate: (NavSection) -> Unit) {
    val navBar = rememberNavBarState()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val selectedGenre by vm.selectedGenre.collectAsStateWithLifecycle()
    val genreChannels by vm.channels.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val onAir by vm.onAir.collectAsStateWithLifecycle()
    val minute by vm.minute.collectAsStateWithLifecycle()
    val groupMenu by vm.groupMenu.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.width(PANEL_WIDTH).fillMaxHeight().background(HindsColors.Panel)) {
                TopNav(NavSection.Live, onNavigate, Modifier.padding(start = 28.dp, top = 20.dp), state = navBar)
                Row(Modifier.weight(1f).padding(top = 20.dp, bottom = 15.dp)) {
                    GenreColumn(
                        genres = genres,
                        selectedGenre = selectedGenre,
                        sync = sync,
                        onFocused = vm::onGenreFocused,
                        onSelected = vm::selectGenre,
                        onEditList = vm::editList,
                        onGroupMenu = vm::openGroupMenu,
                        menuOpen = groupMenu != null,
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
        Text(
            formatTime(minute),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 48.dp)
                .background(HindsColors.Panel, HindsShapes.Pill).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    // Back: first up to the section bar (however far down the list), then back to the picture.
    NavBackHandler(navBar, onExit = if (playback.channel != null) vm::hideChannelList else null)
}

@Composable
private fun GenreColumn(
    genres: List<GenreItem>,
    selectedGenre: String,
    sync: SyncUi,
    onFocused: (String) -> Unit,
    onSelected: (String) -> Unit,
    onEditList: (Long) -> Unit,
    onGroupMenu: (String) -> Unit,
    menuOpen: Boolean,
    onRefresh: () -> Unit,
) {
    val listState = rememberLazyListState()
    // A group that was just pinned moves, a hidden one goes: keep focus on it, or where it was.
    var menuGroup by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var refocusIndex by remember { mutableStateOf<Int?>(null) }
    val refocus = remember { FocusRequester() }
    // Closed without a change (Cancel/Back): forget it, so a later list update doesn't move focus.
    LaunchedEffect(menuOpen) {
        if (!menuOpen) {
            delay(500)
            menuGroup = null
        }
    }
    LaunchedEffect(genres) {
        val (id, oldIndex) = menuGroup ?: return@LaunchedEffect
        val index = genres.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: oldIndex.coerceAtMost(genres.lastIndex)
        if (index < 0) return@LaunchedEffect
        menuGroup = null
        listState.scrollToItem((lazyIndexOf(genres, genres[index].id) - 3).coerceAtLeast(0))
        refocusIndex = index
        withFrameNanos { }
        runCatching { refocus.requestFocus() }
        refocusIndex = null
    }
    Column(Modifier.padding(start = 28.dp).width(260.dp).fillMaxHeight()) {
        Text(
            stringResource(R.string.groups).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = HindsColors.Faint,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        when (sync) {
            SyncUi.Running -> SmallNote(stringResource(R.string.updating_channels), HindsColors.Muted)
            is SyncUi.Failed -> SmallNote(sync.message, HindsColors.Error)
            SyncUi.Idle -> Unit
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            genres.forEachIndexed { index, genre ->
                // A hairline between the user's own groups and the provider's.
                if (genre.kind == GroupKind.All && index > 0) {
                    item(key = "__divider") {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth().height(1.dp).background(HindsColors.Hairline))
                    }
                }
                item(key = genre.id) {
                    GroupRow(
                        genre,
                        selected = genre.id == selectedGenre,
                        onClick = { onSelected(genre.id) },
                        modifier = Modifier
                            .then(if (refocusIndex == index) Modifier.focusRequester(refocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocused(genre.id) }
                            .onKeyEvent {
                                if (it.type != KeyEventType.KeyDown || it.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_MENU) {
                                    return@onKeyEvent false
                                }
                                val listId = genre.listId
                                when {
                                    listId != null -> onEditList(listId)
                                    genre.kind == GroupKind.Provider -> {
                                        menuGroup = genre.id to index
                                        onGroupMenu(genre.id)
                                    }
                                    else -> return@onKeyEvent false
                                }
                                true
                            },
                    )
                }
            }
        }
        RefreshRow(onRefresh)
    }
}

/** The group's position in the lazy list, counting the divider item above All channels. */
private fun lazyIndexOf(genres: List<GenreItem>, id: String): Int {
    val index = genres.indexOfFirst { it.id == id }
    if (index < 0) return -1
    val all = genres.indexOfFirst { it.kind == GroupKind.All }
    return if (all in 1..index) index + 1 else index
}

@Composable
private fun GroupRow(genre: GenreItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(32.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
        colors = focusColors(
            focused,
            container = if (selected) HindsColors.SurfaceRaised else Color.Transparent,
            content = if (selected) HindsColors.Accent else HindsColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (genre.kind) {
                GroupKind.Recent -> Text("↺", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                GroupKind.Football -> Text("⚽", fontSize = 12.sp)
                GroupKind.List -> Text("★", fontSize = 13.sp, color = if (focused) HindsColors.OnFocus else HindsColors.Accent)
                else -> if (genre.pinned) Text("★", fontSize = 13.sp, color = if (focused) HindsColors.OnFocus else HindsColors.Accent)
            }
            Text(
                genre.title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Provider groups have no count; the focused one says what ☰ does instead.
            if (focused && genre.kind == GroupKind.Provider) {
                Text(
                    stringResource(R.string.hint_group_menu),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HindsColors.OnFocus.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            genre.count?.let {
                Text(
                    "%,d".format(it),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        focused -> HindsColors.OnFocus.copy(alpha = 0.7f)
                        selected -> HindsColors.Accent
                        else -> HindsColors.Muted
                    },
                )
            }
        }
    }
}

@Composable
private fun RefreshRow(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(32.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
        colors = focusColors(focused, container = Color.Transparent, content = HindsColors.Muted),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            "↻  " + stringResource(R.string.action_refresh),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
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
    val jump = rememberPageJump(channels?.size ?: 0, page = CHANNEL_PAGE) { listState.scrollToItem((it - 3).coerceAtLeast(0)) }

    Column(Modifier.padding(start = 12.dp).width(500.dp).fillMaxHeight()) {
        Row(
            Modifier.padding(horizontal = 12.dp).padding(bottom = 9.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channels != null) {
                Text(
                    stringResource(R.string.channel_count, channels.size),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = HindsColors.Muted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        when {
            channels == null -> Spacer(Modifier.weight(1f))
            channels.isEmpty() -> {
                SmallNote(stringResource(R.string.no_channels), HindsColors.Muted)
                Spacer(Modifier.weight(1f))
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().then(jump.modifier).focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelItem(
                        channel = channel,
                        playing = channel.id == playingId,
                        programme = onAir[channel.id],
                        nowSec = nowSec,
                        onClick = { onPlay(channel) },
                        onMenu = { onToggleFavorite(channel.id) },
                        modifier = Modifier.pageJumpItem(jump, index)
                            .then(if (!initialFocusDone && index == targetIndex) Modifier.focusRequester(initialFocus) else Modifier),
                    )
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 9.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(R.string.hint_list_watch, R.string.hint_list_add, R.string.hint_list_jump, R.string.hint_list_back).forEach {
                Text(stringResource(it), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HindsColors.Muted, maxLines = 1)
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

/** Logo · number · name (WATCHING when playing) · what's on with progress · ★. */
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
    var focused by remember { mutableStateOf(false) }
    val highlight = if (focused) HindsColors.OnFocus else HindsColors.Accent
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp).onFocusChanged { focused = it.isFocused }.onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                onMenu()
                true
            } else {
                false
            }
        },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
        colors = focusColors(focused, container = Color.Transparent, content = HindsColors.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 7.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ChannelWordmark(
                channel,
                Modifier.size(60.dp, 34.dp).background(HindsColors.Plate, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                initialsSize = 12.sp,
            )
            Text(
                if (channel.number > 0) channel.number.toString() else "",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    focused -> HindsColors.OnFocus
                    playing -> HindsColors.Accent
                    else -> HindsColors.Muted
                },
                modifier = Modifier.width(38.dp),
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    channel.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (playing) Text(stringResource(R.string.watching), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = highlight)
            }
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                programme?.let {
                    Text(
                        it.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) HindsColors.OnFocus else HindsColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProgressLine(
                        it.progress(nowSec),
                        Modifier.width(130.dp),
                        height = 2.5.dp,
                        color = highlight,
                        track = if (focused) FOCUSED_TRACK else HindsColors.Track,
                    )
                }
            }
            Text(if (channel.isFavorite) "★" else "", fontSize = 15.sp, color = highlight, modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun SmallNote(text: String, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** ⏪/⏩ in the channel list move this many channels. */
private const val CHANNEL_PAGE = 10
private val PANEL_WIDTH = 820.dp

/** A progress track that reads on the focus fill in either theme. */
private val FOCUSED_TRACK = Color(0x59808080)
