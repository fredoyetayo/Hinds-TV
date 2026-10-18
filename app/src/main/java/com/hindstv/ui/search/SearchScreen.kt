package com.hindstv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.RadioEntity
import com.hindstv.data.source.VideoItem
import com.hindstv.ui.SearchViewModel
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.PosterCard
import com.hindstv.ui.components.RowTitle
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.TvTextField
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.timeRange
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.videos.videoMeta

/**
 * One box searches everything. The keyboard opens straight away; results fill in below as the
 * user types, grouped by kind, and ▼ from the keyboard moves into them. Recent searches are one
 * press away when the box is empty.
 */
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onNavigate: (NavSection) -> Unit,
    onExit: () -> Unit,
    onWatch: (ChannelRow) -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onPlayRadio: (RadioEntity) -> Unit,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val nowSec = System.currentTimeMillis() / 1000

    val navBar = rememberNavBarState()
    // The bar and the box stay put; only the results scroll, so both are always one press away.
    Column(Modifier.fillMaxSize().background(HindsColors.Background).padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TopNav(NavSection.Search, onNavigate, state = navBar)
        }
        TvTextField(
            label = stringResource(R.string.search_field),
            value = query,
            onValueChange = vm::onQueryChange,
            placeholder = stringResource(R.string.search_hint),
            startEditing = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
        )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (query.trim().length < 2) {
            if (recent.isNotEmpty()) {
                item(key = "__recent") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RowTitle(stringResource(R.string.recent_searches), Modifier.padding(horizontal = 48.dp))
                        LazyRow(
                            Modifier.focusRestorer().focusGroup(),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(recent, key = { it }) { text -> PillButton(text, onClick = { vm.onQueryChange(text) }) }
                            item(key = "__clear") { PillButton(stringResource(R.string.clear), onClick = vm::clearRecent) }
                        }
                    }
                }
            }
            return@LazyColumn
        }
        if (results.channels.isNotEmpty()) {
            item(key = "__channels") {
                ResultRow(stringResource(R.string.results_channels), results.channels, { it.id }) { c ->
                    PosterCard(
                        title = c.name,
                        meta = if (c.number > 0) c.number.toString() else null,
                        image = c.logo,
                        onClick = { vm.remember(); onWatch(c) },
                        width = 180.dp,
                    )
                }
            }
        }
        if (results.programmes.isNotEmpty()) {
            item(key = "__programmes") {
                ResultRow(stringResource(R.string.results_programmes), results.programmes, { "${it.programme.channelId}@${it.programme.start}" }) { hit ->
                    val p = hit.programme
                    val time = if (p.start <= nowSec) stringResource(R.string.on_now) else formatTime(p.start)
                    PosterCard(
                        title = p.title,
                        meta = "${hit.channel.name} · $time",
                        image = hit.channel.logo,
                        onClick = { vm.remember(); onWatch(hit.channel) },
                        width = 220.dp,
                        badge = if (p.start <= nowSec) stringResource(R.string.on_now) else p.timeRange(),
                    )
                }
            }
        }
        if (results.videos.isNotEmpty()) {
            item(key = "__videos") {
                ResultRow(stringResource(R.string.results_videos), results.videos, { it.id }) { v ->
                    PosterCard(
                        title = v.title,
                        meta = videoMeta(v),
                        image = v.poster,
                        onClick = { vm.remember(); onOpenVideo(v) },
                        badge = if (v.hasEpisodes) stringResource(R.string.episodes) else null,
                    )
                }
            }
        }
        if (results.radio.isNotEmpty()) {
            item(key = "__radio") {
                ResultRow(stringResource(R.string.results_radio), results.radio, { it.id }) { r ->
                    PosterCard(title = r.name, meta = null, image = r.logo, onClick = { vm.remember(); onPlayRadio(r) }, width = 160.dp)
                }
            }
        }
        item(key = "__status") {
            val text = when {
                results.searching && results.isEmpty -> stringResource(R.string.searching)
                !results.searching && results.isEmpty && results.query.isNotEmpty() -> stringResource(R.string.no_results, results.query)
                else -> results.videoError
            }
            text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted, modifier = Modifier.padding(horizontal = 48.dp))
            }
        }
    }
    }
    NavBackHandler(navBar, onExit = onExit)
}

@Composable
private fun <T> ResultRow(title: String, items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowTitle(title, Modifier.padding(horizontal = 48.dp))
        LazyRow(
            Modifier.focusRestorer().focusGroup(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items, key = key) { card(it) }
        }
    }
}
