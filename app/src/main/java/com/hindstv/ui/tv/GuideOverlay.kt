package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.data.ChannelRepository
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.components.Clock
import com.hindstv.ui.components.formatDay
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.timeRange
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsPalette
import com.hindstv.ui.theme.LocalHindsPalette
import kotlin.math.max
import kotlin.math.min

private const val SLOT_S = 30 * 60L
private const val SPAN_S = 2 * 60 * 60L

/**
 * TV guide for the current group. The whole grid is one focus target drawn on a Canvas: the
 * focused cell is our own state, so thousands of programmes never become thousands of focusable
 * composables (the main jank trap on a 2 GB stick).
 */
@Composable
fun GuideOverlay(vm: AppViewModel) {
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

    BoxWithConstraints(Modifier.fillMaxSize().background(HindsColors.Background.copy(alpha = 0.97f))) {
        val rowHeight = 54.dp
        val headerHeight = 132.dp
        val timelineHeight = 34.dp
        visibleRows = max(1, ((maxHeight - headerHeight - timelineHeight - 40.dp) / rowHeight).toInt())

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            GuideHeader(
                groupTitle = genres.firstOrNull { it.id == selectedGenre }?.title.orEmpty(),
                channel = focusedChannel,
                programme = focused,
                nowSec = minute,
                modifier = Modifier.height(headerHeight),
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
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
                drawGuide(
                    measurer = measurer,
                    rows = rowChannels,
                    firstRow = firstRow,
                    focusedRow = focusedRow,
                    focusTime = focusTime,
                    windowStart = windowStart,
                    nowSec = minute,
                    programmes = programmes,
                    rowHeight = rowHeight.toPx(),
                    timelineHeight = timelineHeight.toPx(),
                    noGuide = noGuideText,
                    colors = palette,
                )
            }
            Text(
                stringResource(R.string.guide_hint),
                style = MaterialTheme.typography.labelSmall,
                color = HindsColors.Faint,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun GuideHeader(
    groupTitle: String,
    channel: ChannelRow?,
    programme: ProgrammeEntity?,
    nowSec: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${stringResource(R.string.action_guide)}  ·  ⏪  $groupTitle  ⏩",
                style = MaterialTheme.typography.labelLarge,
                color = HindsColors.Accent,
            )
            Text(
                programme?.title ?: channel?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(programme?.timeRange(), channel?.name).joinToString("  ·  ")
                    .ifEmpty { stringResource(R.string.no_guide) },
                style = MaterialTheme.typography.bodyMedium,
                color = HindsColors.Muted,
                maxLines = 1,
            )
            programme?.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Clock(nowSec)
            Text(formatDay(nowSec), style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted)
        }
    }
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
    timelineHeight: Float,
    noGuide: String,
    colors: HindsPalette,
) {
    val channelWidth = 220.dp.toPx()
    val gridLeft = channelWidth
    val gridWidth = size.width - gridLeft
    val gap = 3.dp.toPx()
    val pad = 12.dp.toPx()
    val radius = CornerRadius(8.dp.toPx())
    fun xOf(t: Long) = gridLeft + (t - windowStart).toFloat() / SPAN_S * gridWidth

    val label = TextStyle(fontSize = 13.sp, color = colors.muted)
    val titleStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val timeStyle = TextStyle(fontSize = 12.sp)

    // Timeline
    var t = windowStart
    while (t < windowStart + SPAN_S) {
        val x = xOf(t)
        drawText(measurer, formatTime(t), Offset(x + 6.dp.toPx(), 4.dp.toPx()), label)
        drawLine(colors.hairline, Offset(x, timelineHeight - 6.dp.toPx()), Offset(x, timelineHeight), 1.dp.toPx())
        t += SLOT_S
    }

    rows.forEachIndexed { i, channel ->
        val top = timelineHeight + i * rowHeight
        val isFocusedRow = firstRow + i == focusedRow
        val cellHeight = rowHeight - gap

        // Channel cell
        if (isFocusedRow) drawRoundRect(colors.surfaceRaised, Offset(0f, top), Size(channelWidth - gap, cellHeight), radius)
        val number = if (channel.number > 0) channel.number.toString() else ""
        drawText(measurer, number, Offset(pad, top + (cellHeight - 18.sp.toPx()) / 2), label)
        drawSingleLine(
            measurer, channel.name, titleStyle.copy(color = colors.text),
            Offset(pad + 44.dp.toPx(), top + (cellHeight - 20.sp.toPx()) / 2),
            (channelWidth - pad * 2 - 44.dp.toPx()).toInt(),
        )

        // Programme cells
        val list = programmes[channel.id].orEmpty().filter { it.stop > windowStart && it.start < windowStart + SPAN_S }
        if (list.isEmpty()) {
            val bg = if (isFocusedRow) colors.focus else colors.surface
            drawRoundRect(bg, Offset(gridLeft, top), Size(gridWidth, cellHeight), radius)
            drawSingleLine(
                measurer, noGuide,
                timeStyle.copy(color = if (isFocusedRow) colors.onFocus else colors.faint),
                Offset(gridLeft + pad, top + (cellHeight - 16.sp.toPx()) / 2), (gridWidth - pad * 2).toInt(),
            )
        }
        for (p in list) {
            val x0 = max(gridLeft, xOf(p.start))
            val x1 = min(size.width, xOf(p.stop)) - gap
            if (x1 - x0 < 4f) continue
            val isFocused = isFocusedRow && p.start <= focusTime && p.stop > focusTime
            val airing = p.start <= nowSec && p.stop > nowSec
            val bg = when {
                isFocused -> colors.focus
                airing -> colors.surfaceRaised
                else -> colors.surface
            }
            drawRoundRect(bg, Offset(x0, top), Size(x1 - x0, cellHeight), radius)
            val textWidth = (x1 - x0 - pad * 2).toInt()
            if (textWidth > 12) {
                val fg = when {
                    isFocused -> colors.onFocus
                    p.stop <= nowSec -> colors.faint
                    else -> colors.text
                }
                drawSingleLine(measurer, p.title, titleStyle.copy(color = fg), Offset(x0 + pad, top + 6.dp.toPx()), textWidth)
                drawSingleLine(
                    measurer, p.timeRange(), timeStyle.copy(color = if (isFocused) colors.onFocus.copy(alpha = 0.7f) else colors.muted),
                    Offset(x0 + pad, top + cellHeight - 20.dp.toPx()), textWidth,
                )
            }
        }
    }

    // "Now" marker
    if (nowSec in windowStart until windowStart + SPAN_S) {
        val x = xOf(nowSec)
        drawLine(colors.accent, Offset(x, timelineHeight - 6.dp.toPx()), Offset(x, timelineHeight + rows.size * rowHeight), 2.dp.toPx())
        drawCircle(colors.accent, 4.dp.toPx(), Offset(x, timelineHeight - 6.dp.toPx()))
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

