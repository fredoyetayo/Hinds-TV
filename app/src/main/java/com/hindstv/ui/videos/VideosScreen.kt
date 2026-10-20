package com.hindstv.ui.videos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.data.source.VideoItem
import com.hindstv.data.source.VideoSort
import com.hindstv.player.ResumePolicy
import com.hindstv.ui.LibraryUi
import com.hindstv.ui.VideoPageUi
import com.hindstv.ui.VideoViewModel
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.NavBarState
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PageJumpState
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.PosterCard
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.TvTextField
import com.hindstv.ui.components.formatDuration
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.pageJumpItem
import com.hindstv.ui.components.rememberPageJump
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsStyle
import kotlinx.coroutines.delay

/** VOD: the library, a video's details, or the player, with Back stepping out one level. */
@Composable
fun VideosScreen(vm: VideoViewModel, onNavigate: (NavSection) -> Unit, onExit: () -> Unit) {
    val page by vm.page.collectAsStateWithLifecycle()
    val navBar = rememberNavBarState()
    Box(Modifier.fillMaxSize().background(HindsColors.Background)) {
        when (val p = page) {
            VideoPageUi.Library -> LibraryPage(vm, onNavigate, navBar)
            is VideoPageUi.Details -> DetailsPage(vm, p.item)
            VideoPageUi.Player -> VideoPlayerPage(vm)
        }
    }
    when (page) {
        // However deep in the grid, Back goes up to the section bar first, then out to Live.
        VideoPageUi.Library -> NavBackHandler(navBar, onExit = onExit)
        is VideoPageUi.Details -> BackHandler { vm.closeDetails() }
        VideoPageUi.Player -> BackHandler { vm.closePlayer() }
    }
    LaunchedEffect(Unit) { vm.onOpen() }
}

/**
 * Categories on the left (like the live groups), the chosen one as a poster grid on the right,
 * with a title filter and Newest / A–Z order. Large libraries load page by page.
 */
@Composable
private fun LibraryPage(vm: VideoViewModel, onNavigate: (NavSection) -> Unit, navBar: NavBarState) {
    val library by vm.library.collectAsStateWithLifecycle()
    val query by vm.gridQuery.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val railFocus = remember { FocusRequester() }
    // Back from details: the grid puts focus back on the card instead (see returnFocusKey).
    val returning = remember { vm.returnFocusKey != null }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.videos_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TopNav(NavSection.Videos, onNavigate, state = navBar)
        }
        when (val lib = library) {
            LibraryUi.Loading -> Note(stringResource(R.string.loading), Modifier.padding(top = 24.dp))
            is LibraryUi.Failed -> Column(Modifier.padding(horizontal = 48.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(lib.message, style = MaterialTheme.typography.bodyLarge, color = HindsColors.Error)
                PillButton(stringResource(R.string.retry), onClick = vm::onOpen, primary = true)
            }
            is LibraryUi.Ready -> Row(Modifier.fillMaxSize().padding(top = 16.dp)) {
                CategoryRail(
                    categories = buildList {
                        if (continueWatching.isNotEmpty()) add(VideoViewModel.CONTINUE to stringResource(R.string.continue_watching))
                        lib.categories.forEach { add(it.id to it.title) }
                    },
                    selected = query?.categoryId,
                    onSelect = vm::selectCategory,
                    focusRequester = railFocus,
                )
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 40.dp)) {
                    if (query?.categoryId == VideoViewModel.CONTINUE) {
                        ContinueGrid(vm, continueWatching, railFocus)
                    } else {
                        CategoryGrid(vm, title = lib.categories.firstOrNull { it.id == query?.categoryId }?.title.orEmpty(), railFocus)
                    }
                }
            }
        }
    }

    // Focus starts on the section bar, then moves to the chosen category once the library is in.
    LaunchedEffect(Unit) { if (!returning) navBar.focus.requestFocusSoon() }
    LaunchedEffect(library is LibraryUi.Ready) {
        if (library is LibraryUi.Ready && !returning) railFocus.requestFocusSoon()
    }
}

@Composable
private fun CategoryRail(
    categories: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    // Moving through the rail previews a category after a short pause (fast scrolling stays smooth).
    var focused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focused) {
        val id = focused ?: return@LaunchedEffect
        delay(RAIL_DEBOUNCE_MS)
        onSelect(id)
    }
    LazyColumn(
        Modifier.width(260.dp).fillMaxHeight().focusRestorer().focusGroup(),
        contentPadding = PaddingValues(start = 34.dp, end = 6.dp, bottom = 24.dp),
    ) {
        items(categories, key = { it.first }) { (id, title) ->
            ListItem(
                selected = id == selected,
                onClick = { onSelect(id) },
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = HindsStyle.listItemColors(),
                shape = HindsStyle.listItemShape(),
                scale = HindsStyle.noScale(),
                modifier = Modifier
                    .then(if (id == (selected ?: categories.firstOrNull()?.first)) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) focused = id },
            )
        }
    }
}

