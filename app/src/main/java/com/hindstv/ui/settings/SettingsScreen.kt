package com.hindstv.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hindstv.BuildConfig
import com.hindstv.R
import com.hindstv.core.Diagnostics
import com.hindstv.ui.AccountUi
import com.hindstv.ui.SettingsSection
import com.hindstv.ui.SettingsViewModel
import com.hindstv.ui.SourceKind
import com.hindstv.ui.components.NavBackHandler
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.TopNav
import com.hindstv.ui.components.focusColors
import com.hindstv.ui.components.focusRing
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.DarkPalette
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsPalette
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.theme.HindsStyle
import com.hindstv.ui.theme.LightPalette
import com.hindstv.ui.theme.LocalHindsPalette
import com.hindstv.ui.theme.ThemeMode
import com.hindstv.ui.theme.wallpapersFor
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings: a rail of sections on the left (focus previews), the chosen one on the right. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onNavigate: (NavSection) -> Unit,
    onExit: () -> Unit,
    onChangeAccount: () -> Unit,
    onRefreshChannels: () -> Unit,
    onEditList: (Long, String) -> Unit,
) {
    val section by vm.section.collectAsStateWithLifecycle()
    val navBar = rememberNavBarState()
    val railFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(HindsColors.Background).padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TopNav(NavSection.Settings, onNavigate, state = navBar)
        }
        Row(Modifier.fillMaxSize().padding(top = 24.dp)) {
            LazyColumn(
                Modifier.width(260.dp).fillMaxHeight().focusRestorer().focusGroup(),
                contentPadding = PaddingValues(start = 34.dp, end = 6.dp, bottom = 24.dp),
            ) {
                items(SettingsSection.entries, key = { it.name }) { entry ->
                    ListItem(
                        selected = entry == section,
                        onClick = { vm.select(entry) },
                        headlineContent = { Text(stringResource(entry.label())) },
                        colors = HindsStyle.listItemColors(),
                        shape = HindsStyle.listItemShape(),
                        scale = HindsStyle.noScale(),
                        modifier = Modifier
                            .then(if (entry == section) Modifier.focusRequester(railFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused && entry != section) vm.select(entry) },
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (section) {
                    SettingsSection.Appearance -> Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { AppearancePane(vm) }
                    SettingsSection.Account -> AccountPane(vm, onChangeAccount, onRefreshChannels)
                    SettingsSection.Favourites -> FavouritesPane(vm, onEditList)
                    SettingsSection.Playback -> PlaybackPane(vm)
                    SettingsSection.History -> HistoryPane(vm)
                    SettingsSection.Diagnostics -> DiagnosticsPane(vm)
                    SettingsSection.About -> AboutPane()
                }
            }
        }
    }

    NavBackHandler(navBar, onExit = onExit)
    LaunchedEffect(Unit) { railFocus.requestFocusSoon() }
}

private fun SettingsSection.label() = when (this) {
    SettingsSection.Appearance -> R.string.settings_appearance
    SettingsSection.Account -> R.string.settings_account
    SettingsSection.Favourites -> R.string.settings_favourites
    SettingsSection.Playback -> R.string.settings_playback
    SettingsSection.History -> R.string.settings_history
    SettingsSection.Diagnostics -> R.string.settings_diagnostics
    SettingsSection.About -> R.string.settings_about
}

@Composable
private fun PaneTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color = HindsColors.Muted) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Field(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = HindsColors.Muted, modifier = Modifier.width(180.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppearancePane(vm: SettingsViewModel) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_appearance))
    Hint(stringResource(R.string.appearance_hint))
    Text(
        stringResource(R.string.appearance_theme),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ThemeMode.entries.forEach { option ->
            val (label, hint) = when (option) {
                ThemeMode.Dark -> R.string.theme_dark to R.string.theme_dark_hint
                ThemeMode.Light -> R.string.theme_light to R.string.theme_light_hint
                ThemeMode.Auto -> R.string.theme_auto to R.string.theme_auto_hint
            }
            ThemeCard(option, stringResource(label), stringResource(hint), selected = option == mode) { vm.setThemeMode(option) }
        }
    }
    val wallpapers by vm.wallpapers.collectAsStateWithLifecycle()
    val light = LocalHindsPalette.current.isLight
    val chosen = if (light) wallpapers.second else wallpapers.first
    Text(
        stringResource(R.string.appearance_wallpaper) + "  ·  " + stringResource(if (light) R.string.wallpaper_for_light else R.string.wallpaper_for_dark),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        wallpapersFor(light).forEachIndexed { index, wall ->
            WallpaperCard(stringResource(wall.name), wall.image, selected = index == chosen) { vm.setWallpaper(light, index) }
        }
    }
    val home by vm.startWithHome.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.appearance_start),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioPill(stringResource(R.string.start_last_channel), selected = !home) { vm.setStartWithHome(false) }
        RadioPill(stringResource(R.string.start_home), selected = home) { vm.setStartWithHome(true) }
    }
}

