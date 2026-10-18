package com.hindstv.ui.videos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.data.source.VideoCategory
import com.hindstv.player.ResumePolicy
import com.hindstv.ui.LibraryUi
import com.hindstv.ui.VideoPageUi
import com.hindstv.ui.VideoRow
import com.hindstv.ui.VideoViewModel
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.PosterCard
import com.hindstv.ui.components.RowTitle
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.formatDuration
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors

/** Videos: the library, a video's details, or the player, with Back stepping out one level. */
@Composable
fun VideosScreen(vm: VideoViewModel, onNavigate: (NavSection) -> Unit, onExit: () -> Unit) {
    val page by vm.page.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(HindsColors.Background)) {
        when (val p = page) {
            VideoPageUi.Library -> LibraryPage(vm, onNavigate)
            is VideoPageUi.Details -> DetailsPage(vm, p.item)
            VideoPageUi.Player -> VideoPlayerPage(vm)
        }
    }
    BackHandler {
        when (page) {
            VideoPageUi.Library -> onExit()
            is VideoPageUi.Details -> vm.closeDetails()
            VideoPageUi.Player -> vm.closePlayer()
        }
    }
    LaunchedEffect(Unit) { vm.onOpen() }
}

@Composable
private fun LibraryPage(vm: VideoViewModel, onNavigate: (NavSection) -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    val navFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item(key = "__header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.videos_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TopNav(NavSection.Videos, onNavigate, currentFocus = navFocus)
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "__continue") {
                ContinueRow(continueWatching, onResume = vm::resume, firstFocus = firstFocus)
            }
        }
        when (val lib = library) {
            LibraryUi.Loading -> item(key = "__loading") { Note(stringResource(R.string.loading)) }
            is LibraryUi.Failed -> item(key = "__failed") {
                Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(lib.message, style = MaterialTheme.typography.bodyLarge, color = HindsColors.Error)
                    PillButton(stringResource(R.string.retry), onClick = vm::onOpen, primary = true)
                }
            }
            is LibraryUi.Ready -> {
                if (lib.categories.isEmpty()) item(key = "__empty") { Note(stringResource(R.string.no_videos)) }
                itemsIndexed(lib.categories, key = { _, c -> "cat:" + c.id }) { index, category ->
                    CategoryRow(
                        category = category,
                        row = rows[category.id],
                        onLoadMore = { vm.loadMore(category) },
                        onOpen = vm::openDetails,
                        firstFocus = if (index == 0 && continueWatching.isEmpty()) firstFocus else null,
                    )
                }
            }
        }
    }

    // Something always has focus: the Videos pill straight away, then the first video once it exists.
    val firstCategory = (library as? LibraryUi.Ready)?.categories?.firstOrNull()?.id
    val hasFirst = continueWatching.isNotEmpty() || rows[firstCategory]?.items?.isNotEmpty() == true
    LaunchedEffect(Unit) { navFocus.requestFocusSoon() }
    LaunchedEffect(hasFirst) {
        if (hasFirst) firstFocus.requestFocusSoon()
    }
}

@Composable
private fun ContinueRow(items: List<WatchProgressEntity>, onResume: (WatchProgressEntity) -> Unit, firstFocus: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowTitle(stringResource(R.string.continue_watching), Modifier.padding(horizontal = 48.dp))
        LazyRow(
            modifier = Modifier.focusRestorer().focusGroup(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(items, key = { _, p -> p.key }) { index, p ->
                val left = (p.durationMs - p.positionMs).coerceAtLeast(0)
                val meta = listOfNotNull(
                    p.episodeTitle,
                    if (p.durationMs > 0) stringResource(R.string.time_left, formatDuration(left)) else null,
                ).joinToString(" · ")
                PosterCard(
                    title = p.title,
                    meta = meta,
                    image = p.poster,
                    onClick = { onResume(p) },
                    width = 240.dp,
                    progress = ResumePolicy.fraction(p.positionMs, p.durationMs),
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: VideoCategory,
    row: VideoRow?,
    onLoadMore: () -> Unit,
    onOpen: (com.hindstv.data.source.VideoItem) -> Unit,
    firstFocus: FocusRequester?,
) {
    // Rows load their first page when they scroll into view; later pages as focus nears the end.
    LaunchedEffect(category.id) { if (row == null) onLoadMore() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowTitle(category.title, Modifier.padding(horizontal = 48.dp))
        val items = row?.items.orEmpty()
        when {
            row?.error != null && items.isEmpty() -> Note(row.error)
            items.isEmpty() && row?.loading != false -> Note(stringResource(R.string.loading))
            items.isEmpty() -> Note(stringResource(R.string.row_empty))
            else -> LazyRow(
                modifier = Modifier.focusRestorer().focusGroup(),
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(items, key = { _, v -> v.id }) { index, video ->
                    PosterCard(
                        title = video.title,
                        meta = videoMeta(video),
                        image = video.poster,
                        onClick = { onOpen(video) },
                        onFocused = { if (index >= items.size - LOAD_AHEAD) onLoadMore() },
                        badge = if (video.hasEpisodes) stringResource(R.string.episodes) else null,
                        modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

fun videoMeta(video: com.hindstv.data.source.VideoItem): String =
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
