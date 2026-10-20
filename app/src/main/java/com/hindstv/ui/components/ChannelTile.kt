package com.hindstv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.R
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/** The provider's logo, fitted, or the channel's initials when there is none. */
@Composable
fun ChannelWordmark(channel: ChannelRow, modifier: Modifier, initialsSize: TextUnit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (channel.logo != null) {
            AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(remember(channel.name) { initialsOf(channel.name) }, fontSize = initialsSize, fontWeight = FontWeight.Bold, color = HindsColors.Muted)
        }
    }
}

/**
 * A channel as a logo tile (Home's My Channels, the recent-channels strip): number, logo, how far
 * through what's on now, with the name and programme under it. Focus draws the ring.
 */
@Composable
fun ChannelTile(
    channel: ChannelRow,
    airing: ProgrammeEntity?,
    nowSec: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 163.dp,
    height: Dp = 75.dp,
    nameSize: TextUnit = 13.sp,
    watching: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(height).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = HindsColors.Plate,
                focusedContainerColor = HindsColors.Plate,
                pressedContainerColor = HindsColors.Plate,
            ),
            border = focusRing(focused, shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            if (channel.number > 0) {
                Text(
                    channel.number.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HindsColors.Muted,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                )
            }
            if (watching) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 7.dp).height(16.dp)
                        .background(HindsColors.SurfaceRaised, HindsShapes.Pill).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(5.dp).background(HindsColors.Live, CircleShape))
                    Text(stringResource(R.string.watching), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
            }
            ChannelWordmark(channel, Modifier.align(Alignment.Center).size(width * 0.6f, height * 0.5f), initialsSize = 20.sp)
            if (airing != null) {
                ProgressLine(airing.progress(nowSec), Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp).fillMaxWidth())
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            Text(channel.name, fontSize = nameSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            airing?.let {
                Text(it.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HindsColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
