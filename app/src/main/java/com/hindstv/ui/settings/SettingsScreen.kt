package com.hindstv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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
import com.hindstv.ui.components.rememberNavBarState
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.theme.HindsStyle
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
