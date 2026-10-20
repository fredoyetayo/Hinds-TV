package com.hindstv.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
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
import com.hindstv.data.ChannelRepository
import com.hindstv.ui.AppViewModel
import com.hindstv.ui.components.ChannelTile
import com.hindstv.ui.components.focusColors
import com.hindstv.ui.components.formatTime
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/**
 * ◀ in full screen: a strip of channels along the bottom of the picture — Recently watched, the
 * first favourites list, or All. Select watches, ▼ or Back hides it, ⏪ goes to the last channel.
 */
@Composable
fun BoxScope.RecentStrip(vm: AppViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val group by vm.stripGroup.collectAsStateWithLifecycle()
    val channels by vm.stripChannels.collectAsStateWithLifecycle()
    val homeList by vm.homeList.collectAsStateWithLifecycle()
    val previous by vm.previousChannel.collectAsStateWithLifecycle()
    val onAir by vm.onAir.collectAsStateWithLifecycle()
    val now by vm.minute.collectAsStateWithLifecycle()
    val current = playback.channel
    val tileFocus = remember { FocusRequester() }

    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.54f)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.26f to HindsColors.Panel, 1f to HindsColors.Panel)),
    )

    current?.let { channel ->
        Row(
            Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 22.dp).height(32.dp)
                .background(HindsColors.Panel, HindsShapes.Pill).padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (channel.number > 0) {
                Text(
                    channel.number.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HindsColors.Accent,
                    modifier = Modifier.background(HindsColors.SurfaceRaised, HindsShapes.Pill).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            onAir[channel.id]?.let {
                Text(
                    it.title + " · " + stringResource(R.string.until_time, formatTime(it.stop)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = HindsColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(300.dp),
                )
            }
        }
    }

    Row(
        Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 22.dp).height(32.dp)
            .background(HindsColors.Panel, HindsShapes.Pill).padding(start = 13.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(formatTime(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        previous?.let { last ->
            Box(Modifier.width(1.dp).height(16.dp).background(HindsColors.Hairline))
            Row(
                Modifier.height(24.dp).background(HindsColors.SurfaceRaised, HindsShapes.Pill).padding(start = 4.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KeyCap("⏪")
                Text(stringResource(R.string.last_channel), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted)
                Text(listOf(last.number.takeIf { it > 0 }?.toString(), last.name).filterNotNull().joinToString(" "), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    Column(
        Modifier.align(Alignment.BottomStart).padding(bottom = 24.dp).onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key.nativeKeyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                vm.recall()
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StripTab(stringResource(R.string.group_recent), group == ChannelRepository.RECENT) { vm.selectStripGroup(ChannelRepository.RECENT) }
                homeList?.let { list -> StripTab(list.name, group == list.groupId) { vm.selectStripGroup(list.groupId) } }
                StripTab(stringResource(R.string.genre_all), group == ChannelRepository.ALL) { vm.selectStripGroup(ChannelRepository.ALL) }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyHint("◀ ▶", stringResource(R.string.hint_browse))
                KeyHint("OK", stringResource(R.string.hint_watch))
                KeyHint("▼", stringResource(R.string.hint_hide))
            }
        }
        val listState = rememberLazyListState()
        val focusIndex = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        if (channels.isEmpty()) {
            Text(
                stringResource(R.string.row_empty),
                fontSize = 13.sp,
                color = HindsColors.Muted,
                modifier = Modifier.padding(horizontal = 48.dp).height(132.dp),
            )
        } else {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                modifier = Modifier.onKeyEvent {
                    // Nothing below the tiles: ▼ hides the strip.
                    if (it.type == KeyEventType.KeyDown && it.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        vm.hideChannelList()
                        true
                    } else {
                        false
                    }
                },
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelTile(
                        channel,
                        onAir[channel.id],
                        now,
                        onClick = { vm.play(channel, group) },
                        modifier = if (index == focusIndex) Modifier.focusRequester(tileFocus) else Modifier,
                        width = 156.dp,
                        height = 88.dp,
                        nameSize = 14.sp,
                        watching = channel.id == current?.id,
                    )
                }
            }
            LaunchedEffect(group, channels.isNotEmpty()) {
                listState.scrollToItem(focusIndex)
                tileFocus.requestFocusSoon()
            }
        }
    }

    BackHandler { vm.hideChannelList() }
}

/** A tab that selects its group as soon as it is focused (cheap: the rows come from the database). */
@Composable
private fun StripTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        modifier = Modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && !selected) onSelect()
        },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        colors = focusColors(focused, container = Color.Transparent, content = if (selected) HindsColors.Text else HindsColors.Muted),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Intrinsic width: the underline spans the label, not the whole row.
        Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(
                Modifier.padding(top = 3.dp).height(2.dp).fillMaxWidth()
                    .background(if (selected && !focused) HindsColors.Accent else Color.Transparent, HindsShapes.Pill),
            )
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.border(BorderStroke(1.dp, HindsColors.ChipLine), HindsShapes.Small).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KeyCap(key)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = HindsColors.Muted)
    }
}
