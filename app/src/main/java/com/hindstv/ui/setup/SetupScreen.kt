package com.hindstv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hindstv.BuildConfig
import com.hindstv.R
import com.hindstv.data.stalker.PortalProfile
import com.hindstv.ui.SetupForm
import com.hindstv.ui.SetupMode
import com.hindstv.ui.SetupUi
import com.hindstv.ui.components.TvTextField
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes
import com.hindstv.ui.theme.HindsStyle

@Composable
fun SetupScreen(
    state: SetupUi,
    onFormChange: ((SetupForm) -> SetupForm) -> Unit,
    onGenerateMac: () -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form
    var advanced by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    if (state.canCancel) BackHandler(onBack = onCancel)

    Row(Modifier.fillMaxSize().background(HindsColors.Background)) {
        BrandPanel()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModePill(
                    label = stringResource(R.string.mode_portal),
                    selected = form.mode == SetupMode.Portal,
                    onClick = { onFormChange { it.copy(mode = SetupMode.Portal) } },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                ModePill(
                    label = stringResource(R.string.mode_playlist),
                    selected = form.mode == SetupMode.Playlist,
                    onClick = { onFormChange { it.copy(mode = SetupMode.Playlist) } },
                )
            }

            Text(
                stringResource(if (form.mode == SetupMode.Portal) R.string.setup_subtitle_portal else R.string.setup_subtitle_playlist),
                style = MaterialTheme.typography.bodyMedium,
                color = HindsColors.Muted,
            )

            when (form.mode) {
                SetupMode.Portal -> PortalFields(state, advanced, { advanced = !advanced }, onFormChange, onGenerateMac)
                SetupMode.Playlist -> PlaylistFields(state, onFormChange)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !state.busy,
                    shape = ButtonDefaults.shape(HindsShapes.Pill),
                    colors = ButtonDefaults.colors(
                        containerColor = HindsColors.Accent,
                        contentColor = HindsColors.OnAccent,
                        focusedContainerColor = HindsColors.Focus,
                        focusedContentColor = HindsColors.OnFocus,
                    ),
                ) { Text(stringResource(R.string.connect)) }
                if (state.canCancel) {
                    OutlinedButton(onClick = onCancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                }
            }
            state.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Accent) }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Error) }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

@Composable
private fun BrandPanel() {
    Column(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(HindsColors.Surface)
            .padding(horizontal = 40.dp, vertical = 48.dp),
    ) {
        Box(
            Modifier.size(56.dp).background(HindsColors.Accent, HindsShapes.Medium),
            contentAlignment = Alignment.Center,
        ) { Text("▶", style = MaterialTheme.typography.titleLarge, color = HindsColors.OnAccent) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = HindsColors.Muted)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.about_line, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = HindsColors.Faint,
        )
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = HindsStyle.surfaceColors(container = if (selected) HindsColors.SurfaceRaised else Color.Transparent),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun PortalFields(
    state: SetupUi,
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onFormChange: ((SetupForm) -> SetupForm) -> Unit,
    onGenerateMac: () -> Unit,
) {
    val form = state.form
    TvTextField(
        label = stringResource(R.string.field_portal_url),
        value = form.portalUrl,
        onValueChange = { v -> onFormChange { it.copy(portalUrl = v) } },
        placeholder = "http://example.com/c/",
        keyboardType = KeyboardType.Uri,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTextField(
            label = stringResource(R.string.field_mac),
            value = form.mac,
            onValueChange = { v -> onFormChange { it.copy(mac = v) } },
            placeholder = "00:1A:79:XX:XX:XX",
            keyboardType = KeyboardType.Ascii,
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onGenerateMac, enabled = !state.busy) { Text(stringResource(R.string.generate_mac)) }
    }
    state.generatedMac?.let {
        Text(
            stringResource(R.string.generated_mac_hint, it),
            style = MaterialTheme.typography.bodyMedium,
            color = HindsColors.Accent,
            modifier = Modifier
                .fillMaxWidth()
                .background(HindsColors.Surface, HindsShapes.Medium)
                .padding(16.dp),
        )
    }

    Surface(
        onClick = onToggleAdvanced,
        shape = ClickableSurfaceDefaults.shape(HindsShapes.Pill),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = HindsStyle.surfaceColors(container = Color.Transparent),
    ) {
        Text(
            text = stringResource(if (advanced) R.string.advanced_hide else R.string.advanced_show),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }

    if (advanced) {
        Surface(
            onClick = {
                val types = PortalProfile.STB_TYPES
                val next = types[(types.indexOf(form.stbType) + 1).mod(types.size)]
                onFormChange { it.copy(stbType = next) }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(HindsShapes.Medium),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = HindsStyle.surfaceColors(),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                val content = LocalContentColor.current
                Text(stringResource(R.string.field_stb_model), style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.6f))
                Text("${form.stbType}  ·  ${stringResource(R.string.select_to_change)}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        val notSet = stringResource(R.string.field_empty)
        TvTextField(
            label = stringResource(R.string.field_serial), value = form.serialNumber, placeholder = notSet,
            onValueChange = { v -> onFormChange { it.copy(serialNumber = v) } }, modifier = Modifier.fillMaxWidth(),
        )
        TvTextField(
            label = stringResource(R.string.field_device_id), value = form.deviceId, placeholder = notSet,
            onValueChange = { v -> onFormChange { it.copy(deviceId = v) } }, modifier = Modifier.fillMaxWidth(),
        )
        TvTextField(
            label = stringResource(R.string.field_device_id2), value = form.deviceId2, placeholder = notSet,
            onValueChange = { v -> onFormChange { it.copy(deviceId2 = v) } }, modifier = Modifier.fillMaxWidth(),
        )
        TvTextField(
            label = stringResource(R.string.field_signature), value = form.signature, placeholder = notSet,
            onValueChange = { v -> onFormChange { it.copy(signature = v) } }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlaylistFields(state: SetupUi, onFormChange: ((SetupForm) -> SetupForm) -> Unit) {
    val form = state.form
    TvTextField(
        label = stringResource(R.string.field_playlist_url),
        value = form.playlistUrl,
        onValueChange = { v -> onFormChange { it.copy(playlistUrl = v) } },
        placeholder = "https://example.com/playlist.m3u",
        keyboardType = KeyboardType.Uri,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    TvTextField(
        label = stringResource(R.string.field_epg_url),
        value = form.epgUrl,
        onValueChange = { v -> onFormChange { it.copy(epgUrl = v) } },
        placeholder = stringResource(R.string.field_empty),
        keyboardType = KeyboardType.Uri,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}
