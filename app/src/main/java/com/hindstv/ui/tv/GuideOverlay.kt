package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.ChannelRepository
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.GenreItem
import com.hindstv.ui.components.ChannelWordmark
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.formatDay
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.timeRange
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsPalette
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.theme.LocalHindsPalette
import kotlin.math.max
import kotlin.math.min

private const val SLOT_S = 30 * 60L
private const val SPAN_S = 2 * 60 * 60L

private val CHANNEL_WIDTH = 192.dp
private val ROW_HEIGHT = 42.dp
private val ROW_GAP = 4.dp
private val ROW_PITCH = ROW_HEIGHT + ROW_GAP
private val TIMELINE_HEIGHT = 26.dp
private val HEADER_HEIGHT = 112.dp

/** Section bar, header, timeline and key hints: everything above and below the rows. */
private val CHROME_HEIGHT = 56.dp + 17.dp + HEADER_HEIGHT + TIMELINE_HEIGHT + 40.dp

/**
 * TV guide for the current group. The whole grid is one focus target drawn on a Canvas: the
 * focused cell is our own state, so thousands of programmes never become thousands of focusable
 * composables (the main jank trap on a 2 GB stick).
 */
@Composable
fun GuideOverlay(vm: AppViewModel, onNavigate: (NavSection) -> Unit) {
    val navBar = rememberNavBarState()
    val selectedGenre by vm.selectedGenre.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val genreChannels by vm.channels.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val minute by vm.minute.collectAsStateWithLifecycle()
    val revision by vm.epgRevision.collectAsStateWithLifecycle()
    val channels = genreChannels?.takeIf { it.genreId == selectedGenre }?.channels.orEmpty()

    var focusedRow by remember { mutableIntStateOf(-1) }
    var firstRow by remember { mutableIntStateOf(0) }
    var focusTime by remember { mutableLongStateOf(minute) }
    var windowStart by remember { mutableLongStateOf(minute - minute % SLOT_S) }
    var visibleRows by remember { mutableIntStateOf(6) }
    val focusRequester = remember { FocusRequester() }
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val palette = LocalHindsPalette.current
    val noGuideText = stringResource(R.string.no_guide)

    // A different group starts again at its top (or on the playing channel if it is in it).
    LaunchedEffect(selectedGenre) {
        focusedRow = -1
        firstRow = 0
    }
    // Start on the playing channel once the group's channels are known.
    LaunchedEffect(selectedGenre, channels.isNotEmpty()) {
        if (focusedRow < 0 && channels.isNotEmpty()) {
            focusedRow = channels.indexOfFirst { it.id == playback.channel?.id }.coerceAtLeast(0)
            firstRow = max(0, focusedRow - 2)
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val rowRange = firstRow until min(channels.size, firstRow + visibleRows)
    val rowChannels = if (channels.isEmpty()) emptyList() else channels.slice(rowRange)
    val rowIds = rowChannels.map { it.id }
    val programmes by produceState(emptyMap<String, List<ProgrammeEntity>>(), rowIds, windowStart, revision) {
        value = vm.guide(rowIds, windowStart - SLOT_S, windowStart + SPAN_S + SLOT_S).groupBy { it.channelId }
    }
    val focusedChannel = channels.getOrNull(focusedRow)
    val focused = focusedChannel?.let { ch -> programmes[ch.id]?.firstOrNull { it.start <= focusTime && it.stop > focusTime } }

    fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        focusedRow = (focusedRow + delta).coerceIn(0, channels.lastIndex)
        if (focusedRow < firstRow) firstRow = focusedRow
        if (focusedRow >= firstRow + visibleRows) firstRow = focusedRow - visibleRows + 1
    }

    fun moveTime(forward: Boolean) {
        val minTime = minute - ChannelRepository.EPG_PAST_S
        val maxTime = minute + ChannelRepository.EPG_AHEAD_S
        val target = if (forward) focused?.stop ?: (focusTime + SLOT_S) else (focused?.start ?: focusTime) - if (focused != null) 1 else SLOT_S
        focusTime = target.coerceIn(minTime, maxTime)
        while (focusTime >= windowStart + SPAN_S - SLOT_S / 3) windowStart += SLOT_S
        while (focusTime < windowStart) windowStart -= SLOT_S
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(HindsColors.Panel)) {
        // Rows fill what the bar, header, timeline and hints leave.
        visibleRows = max(1, ((maxHeight - CHROME_HEIGHT) / ROW_PITCH).toInt())

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                TopNav(NavSection.Guide, onNavigate, state = navBar)
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatDay(minute), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted, modifier = Modifier.padding(bottom = 3.dp))
                    Text(formatTime(minute), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            GuideHeader(
                group = genres.firstOrNull { it.id == selectedGenre },
                channel = focusedChannel,
                programme = focused,
                modifier = Modifier.padding(top = 17.dp).height(HEADER_HEIGHT),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            // ▲ on the top row leaves the grid for the section bar.
                            KeyEvent.KEYCODE_DPAD_UP -> if (focusedRow <= 0) return@onKeyEvent false else moveRow(-1)
                            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(+1)
                            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-visibleRows)
                            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(+visibleRows)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(forward = true)
                            KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(forward = false)
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                                focusedChannel?.let { vm.play(it, selectedGenre) }
                            KeyEvent.KEYCODE_MENU -> focusedChannel?.let { vm.toggleFavorite(it.id) }
                            // ⏪ ⏩ step through the groups (favourites lists first), ⏯ back to now.
                            KeyEvent.KEYCODE_MEDIA_REWIND -> vm.stepGuideGroup(-1)
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> vm.stepGuideGroup(+1)
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                focusTime = minute
                                windowStart = minute - minute % SLOT_S
                            }
                            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> vm.closeGuide()
                            else -> return@onKeyEvent false
                        }
                        true
                    },
            ) {
                // Channel column: a handful of plain rows (logos are images, which the canvas can't draw).
                Column(Modifier.width(CHANNEL_WIDTH), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    Spacer(Modifier.height(TIMELINE_HEIGHT - ROW_GAP))
                    rowChannels.forEach { GuideChannelCell(it) }
                }
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    drawGuide(
                        measurer = measurer,
                        rows = rowChannels,
                        firstRow = firstRow,
                        focusedRow = focusedRow,
                        focusTime = focusTime,
                        windowStart = windowStart,
                        nowSec = minute,
                        programmes = programmes,
                        rowHeight = ROW_HEIGHT.toPx(),
                        rowGap = ROW_GAP.toPx(),
                        timelineHeight = TIMELINE_HEIGHT.toPx(),
                        noGuide = noGuideText,
                        colors = palette,
                    )
                }
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                stringArrayResource(R.array.guide_keys).forEach {
                    Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HindsColors.Muted, maxLines = 1)
                }
            }
        }
    }
    // Back: first up to the section bar, then out of the guide.
    NavBackHandler(navBar, onExit = vm::closeGuide)
}

