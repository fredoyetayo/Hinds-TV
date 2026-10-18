package com.hindstv.ui.videos

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.MediaRepository
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.data.source.VideoEpisode
import com.hindstv.data.source.VideoItem
import com.hindstv.player.ResumePolicy
import com.hindstv.ui.EpisodesUi
import com.hindstv.ui.VideoViewModel
import com.hindstv.ui.components.Artwork
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.PosterCard
import com.hindstv.ui.components.RowTitle
import com.hindstv.ui.components.formatDuration
import com.hindstv.ui.components.formatTimecode
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/** One video or series: what it is, Resume / Play from start, and its episodes. */
@Composable
fun DetailsPage(vm: VideoViewModel, item: VideoItem) {
    val episodesUi by vm.episodes.collectAsStateWithLifecycle()
    val progress by vm.detailsProgress.collectAsStateWithLifecycle()
    val byKey = remember(progress) { progress.associateBy { it.key } }
    val episodes = (episodesUi as? EpisodesUi.Ready)?.episodes
    val primaryFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item.genres?.takeIf { it.isNotBlank() }?.let {
                    Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent, maxLines = 1)
                }
                Text(item.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                videoMeta(item).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted)
                }
                item.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Text.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Actions(vm, item, episodesUi, episodes, progress, byKey, Modifier.padding(top = 8.dp), primaryFocus)
            }
            Artwork(item.poster, item.title, Modifier.size(width = 320.dp, height = 180.dp).clip(HindsShapes.Large))
        }

        if (item.hasEpisodes) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RowTitle(stringResource(R.string.episodes), Modifier.padding(horizontal = 48.dp))
                when (val ui = episodesUi) {
                    EpisodesUi.Loading, EpisodesUi.None -> Note(stringResource(R.string.episodes_loading))
                    is EpisodesUi.Failed -> Note(ui.message)
                    is EpisodesUi.Ready -> LazyRow(
                        modifier = Modifier.focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(ui.episodes, key = { _, e -> e.key }) { _, episode ->
                            val saved = byKey[MediaRepository.progressKey(item, episode)]
                            PosterCard(
                                title = episode.title,
                                meta = episodeMeta(episode, saved),
                                image = item.poster,
                                onClick = { vm.play(item, episode, startFor(saved)) },
                                progress = saved?.let { ResumePolicy.fraction(it.positionMs, it.durationMs) },
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(item.id, episodesUi is EpisodesUi.Ready || episodesUi is EpisodesUi.Failed || !item.hasEpisodes) {
        primaryFocus.requestFocusSoon()
    }
}

/**
 * The main buttons. Films: Resume from 12:31 + Play from start, or Play. Series: carry on with
 * the last episode watched (or the one after it), else start at the first.
 */
@Composable
private fun Actions(
    vm: VideoViewModel,
    item: VideoItem,
    episodesUi: EpisodesUi,
    episodes: List<VideoEpisode>?,
    progress: List<WatchProgressEntity>,
    byKey: Map<String, WatchProgressEntity>,
    modifier: Modifier,
    primaryFocus: FocusRequester,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!item.hasEpisodes) {
            val saved = byKey[MediaRepository.progressKey(item, null)]
            val start = startFor(saved)
            if (start > 0) {
                PillButton(
                    stringResource(R.string.resume_at, formatTimecode(start)),
                    onClick = { vm.play(item, null, start) },
                    primary = true,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
                PillButton(stringResource(R.string.play_from_start), onClick = { vm.play(item, null, 0) })
            } else {
                PillButton(
                    stringResource(R.string.play),
                    onClick = { vm.play(item, null, 0) },
                    primary = true,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            return@Row
        }
        if (episodes == null) {
            if (episodesUi is EpisodesUi.Failed) {
                PillButton(stringResource(R.string.retry), onClick = { vm.openDetails(item) }, primary = true, modifier = Modifier.focusRequester(primaryFocus))
            }
            return@Row
        }
        if (episodes.isEmpty()) return@Row
        // progress is newest first: the episode the viewer was last in.
        val last = progress.firstOrNull()
        val lastEpisode = last?.episodeKey?.let { key -> episodes.firstOrNull { it.key == key } }
        val resumeStart = startFor(last)
        when {
            lastEpisode != null && resumeStart > 0 -> {
                val left = (last.durationMs - last.positionMs).coerceAtLeast(0)
                PillButton(
                    stringResource(R.string.resume_episode, lastEpisode.title, formatDuration(left)),
                    onClick = { vm.play(item, lastEpisode, resumeStart) },
                    primary = true,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
                PillButton(stringResource(R.string.play_from_start), onClick = { vm.play(item, lastEpisode, 0) })
            }
            else -> {
                val next = lastEpisode?.let { vm.nextEpisodeAfter(it) } ?: lastEpisode ?: episodes.first()
                PillButton(
                    "${stringResource(R.string.play)} · ${next.title}",
                    onClick = { vm.play(item, next, startFor(byKey[MediaRepository.progressKey(item, next)])) },
                    primary = true,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
        }
    }
}

private fun startFor(saved: WatchProgressEntity?): Long =
    saved?.let { ResumePolicy.startPosition(it.positionMs, it.durationMs, it.finished) } ?: 0L

@Composable
private fun episodeMeta(episode: VideoEpisode, saved: WatchProgressEntity?): String {
    val state = when {
        saved == null -> null
        saved.finished -> stringResource(R.string.watched)
        saved.durationMs > 0 -> stringResource(R.string.time_left, formatDuration(saved.durationMs - saved.positionMs))
        else -> null
    }
    return listOfNotNull(episode.season, state).joinToString(" · ")
}
