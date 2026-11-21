package com.hindstv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.R
import com.hindstv.data.ChannelRepository
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.player.ResumePolicy
import com.hindstv.ui.AppViewModel
import com.hindstv.hub.HubMatch
import com.hindstv.ui.HomeList
import com.hindstv.ui.HomePick
import com.hindstv.ui.VideoViewModel
import com.hindstv.ui.components.Artwork
import com.hindstv.ui.components.ChannelTile
import com.hindstv.ui.components.ChannelWordmark
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.focusColors
import com.hindstv.ui.components.focusRing
import com.hindstv.ui.components.formatDay
import com.hindstv.ui.components.formatDuration
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.formatTimecode
import com.hindstv.ui.components.initialsOf
import com.hindstv.ui.components.progress
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.theme.LocalHindsPalette
import com.hindstv.ui.theme.wallpapersFor

/**
 * Home (opt-in start screen): a live match of your teams when there is one, else what you were
 * watching; then your matches (live scores, kick-offs), football on now, picks for you, your first
 * favourites list and the channels you watched last. Nothing plays here, so the provider connection
 * stays free until you pick.
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    videoVm: VideoViewModel,
    onNavigate: (NavSection) -> Unit,
    onExit: () -> Unit,
    onResume: (WatchProgressEntity, fromStart: Boolean) -> Unit,
    onWatch: (ChannelRow, group: String) -> Unit,
    onAllChannels: () -> Unit,
) {
    val continueWatching by videoVm.continueWatching.collectAsStateWithLifecycle()
    val recent by vm.recentChannels.collectAsStateWithLifecycle()
    val homeList by vm.homeList.collectAsStateWithLifecycle()
    val onAir by vm.onAir.collectAsStateWithLifecycle()
    val now by vm.minute.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val footballNow by vm.footballNow.collectAsStateWithLifecycle()
    val picks by vm.picks.collectAsStateWithLifecycle()
    val liveMatch = matches.firstOrNull { it.state == "in" }
    val navBar = rememberNavBarState()
    val firstFocus = remember { FocusRequester() }
    val hero = continueWatching.firstOrNull()
    val wallpapers by vm.wallpapers.collectAsStateWithLifecycle()
    val light = LocalHindsPalette.current.isLight
    val set = wallpapersFor(light)
    val wallpaper = set[(if (light) wallpapers.second else wallpapers.first).coerceIn(set.indices)]

    Box(Modifier.fillMaxSize().background(HindsColors.Background)) {
        // Coil decodes it at screen size; the scrims keep text legible over any picture.
        AsyncImage(model = wallpaper.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val bg = HindsColors.Background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.86f), 0.38f to bg.copy(alpha = 0.5f), 0.62f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.64f to bg.copy(alpha = 0.9f), 1f to bg.copy(alpha = 0.98f))))
        Box(Modifier.fillMaxWidth().height(100.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.6f), Color.Transparent))))
    }
    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTime(now), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(formatDay(now), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted, modifier = Modifier.padding(bottom = 3.dp))
            }
            TopNav(NavSection.Home, onNavigate, Modifier.align(Alignment.Center), state = navBar)
        }
        // Focus moving down scrolls the rows into view; the clock and nav stay put.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(start = 48.dp, end = 48.dp, top = 30.dp).height(214.dp)) {
                val last = recent.firstOrNull()
                when {
                    liveMatch != null -> MatchHero(liveMatch, firstFocus) { vm.openMatch(liveMatch) }
                    hero != null -> ResumeHero(hero, firstFocus, onResume)
                    last != null -> ChannelHero(last, onAir[last.id], now, firstFocus) { onWatch(last, ChannelRepository.ALL) }
                    else -> WelcomeHero()
                }
            }
            val rowPad = Modifier.padding(top = 22.dp)
            if (matches.isNotEmpty()) {
                Shelf(stringResource(R.string.home_your_matches), rowPad) {
                    items(matches, key = { it.id }) { m -> MatchCard(m) { vm.openMatch(m) } }
                }
            }
            if (footballNow.isNotEmpty()) {
                Shelf(stringResource(R.string.home_football_now), rowPad) {
                    items(footballNow.take(SHELF_MAX), key = { it.id }) { channel ->
                        ChannelTile(channel, onAir[channel.id], now, onClick = { onWatch(channel, ChannelRepository.FOOTBALL) })
                    }
                }
            }
            if (picks.isNotEmpty()) {
                Shelf(stringResource(R.string.home_for_you), rowPad) {
                    items(picks, key = { it.channel.id + it.pick.start }) { p -> PickCard(p, now) { onWatch(p.channel, ChannelRepository.ALL) } }
                }
            }
            homeList?.takeIf { it.channels.isNotEmpty() }?.let { list ->
                MyChannels(list, onAir, now, onWatch, Modifier.padding(start = 48.dp, end = 48.dp, top = 22.dp))
            }
            RecentRow(
                recent.take(RECENT_CHIPS),
                onWatch = { onWatch(it, ChannelRepository.ALL) },
                onAllChannels = onAllChannels,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 24.dp),
                focusFirst = if (liveMatch == null && hero == null && recent.isEmpty()) firstFocus else null,
            )
        }
    }

    NavBackHandler(navBar, onExit = onExit)
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, color = HindsColors.Accent)
}

@Composable
private fun HeroTitle(text: String) {
    // Lesson titles run long; a smaller size keeps them to two lines.
    val big = text.length <= 18
    Text(
        text,
        fontSize = if (big) 52.sp else 34.sp,
        lineHeight = if (big) 52.sp else 38.sp,
        letterSpacing = if (big) (-1.8).sp else (-0.6).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ResumeHero(p: WatchProgressEntity, focus: FocusRequester, onResume: (WatchProgressEntity, Boolean) -> Unit) {
    val start = ResumePolicy.startPosition(p.positionMs, p.durationMs, p.finished)
    val episode = p.episodeNumber?.let { stringResource(R.string.episode_number, it) } ?: p.episodeTitle
    Row {
        Column(Modifier.width(460.dp)) {
            Eyebrow(stringResource(R.string.continue_watching))
            HeroTitle(p.title)
            val meta = listOfNotNull(episode, p.year?.takeIf { it.isNotBlank() }, formatDuration(p.durationMs).takeIf { p.durationMs > 0 })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted, modifier = Modifier.padding(top = 10.dp))
            }
            if (p.durationMs > 0) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ProgressLine(p.positionMs.toFloat() / p.durationMs, Modifier.width(240.dp))
                    Text(
                        stringResource(R.string.time_left, formatDuration(p.durationMs - p.positionMs)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HindsColors.Muted,
                    )
                }
            }
            p.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = HindsColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(400.dp).padding(top = 10.dp),
                )
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.resume_at, formatTimecode(start)), onClick = { onResume(p, false) }, modifier = Modifier.focusRequester(focus))
                PillButton(stringResource(R.string.play_from_start), onClick = { onResume(p, true) })
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(320.dp)) {
            Artwork(p.poster, p.title, Modifier.size(320.dp, 180.dp).clip(RoundedCornerShape(12.dp)))
            val next = if (p.isSeries) nextEpisodeNumber(p.episodeNumber, p.episodeNumbers) else null
            if (next != null) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.next_up), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted)
                    Text(
                        p.title + " · " + stringResource(R.string.episode_number, next),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Nothing to resume yet: the last channel, one press away. */