@Composable
private fun GuideChannelCell(channel: ChannelRow) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT).background(HindsColors.Surface, HindsShapes.Small).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelWordmark(
            channel,
            Modifier.size(52.dp, 30.dp).background(HindsColors.SurfaceRaised, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 3.dp),
            initialsSize = 11.sp,
        )
        if (channel.number > 0) Text(channel.number.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HindsColors.Muted)
        Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** ⏪ group ⏩, then the focused programme: title, channel and time, description. */
@Composable
private fun GuideHeader(group: GenreItem?, channel: ChannelRow?, programme: ProgrammeEntity?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            GuideKeyCap("⏪")
            if (group?.listId != null) Text("★", fontSize = 12.sp, color = HindsColors.Accent)
            Text(group?.title.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            GuideKeyCap("⏩")
        }
        Text(
            programme?.title ?: channel?.name.orEmpty(),
            fontSize = 28.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.7).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        val meta = listOfNotNull(
            channel?.let { listOfNotNull(it.number.takeIf { n -> n > 0 }?.toString(), it.name).joinToString(" ") },
            programme?.timeRange(),
        ).joinToString("  ·  ")
        Text(
            meta.ifEmpty { stringResource(R.string.no_guide) },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = HindsColors.Muted,
            maxLines = 1,
        )
        programme?.description?.let {
            Text(
                it,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = HindsColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(590.dp),
            )
        }
    }
}

