package com.hindstv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.R
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/** The app's sections, shown as pills along the top of every browsing screen. */
enum class NavSection(val label: Int) {
    Live(R.string.nav_live),
    Guide(R.string.nav_guide),
    Videos(R.string.nav_videos),
    Radio(R.string.nav_radio),
    Search(R.string.nav_search),
    Settings(R.string.nav_settings),
}

/** "Live · Guide · Videos · Radio · Settings" in a dark capsule; the current section is raised. */
@Composable
fun TopNav(
    current: NavSection,
    onNavigate: (NavSection) -> Unit,
    modifier: Modifier = Modifier,
    currentFocus: FocusRequester? = null,
) {
    Row(
        modifier
            .background(HindsColors.Surface, HindsShapes.Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NavSection.entries.forEach { section ->
            val selected = section == current
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onNavigate(section) },
                modifier = (if (selected && currentFocus != null) Modifier.focusRequester(currentFocus) else Modifier)
                    .onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
                colors = focusColors(
                    focused,
                    container = if (selected) HindsColors.Hairline else Color.Transparent,
                    content = if (selected) HindsColors.Text else HindsColors.Muted,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    stringResource(section.label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** Rounded pill button: gold when [primary], otherwise a quiet translucent fill. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        colors = focusColors(
            focused,
            container = if (primary) HindsColors.Accent else Color.White.copy(alpha = 0.12f),
            content = if (primary) HindsColors.OnAccent else HindsColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** Artwork, or a calm coloured tile with initials when the provider has none. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(tileColor(title)), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                remember(title) { initialsOf(title) },
                style = MaterialTheme.typography.headlineSmall,
                color = HindsColors.Text.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * A 16:9 card with a title and meta line under it. Focus draws a white ring (no scaling: the
 * stick's GPU is weak). [progress] shows how much has been watched.
 */
@Composable
fun PosterCard(
    title: String,
    meta: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    progress: Float? = null,
    badge: String? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = HindsColors.Surface,
                focusedContainerColor = HindsColors.Surface,
            ),
            border = focusRing(focused, HindsShapes.Medium),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(image, title, Modifier.fillMaxSize().clip(HindsShapes.Medium))
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = HindsColors.Text,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(HindsColors.Background.copy(alpha = 0.8f), HindsShapes.Pill)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (progress != null) {
                    ProgressLine(
                        progress,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        height = 4.dp,
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (focused) HindsColors.Text else HindsColors.Text.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Focus visuals driven by the real focus state. tv-material's Surface tracks focus through its
 * interaction source, which misses focus given programmatically while a screen is switching, and
 * the focused item would then look unfocused.
 */
@Composable
fun focusColors(focused: Boolean, container: Color, content: Color) = ClickableSurfaceDefaults.colors(
    containerColor = if (focused) HindsColors.Focus else container,
    contentColor = if (focused) HindsColors.OnFocus else content,
    focusedContainerColor = HindsColors.Focus,
    focusedContentColor = HindsColors.OnFocus,
    pressedContainerColor = HindsColors.Focus,
    pressedContentColor = HindsColors.OnFocus,
)

/** White focus ring for cards and tiles (see [focusColors] for why it follows [focused]). */
@Composable
fun focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape, idle: Border = Border.None) =
    ClickableSurfaceDefaults.border(
        border = if (focused) Border(BorderStroke(3.dp, HindsColors.Focus), inset = 0.dp, shape = shape) else idle,
        focusedBorder = Border(BorderStroke(3.dp, HindsColors.Focus), inset = 0.dp, shape = shape),
    )

/** Section heading above a row of cards. */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier)
}

/** Static level meter for radio (purely decorative: no animation, the GPU stays idle). */
@Composable
fun LevelBars(modifier: Modifier = Modifier, color: Color = HindsColors.Accent) {
    Row(modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        LEVELS.forEach { level ->
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight(level)
                    .background(color.copy(alpha = 0.35f + 0.65f * level), HindsShapes.Pill),
            )
        }
    }
}

private val LEVELS = listOf(0.35f, 0.6f, 0.85f, 0.5f, 0.95f, 0.7f, 0.4f, 0.8f, 0.55f, 1f, 0.65f, 0.45f, 0.75f, 0.3f, 0.6f, 0.9f, 0.5f, 0.7f)

private val TILE_COLOURS = listOf(
    Color(0xFF3A2F45), Color(0xFF243A4A), Color(0xFF2E4A3A), Color(0xFF4A3A24),
    Color(0xFF4A2F2F), Color(0xFF2F3A4A), Color(0xFF3D3524), Color(0xFF2A3B36),
)

/** A stable colour per title so a library without artwork still looks intentional. */
fun tileColor(title: String): Color = TILE_COLOURS[(title.hashCode() and 0x7fffffff) % TILE_COLOURS.size]

fun initialsOf(name: String): String =
    name.split(' ', '-', '_', ':').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "▶" }

/** "1h 52m", "45m". Rounds up, so 20 seconds left reads "1m left", never "0m left". */
fun formatDuration(ms: Long): String {
    val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "1:02:03" / "7:12" for player timecodes. */
fun formatTimecode(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Moves focus to [this] once it is attached to something on screen. Lazy rows compose their
 * items a frame or two after the data arrives, and when the previously focused item (say the card
 * that was just clicked) leaves the screen, Compose clears focus a frame later, which would undo
 * an early request. So keep asking for the first few frames, then until it succeeds.
 */
suspend fun FocusRequester.requestFocusSoon(frames: Int = 30): Boolean {
    var granted = false
    repeat(frames) { frame ->
        withFrameNanos { }
        granted = runCatching { requestFocus() }.getOrDefault(false)
        if (granted && frame >= SETTLE_FRAMES) return true
    }
    return granted
}

private const val SETTLE_FRAMES = 4