@Composable
private fun ChannelHero(channel: ChannelRow, airing: ProgrammeEntity?, now: Long, focus: FocusRequester, onWatch: () -> Unit) {
    Row {
        Column(Modifier.width(460.dp)) {
            Eyebrow(stringResource(R.string.home_last_watched))
            HeroTitle(channel.name)
            Text(
                listOfNotNull(channel.number.toString(), airing?.title).joinToString("  ·  "),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = HindsColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (airing != null) ProgressLine(airing.progress(now), Modifier.padding(top = 10.dp).width(240.dp))
            PillButton(stringResource(R.string.home_watch), onClick = onWatch, modifier = Modifier.padding(top = 16.dp).focusRequester(focus))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(320.dp, 180.dp).background(HindsColors.Plate, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            ChannelWordmark(channel, Modifier.size(180.dp, 90.dp), initialsSize = 40.sp)
        }
    }
}

@Composable
private fun WelcomeHero() {
    Column {
        HeroTitle(stringResource(R.string.home_welcome))
        Text(stringResource(R.string.home_welcome_hint), fontSize = 14.sp, color = HindsColors.Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun MyChannels(list: HomeList, onAir: Map<String, ProgrammeEntity>, now: Long, onWatch: (ChannelRow, String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(list.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.home_add_hint), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            list.channels.forEach { channel ->
                ChannelTile(channel, onAir[channel.id], now, onClick = { onWatch(channel, list.groupId) })
            }
        }
    }
}

@Composable
private fun RecentRow(
    recent: List<ChannelRow>,
    onWatch: (ChannelRow) -> Unit,
    onAllChannels: () -> Unit,
    modifier: Modifier,
    focusFirst: FocusRequester?,
) {
    Row(modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.group_recent), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 7.dp))
        }
        // The chips give way (clipped at the edge) so All channels always fits.
        Row(Modifier.weight(1f).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            recent.forEach { channel -> RecentChip(channel) { onWatch(channel) } }
        }
        var focused by remember { mutableStateOf(false) }
        Surface(
            onClick = onAllChannels,
            modifier = Modifier.height(32.dp).onFocusChanged { focused = it.isFocused }
                .then(if (focusFirst != null) Modifier.focusRequester(focusFirst) else Modifier),
            shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
            colors = focusColors(focused, container = Color.Transparent, content = HindsColors.Text),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, HindsColors.ChipLine), shape = HindsShapes.Pill),
                focusedBorder = Border.None,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Text(
                stringResource(R.string.genre_all),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
            )
        }
    }
}