@Composable
private fun CategoryGrid(vm: VideoViewModel, title: String, railFocus: FocusRequester) {
    val grid by vm.grid.collectAsStateWithLifecycle()
    val query by vm.gridQuery.collectAsStateWithLifecycle()
    val q = query ?: return
    // The filter box belongs to the category: a new category starts unfiltered.
    var filter by remember(q.categoryId) { mutableStateOf(q.search) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TvTextField(
            label = stringResource(R.string.search_vod),
            value = filter,
            onValueChange = {
                filter = it
                vm.setSearch(it)
            },
            placeholder = stringResource(R.string.search_vod_hint),
            modifier = Modifier.width(250.dp),
        )
        PillButton(
            stringResource(if (q.sort == VideoSort.Newest) R.string.sort_newest else R.string.sort_az),
            onClick = { vm.setSort(if (q.sort == VideoSort.Newest) VideoSort.AtoZ else VideoSort.Newest) },
        )
    }
    val g = grid
    val items = g?.items.orEmpty()
    val gridState = rememberLazyGridState()
    val jump = rememberPageJump(items.size, page = GRID_PAGE) { gridState.scrollToItem(it) }
    ReturnFocus(vm, items.isNotEmpty(), jump, railFocus) { key -> items.indexOfFirst { it.id == key } }
    when {
        g == null || (items.isEmpty() && g.loading) -> Note(stringResource(R.string.loading), Modifier.padding(top = 16.dp))
        g.error != null && items.isEmpty() -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Note(g.error)
            PillButton(stringResource(R.string.retry), onClick = vm::retryGrid, modifier = Modifier.padding(start = 48.dp))
        }
        items.isEmpty() -> Note(
            if (q.search.isNotEmpty()) stringResource(R.string.no_results, q.search) else stringResource(R.string.row_empty),
            Modifier.padding(top = 16.dp),
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(top = 14.dp).then(jump.modifier).focusRestorer().focusGroup(),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, v -> v.id }) { index, video ->
                PosterCard(
                    title = video.title,
                    meta = videoMeta(video),
                    image = video.poster,
                    onClick = { vm.openDetails(video) },
                    onFocused = { if (index >= items.size - LOAD_AHEAD) vm.loadMore() },
                    badge = if (video.hasEpisodes) stringResource(R.string.episodes) else null,
                    width = CARD_WIDTH,
                    modifier = Modifier.pageJumpItem(jump, index),
                )
            }
        }
    }
}

@Composable
private fun ContinueGrid(vm: VideoViewModel, items: List<WatchProgressEntity>, railFocus: FocusRequester) {
    val gridState = rememberLazyGridState()
    val jump = rememberPageJump(items.size, page = GRID_PAGE) { gridState.scrollToItem(it) }
    ReturnFocus(vm, items.isNotEmpty(), jump, railFocus) { key -> items.indexOfFirst { it.key == key } }
    Text(stringResource(R.string.continue_watching), style = MaterialTheme.typography.titleLarge)
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(top = 14.dp).then(jump.modifier).focusRestorer().focusGroup(),
        contentPadding = PaddingValues(bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(items, key = { _, p -> p.key }) { index, p ->
            val left = (p.durationMs - p.positionMs).coerceAtLeast(0)
            PosterCard(
                title = p.title,
                meta = listOfNotNull(
                    p.episodeTitle,
                    if (p.durationMs > 0) stringResource(R.string.time_left, formatDuration(left)) else null,
                ).joinToString(" · "),
                image = p.poster,
                onClick = { vm.resume(p) },
                progress = ResumePolicy.fraction(p.positionMs, p.durationMs),
                width = CARD_WIDTH,
                modifier = Modifier.pageJumpItem(jump, index),
            )
        }
    }
}

/** Back from details: focus the card it was opened from, or the rail if that card has gone. */
@Composable
private fun ReturnFocus(vm: VideoViewModel, ready: Boolean, jump: PageJumpState, railFocus: FocusRequester, indexOf: (String) -> Int) {
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val key = vm.returnFocusKey ?: return@LaunchedEffect
        vm.returnFocusKey = null
        val index = indexOf(key)
        if (index >= 0) jump.focus(index) else railFocus.requestFocusSoon()
    }
}

fun videoMeta(video: VideoItem): String =
    listOfNotNull(
        video.year,
        video.durationMin.takeIf { it > 0 }?.let { formatDuration(it * 60_000L) },
        video.genres?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted, modifier = modifier.padding(horizontal = 48.dp))
}

private const val LOAD_AHEAD = 6
private const val GRID_COLUMNS = 3
private const val GRID_PAGE = GRID_COLUMNS * 3
private val CARD_WIDTH = 180.dp
private const val RAIL_DEBOUNCE_MS = 250L