@Composable
private fun GuideKeyCap(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = HindsColors.Muted,
        modifier = Modifier.border(BorderStroke(1.dp, HindsColors.ChipLine), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private fun DrawScope.drawGuide(
    measurer: TextMeasurer,
    rows: List<ChannelRow>,
    firstRow: Int,
    focusedRow: Int,
    focusTime: Long,
    windowStart: Long,
    nowSec: Long,
    programmes: Map<String, List<ProgrammeEntity>>,
    rowHeight: Float,
    rowGap: Float,
    timelineHeight: Float,
    noGuide: String,
    colors: HindsPalette,
) {
    val gridWidth = size.width
    val gap = 4.dp.toPx()
    val pad = 10.dp.toPx()
    val radius = CornerRadius(8.dp.toPx())
    fun xOf(t: Long) = (t - windowStart).toFloat() / SPAN_S * gridWidth

    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.muted)
    val titleStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

    // Timeline: a time at the start of each half hour.
    var t = windowStart
    while (t < windowStart + SPAN_S) {
        drawText(measurer, formatTime(t), Offset(xOf(t), 0f), label)
        t += SLOT_S
    }

    rows.forEachIndexed { i, channel ->
        val top = timelineHeight + i * (rowHeight + rowGap)
        val isFocusedRow = firstRow + i == focusedRow

        val list = programmes[channel.id].orEmpty().filter { it.stop > windowStart && it.start < windowStart + SPAN_S }
        if (list.isEmpty()) {
            val bg = if (isFocusedRow) colors.focus else colors.surface
            drawRoundRect(bg, Offset(0f, top), Size(gridWidth, rowHeight), radius)
            drawSingleLine(
                measurer, noGuide,
                timeStyle.copy(color = if (isFocusedRow) colors.onFocus else colors.faint),
                Offset(pad, top + (rowHeight - 15.sp.toPx()) / 2), (gridWidth - pad * 2).toInt(),
            )
        }
        for (p in list) {
            val x0 = max(0f, xOf(p.start))
            val x1 = min(gridWidth, xOf(p.stop)) - gap
            if (x1 - x0 < 4f) continue
            val isFocused = isFocusedRow && p.start <= focusTime && p.stop > focusTime
            val airing = p.start <= nowSec && p.stop > nowSec
            val bg = when {
                isFocused -> colors.focus
                airing -> colors.surfaceRaised
                else -> colors.surface
            }
            drawRoundRect(bg, Offset(x0, top), Size(x1 - x0, rowHeight), radius)
            val textWidth = (x1 - x0 - pad * 2).toInt()
            if (textWidth > 12) {
                val fg = when {
                    isFocused -> colors.onFocus
                    p.stop <= nowSec -> colors.faint
                    else -> colors.text
                }
                drawSingleLine(measurer, p.title, titleStyle.copy(color = fg), Offset(x0 + pad, top + 5.dp.toPx()), textWidth)
                drawSingleLine(
                    measurer, p.timeRange(), timeStyle.copy(color = if (isFocused) colors.onFocus else colors.muted),
                    Offset(x0 + pad, top + rowHeight - 19.dp.toPx()), textWidth,
                )
            }
        }
    }

    // "Now": a thin Accent line down the grid with the time in a tag on the timeline.
    if (nowSec in windowStart until windowStart + SPAN_S) {
        val x = xOf(nowSec)
        val tagHeight = 18.dp.toPx()
        val bottom = timelineHeight + rows.size * (rowHeight + rowGap) - rowGap
        drawLine(colors.accent, Offset(x, tagHeight), Offset(x, bottom), 1.5.dp.toPx())
        val tag = measurer.measure(formatTime(nowSec), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.onAccent), maxLines = 1)
        val tagWidth = tag.size.width + 10.dp.toPx()
        val tagLeft = (x - tagWidth / 2).coerceIn(0f, gridWidth - tagWidth)
        drawRoundRect(colors.accent, Offset(tagLeft, 0f), Size(tagWidth, tagHeight), CornerRadius(tagHeight / 2))
        drawText(tag, topLeft = Offset(tagLeft + 5.dp.toPx(), (tagHeight - tag.size.height) / 2))
    }
}

private fun DrawScope.drawSingleLine(measurer: TextMeasurer, text: String, style: TextStyle, topLeft: Offset, maxWidth: Int) {
    if (maxWidth <= 0) return
    val layout = measurer.measure(
        text, style, overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false,
        constraints = Constraints(maxWidth = maxWidth),
    )
    drawText(layout, topLeft = topLeft)
}

private fun DrawScope.drawText(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    drawText(measurer.measure(text, style, maxLines = 1, softWrap = false), topLeft = topLeft)
}