@Composable
private fun RecentChip(channel: ChannelRow, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.height(32.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        colors = focusColors(focused, container = HindsColors.Surface, content = HindsColors.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = 14.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(23.dp).background(HindsColors.SurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                Text(remember(channel.name) { initialsOf(channel.name) }, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = HindsColors.Muted)
            }
            Text(
                channel.number.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (focused) HindsColors.OnFocus.copy(alpha = 0.7f) else HindsColors.Muted,
            )
            Text(channel.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** A titled, sideways-scrolling row. */
@Composable
private fun Shelf(title: String, modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 48.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One of your teams is playing right now: the score, one press from the match. */
@Composable
private fun MatchHero(match: HubMatch, focus: FocusRequester, onWatch: () -> Unit) {
    Column(Modifier.width(560.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LivePill()
            Eyebrow(listOf(match.competition, match.detail).filter { it.isNotBlank() }.joinToString("  ·  "))
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(match.home, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(match.score ?: "v", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = HindsColors.Accent)
            Text(match.away, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        PillButton(stringResource(R.string.home_watch_match), onClick = onWatch, modifier = Modifier.padding(top = 20.dp).focusRequester(focus))
    }
}

@Composable
private fun LivePill() {
    Box(Modifier.background(Color(0xFFE5484D), HindsShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(R.string.home_live), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
    }
}

/** A fixture: live score and minute, or the day and kick-off time. */
@Composable
private fun MatchCard(match: HubMatch, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val live = match.state == "in"
    Surface(
        onClick = onClick,
        modifier = Modifier.size(250.dp, 96.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = focusColors(focused, container = HindsColors.Surface, content = HindsColors.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        val sub = if (focused) HindsColors.OnFocus.copy(alpha = 0.7f) else HindsColors.Muted
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) LivePill()
                Text(
                    if (live) match.detail else formatDay(match.kickoff) + "  " + formatTime(match.kickoff),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (live) HindsColors.Accent else sub,
                    maxLines = 1,
                )
            }
            TeamLine(match.home, match.homeScore.takeIf { match.score != null })
            TeamLine(match.away, match.awayScore.takeIf { match.score != null })
            Text(match.competition, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TeamLine(team: String, score: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(team, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (score != null) Text(score, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** A recommendation: what's on (with progress) or coming up, and on which channel. */
@Composable
private fun PickCard(p: HomePick, now: Long, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = p.pick.start <= now
    Surface(
        onClick = onClick,
        modifier = Modifier.size(250.dp, 96.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = focusColors(focused, container = HindsColors.Surface, content = HindsColors.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        val sub = if (focused) HindsColors.OnFocus.copy(alpha = 0.7f) else HindsColors.Muted
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                listOfNotNull(
                    if (on) stringResource(R.string.home_on_now) else stringResource(R.string.home_at, formatTime(p.pick.start)),
                    stringResource(R.string.home_you_watch).takeIf { p.pick.liked },
                ).joinToString("  ·  "),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (p.pick.liked && !focused) HindsColors.Accent else sub,
                maxLines = 1,
            )
            Text(p.pick.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.channel.number}  ${p.channel.name}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (on && p.pick.stop > p.pick.start) {
                    ProgressLine((now - p.pick.start).toFloat() / (p.pick.stop - p.pick.start), Modifier.width(60.dp))
                }
            }
        }
    }
}

private const val RECENT_CHIPS = 4
private const val SHELF_MAX = 15