/** A wallpaper thumbnail (decoded at thumbnail size) with its name; the chosen one has a check. */
@Composable
private fun WallpaperCard(name: String, @DrawableRes image: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(63.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = HindsColors.Surface, focusedContainerColor = HindsColors.Surface),
            border = focusRing(
                focused,
                shape,
                idle = if (selected) Border(BorderStroke(2.dp, HindsColors.Accent), inset = 0.dp, shape = shape) else Border.None,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).background(HindsColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = HindsColors.OnAccent)
                }
            }
        }
        Text(name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (selected) HindsColors.Text else HindsColors.Muted)
    }
}

/** One choice of a few: a ring that fills when chosen; the chosen one sits on SurfaceRaised in Accent. */
@Composable
private fun RadioPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.height(34.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        colors = focusColors(
            focused,
            container = if (selected) HindsColors.SurfaceRaised else HindsColors.Chip,
            content = if (selected) HindsColors.Accent else HindsColors.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = 11.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val content = LocalContentColor.current
            Box(
                Modifier.size(13.dp).border(1.5.dp, if (selected || focused) content else HindsColors.ChipLine, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(6.dp).background(content, CircleShape))
            }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A theme option: a mini preview of that palette, a check when chosen, and a caption. Selecting applies it at once. */
@Composable
private fun ThemeCard(mode: ThemeMode, label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(186.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(90.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(HindsShapes.Card),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = HindsColors.Surface,
                focusedContainerColor = HindsColors.Surface,
                pressedContainerColor = HindsColors.Surface,
            ),
            border = focusRing(
                focused,
                HindsShapes.Card,
                idle = Border(BorderStroke(1.dp, HindsColors.Hairline), inset = 0.dp, shape = HindsShapes.Card),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize()) {
                when (mode) {
                    ThemeMode.Dark -> ThemePreview(DarkPalette, Modifier.weight(1f))
                    ThemeMode.Light -> ThemePreview(LightPalette, Modifier.weight(1f))
                    ThemeMode.Auto -> {
                        ThemePreview(DarkPalette, Modifier.weight(1f), compact = true)
                        ThemePreview(LightPalette, Modifier.weight(1f), compact = true)
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).background(HindsColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", style = MaterialTheme.typography.labelMedium, color = HindsColors.OnAccent)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) HindsColors.Accent else HindsColors.Text,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** A sketch of a screen in [p]: eyebrow, title, a line, a button and a row of tiles. Plain boxes, nothing to decode. */
@Composable
private fun ThemePreview(p: HindsPalette, modifier: Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxHeight().background(p.background).padding(start = if (compact) 8.dp else 11.dp, top = 13.dp)) {
        Box(Modifier.size(32.dp, 4.dp).background(p.accent, HindsShapes.Pill))
        Box(Modifier.padding(top = 5.dp).size(if (compact) 60.dp else 75.dp, 10.dp).background(p.text, RoundedCornerShape(3.dp)))
        Box(Modifier.padding(top = 5.dp).size(if (compact) 50.dp else 60.dp, 4.dp).background(p.muted, HindsShapes.Pill))
        Box(Modifier.padding(top = 8.dp).size(if (compact) 32.dp else 36.dp, 11.dp).background(p.focus, HindsShapes.Pill))
        Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(if (compact) 3 else 4) {
                Box(Modifier.size(if (compact) 23.dp else 36.dp, 13.dp).background(p.plate, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun AccountPane(vm: SettingsViewModel, onChangeAccount: () -> Unit, onRefreshChannels: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_account))
    val a: AccountUi? = account
    if (a == null) {
        Hint(stringResource(R.string.settings_no_account))
    } else {
        Field(
            stringResource(R.string.settings_source),
            stringResource(if (a.kind == SourceKind.Portal) R.string.settings_source_portal else R.string.settings_source_playlist),
        )
        Field(stringResource(R.string.settings_server), a.address)
        a.maskedMac?.let { Field(stringResource(R.string.field_mac), it) }
        Field(stringResource(R.string.settings_channels), a.channels.toString())
        Field(
            stringResource(R.string.settings_last_sync),
            if (a.lastSyncAt > 0) formatDateTime(a.lastSyncAt) else stringResource(R.string.settings_never),
        )
        if (a.kind == SourceKind.Portal) {
            val expiry = when {
                a.expiryLoading -> stringResource(R.string.settings_checking)
                a.expiryError != null -> a.expiryError
                else -> a.expiry ?: stringResource(R.string.settings_not_given)
            }
            Field(stringResource(R.string.settings_expiry), expiry)
            a.plan?.let { Field(stringResource(R.string.settings_plan), it) }
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.settings_change_account), onClick = onChangeAccount, primary = true)
        if (a != null) PillButton(stringResource(R.string.action_refresh), onClick = onRefreshChannels)
    }
}

@Composable
private fun FavouritesPane(vm: SettingsViewModel, onEditList: (Long, String) -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_favourites))
    Hint(stringResource(R.string.settings_favourites_hint))
    if (lists.isEmpty()) {
        Hint(stringResource(R.string.settings_no_lists))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(lists, key = { it.id }) { list ->
            Row(
                Modifier.fillMaxWidth().background(HindsColors.Surface, HindsShapes.Large).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("★  " + list.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pluralStringResource(R.plurals.settings_list_count, list.count, list.count),
                        style = MaterialTheme.typography.bodySmall,
                        color = HindsColors.Muted,
                    )
                }
                if (list != lists.first()) PillButton("▲", onClick = { vm.moveList(list.id, -1) })
                if (list != lists.last()) PillButton("▼", onClick = { vm.moveList(list.id, 1) })
                PillButton(stringResource(R.string.settings_rename_delete), onClick = { onEditList(list.id, list.name) })
            }
        }
    }
}

@Composable
private fun PlaybackPane(vm: SettingsViewModel) {
    val invert by vm.invertUpDown.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_playback))
    PillButton(
        stringResource(R.string.settings_invert, stringResource(if (invert) R.string.on else R.string.off)),
        onClick = { vm.setInvertUpDown(!invert) },
        primary = invert,
    )
    Hint(stringResource(R.string.settings_invert_hint))
}

@Composable
private fun HistoryPane(vm: SettingsViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_history))
    val resumeDone = stringResource(R.string.settings_resume_cleared)
    val searchDone = stringResource(R.string.settings_searches_cleared)
    PillButton(stringResource(R.string.settings_clear_resume), onClick = { vm.clearResumeHistory(resumeDone) })
    Hint(stringResource(R.string.settings_clear_resume_hint))
    PillButton(stringResource(R.string.settings_clear_searches), onClick = { vm.clearRecentSearches(searchDone) })
    message?.let { Hint(it, color = HindsColors.Accent) }
}

@Composable
private fun DiagnosticsPane(vm: SettingsViewModel) {
    val entries by vm.diagnostics.collectAsStateWithLifecycle()
    PaneTitle(stringResource(R.string.settings_diagnostics))
    Hint(stringResource(R.string.settings_diagnostics_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.settings_refresh), onClick = vm::refreshDiagnostics, primary = true)
        PillButton(stringResource(R.string.settings_clear), onClick = vm::clearDiagnostics)
    }
    if (entries.isEmpty()) {
        Hint(stringResource(R.string.settings_diagnostics_empty))
        return
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(entries, key = { System.identityHashCode(it) }) { entry -> DiagnosticRow(entry, timeFormat.format(Date(entry.atMs))) }
    }
}

/** Focusable, so the D-pad can scroll through the log; Select does nothing. */
@Composable
private fun DiagnosticRow(entry: Diagnostics.Entry, time: String) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
        colors = focusColors(focused, container = HindsColors.Surface, content = HindsColors.Text),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(time, style = MaterialTheme.typography.bodySmall, color = if (focused) HindsColors.OnFocus else HindsColors.Faint)
            Text(entry.area, style = MaterialTheme.typography.labelMedium, color = if (focused) HindsColors.OnFocus else HindsColors.Accent)
            Text(entry.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AboutPane() {
    PaneTitle(stringResource(R.string.app_name))
    Field(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
    Hint(stringResource(R.string.settings_about_text))
    Hint(stringResource(R.string.settings_licences))
    // Something focusable, so the rail isn't the only place focus can be.
    PillButton(stringResource(R.string.settings_ok), onClick = {})
}

private fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.UK).format(Date(ms))
